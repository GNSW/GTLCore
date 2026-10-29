/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion.spatial;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PriorityQueue;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;
import org.voxelregion.geometry.Volume;

/**
 * Visits each candidate chunk once, in increasing horizontal distance from an
 * exact half-grid center to the chunk's footprint. Distance is the infimum over
 * the physical [16*x,16*x+16] x [16*z,16*z+16] rectangle; no square root or
 * floating-point comparison is used. Equal distances prefer the chunk that
 * contains the center, then ascending chunk X and Z. Y clips geometry only.
 *
 * <p>A heap merges one frontier entry per row along the shorter horizontal
 * extent. Storage is O(min(chunk width,chunk depth)), never O(chunk area), and
 * is rejected before allocation when the explicit row budget is exceeded.
 * Initialization examines rows, not individual chunks. The default allows
 * 4096 rows (a radius-2000 sphere needs about 251). Iteration classifies the
 * original complete volume, without radial shells or repeated chunk output.
 * As with ChunkCursor, a conservative PARTIAL may be an empty voxel selection;
 * the host must retain the exact volume for its final section/block selection.
 *
 * <p>A constant-size checkpoint stores the last returned ordering key. Restore
 * rebuilds each row's frontier using binary search in O(rows*log(longer extent))
 * before heap insertion, without replaying previously returned chunks. Volume
 * identity and completed world writes still require the host's durable intent
 * and receipts. Mutable, not thread safe. Filtering a long OUTSIDE run can do
 * several classifications in one hasNext call; charge this to the host budget.
 */
public final class CenterOutChunkCursor implements Iterator<ChunkKey> {
    public static final int DEFAULT_MAX_FRONTIER_ROWS = 4096;
    private final Volume volume;
    private final Point3 center;
    private final IntBox clippedBounds;
    private final int minChunkX, maxChunkX, minChunkZ, maxChunkZ;
    private final int centerChunkX, centerChunkZ;
    private final boolean rowsAreZ;
    private final int alongMin, alongMax, rowCount;
    private final PriorityQueue<Row> frontier;
    private boolean started;
    private int lastChunkX, lastChunkZ;
    private ChunkKey next;

    public CenterOutChunkCursor(Volume volume, Point3 center, int minY, int maxY) {
        this(volume, center, minY, maxY, DEFAULT_MAX_FRONTIER_ROWS);
    }

    public CenterOutChunkCursor(Volume volume, Point3 center, int minY, int maxY, int maxFrontierRows) {
        this(volume, center, minY, maxY, maxFrontierRows, null);
    }

    private CenterOutChunkCursor(Volume volume, Point3 center, int minY, int maxY,
            int maxFrontierRows, Checkpoint checkpoint) {
        this.volume = Objects.requireNonNull(volume, "volume");
        this.center = Objects.requireNonNull(center, "center");
        if (maxFrontierRows < 1) throw new IllegalArgumentException("Frontier row budget must be positive");
        clippedBounds = volume.bounds().intersection(new IntBox(IntBox.WORLD_MIN, minY,
                IntBox.WORLD_MIN, IntBox.WORLD_MAX, maxY, IntBox.WORLD_MAX));
        minChunkX = Math.floorDiv(clippedBounds.minX(), 16);
        maxChunkX = Math.floorDiv(clippedBounds.maxX() - 1, 16);
        minChunkZ = Math.floorDiv(clippedBounds.minZ(), 16);
        maxChunkZ = Math.floorDiv(clippedBounds.maxZ() - 1, 16);
        centerChunkX = (int) Math.floorDiv(center.x2(), 32);
        centerChunkZ = (int) Math.floorDiv(center.z2(), 32);
        int width = clippedBounds.isEmpty() ? 0 : maxChunkX - minChunkX + 1;
        int depth = clippedBounds.isEmpty() ? 0 : maxChunkZ - minChunkZ + 1;
        rowsAreZ = depth <= width;
        rowCount = Math.min(width, depth);
        if (rowCount > maxFrontierRows)
            throw new IllegalArgumentException("Center-out cursor exceeds frontier row budget: " + rowCount);
        alongMin = rowsAreZ ? minChunkX : minChunkZ;
        alongMax = rowsAreZ ? maxChunkX : maxChunkZ;
        if (checkpoint != null) {
            if (!clippedBounds.equals(checkpoint.clippedBounds()) || !center.equals(checkpoint.center()))
                throw new IllegalArgumentException("Checkpoint bounds or center disagree with this cursor");
            started = checkpoint.started();
            lastChunkX = checkpoint.lastChunkX(); lastChunkZ = checkpoint.lastChunkZ();
            if (started && (clippedBounds.isEmpty() || lastChunkX < minChunkX || lastChunkX > maxChunkX
                    || lastChunkZ < minChunkZ || lastChunkZ > maxChunkZ
                    || volume.classify(new ChunkKey(lastChunkX, lastChunkZ).bounds(
                            clippedBounds.minY(), clippedBounds.maxY())) == Coverage.OUTSIDE))
                throw new IllegalArgumentException("Checkpoint's last chunk is not a candidate in this volume");
        }
        frontier = new PriorityQueue<>(Math.max(1, rowCount), this::compareRows);
        if (clippedBounds.isEmpty() || checkpoint != null && checkpoint.exhausted()) return;
        int firstRow = rowsAreZ ? minChunkZ : minChunkX;
        int pivot = Math.max(alongMin, Math.min(alongMax, rowsAreZ ? centerChunkX : centerChunkZ));
        for (int i = 0; i < rowCount; i++) {
            int coordinate = firstRow + i;
            IntBox rowBounds = rowsAreZ
                    ? new IntBox(minChunkX * 16, clippedBounds.minY(), coordinate * 16,
                            (maxChunkX + 1) * 16, clippedBounds.maxY(), coordinate * 16 + 16)
                    : new IntBox(coordinate * 16, clippedBounds.minY(), minChunkZ * 16,
                            coordinate * 16 + 16, clippedBounds.maxY(), (maxChunkZ + 1) * 16);
            Coverage coverage = volume.classify(rowBounds);
            if (coverage == Coverage.OUTSIDE) continue;
            Row row = new Row(coordinate, pivot - 1, pivot, coverage == Coverage.INSIDE);
            if (started) {
                row.left = firstAfter(row, row.left, -1, row.left - alongMin + 1);
                row.right = firstAfter(row, row.right, 1, alongMax - row.right + 1);
            }
            if (update(row)) frontier.add(row);
        }
    }

    /**
     * Constant-size last-returned position. Unconsumed hasNext prefetch is not
     * acknowledged. Before the first return, lastChunkX/Z must both be zero.
     */
    public record Checkpoint(int formatVersion, IntBox clippedBounds, Point3 center,
            int lastChunkX, int lastChunkZ, boolean started, boolean exhausted) {
        public Checkpoint {
            if (formatVersion != 1) throw new IllegalArgumentException("Unsupported center-out checkpoint format");
            Objects.requireNonNull(clippedBounds, "clippedBounds"); Objects.requireNonNull(center, "center");
            if (!started && (lastChunkX != 0 || lastChunkZ != 0))
                throw new IllegalArgumentException("Unstarted checkpoint requires canonical zero coordinates");
        }
    }

    public Checkpoint checkpoint() {
        return new Checkpoint(1, clippedBounds, center, lastChunkX, lastChunkZ,
                started, next == null && frontier.isEmpty());
    }

    public static CenterOutChunkCursor restore(Volume volume, Point3 center, int minY, int maxY, Checkpoint checkpoint) {
        return restore(volume, center, minY, maxY, DEFAULT_MAX_FRONTIER_ROWS, checkpoint);
    }

    public static CenterOutChunkCursor restore(Volume volume, Point3 center, int minY, int maxY,
            int maxFrontierRows, Checkpoint checkpoint) {
        return new CenterOutChunkCursor(volume, center, minY, maxY, maxFrontierRows,
                Objects.requireNonNull(checkpoint, "checkpoint"));
    }

    /** Maximum row storage selected at construction, including geometrically skipped rows. */
    public int frontierRows() { return rowCount; }
    /** Conservative engine accounting, not an exact JVM object-layout guarantee. */
    public long estimatedBytes() { return 256L + 128L * rowCount; }

    @Override public boolean hasNext() {
        if (next != null) return true;
        while (!frontier.isEmpty()) {
            Row row = frontier.remove();
            ChunkKey candidate = new ChunkKey(row.x, row.z);
            boolean inside = row.inside;
            if (row.fromLeft) row.left--; else row.right++;
            if (update(row)) frontier.add(row);
            if (inside || volume.classify(candidate.bounds(clippedBounds.minY(), clippedBounds.maxY())) != Coverage.OUTSIDE) {
                next = candidate;
                return true;
            }
        }
        return false;
    }

    @Override public ChunkKey next() {
        if (!hasNext()) throw new NoSuchElementException("Center-out chunk cursor exhausted");
        ChunkKey result = next; next = null;
        started = true; lastChunkX = result.x(); lastChunkZ = result.z();
        return result;
    }

    /** Find the first still-unreturned point in one monotone outward half-row. */
    private int firstAfter(Row row, int start, int step, int count) {
        int lo = 0, hi = count;
        long previousDistance = distance(lastChunkX, lastChunkZ);
        while (lo < hi) {
            int middle = (lo + hi) >>> 1;
            int coordinate = start + step * middle;
            int x = rowsAreZ ? coordinate : row.coordinate;
            int z = rowsAreZ ? row.coordinate : coordinate;
            if (compareKey(distance(x, z), x, z, previousDistance, lastChunkX, lastChunkZ) > 0) hi = middle;
            else lo = middle + 1;
        }
        return start + step * lo;
    }

    private boolean update(Row row) {
        if (row.left < alongMin && row.right > alongMax) return false;
        int lx = rowsAreZ ? row.left : row.coordinate, lz = rowsAreZ ? row.coordinate : row.left;
        int rx = rowsAreZ ? row.right : row.coordinate, rz = rowsAreZ ? row.coordinate : row.right;
        row.fromLeft = row.right > alongMax || row.left >= alongMin
                && compareKey(distance(lx, lz), lx, lz, distance(rx, rz), rx, rz) < 0;
        row.x = row.fromLeft ? lx : rx; row.z = row.fromLeft ? lz : rz;
        row.distance = distance(row.x, row.z);
        return true;
    }

    private int compareRows(Row first, Row second) {
        return compareKey(first.distance, first.x, first.z, second.distance, second.x, second.z);
    }

    private int compareKey(long firstDistance, int firstX, int firstZ, long secondDistance, int secondX, int secondZ) {
        int distanceOrder = Long.compare(firstDistance, secondDistance);
        if (distanceOrder != 0) return distanceOrder;
        boolean firstCenter = firstX == centerChunkX && firstZ == centerChunkZ;
        boolean secondCenter = secondX == centerChunkX && secondZ == centerChunkZ;
        if (firstCenter != secondCenter) return firstCenter ? -1 : 1;
        int xOrder = Integer.compare(firstX, secondX);
        return xOrder != 0 ? xOrder : Integer.compare(firstZ, secondZ);
    }

    private long distance(int x, int z) {
        long dx = axisDistance(center.x2(), x), dz = axisDistance(center.z2(), z);
        return dx * dx + dz * dz;
    }

    private static long axisDistance(long center2, int chunk) {
        long minimum = 32L * chunk, maximum = minimum + 32;
        return center2 < minimum ? minimum - center2 : center2 > maximum ? center2 - maximum : 0;
    }

    private static final class Row {
        final int coordinate;
        final boolean inside;
        int left, right, x, z;
        long distance;
        boolean fromLeft;
        Row(int coordinate, int left, int right, boolean inside) {
            this.coordinate = coordinate; this.left = left; this.right = right; this.inside = inside;
        }
    }
}
