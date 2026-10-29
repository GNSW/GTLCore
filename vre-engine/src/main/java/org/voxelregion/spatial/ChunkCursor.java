/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion.spatial;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Volume;

/**
 * Lazy, constant-storage chunk iterator for a volume and a half-open build
 * height. Chunks are grouped into 32x32 regions using floor division, ordered
 * by region X, region Z, chunk X, then chunk Z. An OUTSIDE region is skipped;
 * an INSIDE region does not require individual chunk classification.
 * This cursor is mutable and not thread safe; construction does not scan.
 */
public final class ChunkCursor implements Iterator<ChunkKey> {
    private final Volume volume;
    private final IntBox clippedBounds;
    private final int minY;
    private final int maxY;
    private final int minChunkX;
    private final int minChunkZ;
    private final int maxChunkX;
    private final int maxChunkZ;
    private final int minRegionZ;
    private final int maxRegionX;
    private final int maxRegionZ;
    private int regionX;
    private int regionZ;
    private int chunkX;
    private int chunkZ;
    private int startChunkZ;
    private int endChunkX;
    private int endChunkZ;
    private Coverage regionCoverage;
    private boolean regionOpen;
    private boolean exhausted;
    private ChunkKey next;

    public ChunkCursor(Volume volume, int minY, int maxY) {
        this.volume = Objects.requireNonNull(volume, "volume");
        IntBox clipped = volume.bounds().intersection(new IntBox(IntBox.WORLD_MIN, minY,
                IntBox.WORLD_MIN, IntBox.WORLD_MAX, maxY, IntBox.WORLD_MAX));
        this.clippedBounds = clipped;
        this.minY = clipped.minY();
        this.maxY = clipped.maxY();
        exhausted = clipped.isEmpty();
        minChunkX = Math.floorDiv(clipped.minX(), 16);
        minChunkZ = Math.floorDiv(clipped.minZ(), 16);
        maxChunkX = Math.floorDiv(clipped.maxX() - 1, 16);
        maxChunkZ = Math.floorDiv(clipped.maxZ() - 1, 16);
        regionX = Math.floorDiv(minChunkX, 32);
        regionZ = Math.floorDiv(minChunkZ, 32);
        minRegionZ = regionZ;
        maxRegionX = Math.floorDiv(maxChunkX, 32);
        maxRegionZ = Math.floorDiv(maxChunkZ, 32);
    }

    /**
     * Detached next-scan position. No work is performed by checkpoint(). A
     * prefetched result is normalized back to that result's candidate position,
     * so it is returned exactly once after restoration. Exhausted positions
     * have canonical zero coordinates. The format is independent of Java object
     * serialization; hosts should encode fields with their versioned intent.
     */
    public record Checkpoint(int formatVersion, IntBox clippedBounds,
            int regionX, int regionZ, int chunkX, int chunkZ, boolean exhausted) {
        public Checkpoint {
            Objects.requireNonNull(clippedBounds, "clippedBounds");
            if (formatVersion != 1) throw new IllegalArgumentException("Unsupported chunk cursor checkpoint format");
            if (exhausted && (regionX != 0 || regionZ != 0 || chunkX != 0 || chunkZ != 0)) {
                throw new IllegalArgumentException("Exhausted checkpoint must have canonical zero coordinates");
            }
        }
    }

    public Checkpoint checkpoint() {
        if (next != null) {
            return new Checkpoint(1, clippedBounds, Math.floorDiv(next.x(), 32), Math.floorDiv(next.z(), 32),
                    next.x(), next.z(), false);
        }
        if (exhausted) return new Checkpoint(1, clippedBounds, 0, 0, 0, 0, true);
        int x = regionOpen ? chunkX : Math.max(minChunkX, regionX * 32);
        int z = regionOpen ? chunkZ : Math.max(minChunkZ, regionZ * 32);
        return new Checkpoint(1, clippedBounds, regionX, regionZ, x, z, false);
    }

    /**
     * Restores in constant storage without replaying earlier regions. Validates
     * bounds, region membership and coordinate consistency; reclassifies at most
     * one region instead of trusting saved INSIDE evidence. The host must bind
     * the volume parameters to its durable intent hash and authenticate progress
     * using its receipts. A geometrically valid position cannot prove that
     * earlier world writes completed; this method grants no recovery authority.
     */
    public static ChunkCursor restore(Volume volume, int minY, int maxY, Checkpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        ChunkCursor cursor = new ChunkCursor(volume, minY, maxY);
        if (!cursor.clippedBounds.equals(checkpoint.clippedBounds())) {
            throw new IllegalArgumentException("Checkpoint bounds do not match this volume and height");
        }
        if (checkpoint.exhausted()) {
            cursor.exhausted = true;
            return cursor;
        }
        if (cursor.exhausted || checkpoint.regionX() < cursor.regionX || checkpoint.regionX() > cursor.maxRegionX
                || checkpoint.regionZ() < cursor.minRegionZ || checkpoint.regionZ() > cursor.maxRegionZ
                || checkpoint.chunkX() < cursor.minChunkX || checkpoint.chunkX() > cursor.maxChunkX
                || checkpoint.chunkZ() < cursor.minChunkZ || checkpoint.chunkZ() > cursor.maxChunkZ
                || Math.floorDiv(checkpoint.chunkX(), 32) != checkpoint.regionX()
                || Math.floorDiv(checkpoint.chunkZ(), 32) != checkpoint.regionZ()) {
            throw new IllegalArgumentException("Checkpoint is outside its clipped region/chunk range");
        }
        cursor.regionX = checkpoint.regionX();
        cursor.regionZ = checkpoint.regionZ();
        int firstX = Math.max(cursor.minChunkX, cursor.regionX * 32);
        int firstZ = Math.max(cursor.minChunkZ, cursor.regionZ * 32);
        cursor.chunkX = checkpoint.chunkX();
        cursor.chunkZ = checkpoint.chunkZ();
        cursor.startChunkZ = firstZ;
        cursor.endChunkX = Math.min(cursor.maxChunkX, cursor.regionX * 32 + 31);
        cursor.endChunkZ = Math.min(cursor.maxChunkZ, cursor.regionZ * 32 + 31);
        cursor.regionCoverage = volume.classify(new IntBox(firstX * 16, cursor.minY, firstZ * 16,
                (cursor.endChunkX + 1) * 16, cursor.maxY, (cursor.endChunkZ + 1) * 16));
        if (cursor.regionCoverage == Coverage.OUTSIDE) {
            if (cursor.chunkX != firstX || cursor.chunkZ != firstZ) {
                throw new IllegalArgumentException("Checkpoint cannot be inside a skipped OUTSIDE region");
            }
            cursor.regionOpen = false;
        } else {
            cursor.regionOpen = true;
        }
        return cursor;
    }

    @Override
    public boolean hasNext() {
        if (next != null) return true;
        while (!exhausted) {
            if (!regionOpen && !openRegion()) continue;
            ChunkKey candidate = new ChunkKey(chunkX, chunkZ);
            Coverage coarse = regionCoverage;
            advanceChunk();
            if (coarse == Coverage.INSIDE || volume.classify(candidate.bounds(minY, maxY)) != Coverage.OUTSIDE) {
                next = candidate;
                return true;
            }
        }
        return false;
    }

    @Override
    public ChunkKey next() {
        if (!hasNext()) throw new NoSuchElementException("Chunk cursor exhausted");
        ChunkKey result = next;
        next = null;
        return result;
    }

    private boolean openRegion() {
        chunkX = Math.max(minChunkX, regionX * 32);
        chunkZ = Math.max(minChunkZ, regionZ * 32);
        startChunkZ = chunkZ;
        endChunkX = Math.min(maxChunkX, regionX * 32 + 31);
        endChunkZ = Math.min(maxChunkZ, regionZ * 32 + 31);
        regionCoverage = volume.classify(new IntBox(chunkX * 16, minY, chunkZ * 16,
                (endChunkX + 1) * 16, maxY, (endChunkZ + 1) * 16));
        if (regionCoverage == Coverage.OUTSIDE) {
            advanceRegion();
            return false;
        }
        regionOpen = true;
        return true;
    }

    private void advanceChunk() {
        if (chunkZ < endChunkZ) {
            chunkZ++;
        } else if (chunkX < endChunkX) {
            chunkX++;
            chunkZ = startChunkZ;
        } else {
            regionOpen = false;
            advanceRegion();
        }
    }

    private void advanceRegion() {
        if (regionZ < maxRegionZ) {
            regionZ++;
        } else if (regionX < maxRegionX) {
            regionX++;
            regionZ = minRegionZ;
        } else {
            exhausted = true;
        }
    }
}
