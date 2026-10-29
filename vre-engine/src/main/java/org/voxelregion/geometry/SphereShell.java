/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion.geometry;

import java.util.Objects;

/**
 * An outer closed sphere minus an inner closed sphere, clipped to a half-open
 * build height. Radii and the center use exact half-block units. A voxel is
 * selected precisely when innerRadius^2 &lt; distance^2 &lt;= outerRadius^2.
 * {@link #NO_INNER_RADIUS_2} is a sentinel meaning no inner sphere, so the
 * initial shell includes the center and a zero-radius wave works like Sphere.
 * Equal nonnegative radii select nothing. All ordinary radii have Sphere's
 * bounds. The Y coordinate of the center participates in distance calculations.
 *
 * <p>Classification never gives an incorrect INSIDE or OUTSIDE proof. PARTIAL
 * can conservatively include a discrete shell with no selected voxel; callers
 * must use exact range emission or contains at the leaf. Range emission skips
 * columns wholly within the inner sphere and subtracts Y intervals, rather
 * than enumerating and discarding the inner sphere's voxels.
 */
public final class SphereShell implements Volume {
    public static final long NO_INNER_RADIUS_2 = -1L;

    private final Point3 center;
    private final long innerExclusiveRadius2;
    private final long outerInclusiveRadius2;
    private final long innerSquared;
    private final long outerSquared;
    private final int minY;
    private final int maxY;
    private final IntBox bounds;

    public SphereShell(Point3 center, long innerExclusiveRadius2, long outerInclusiveRadius2) {
        this(center, innerExclusiveRadius2, outerInclusiveRadius2, IntBox.WORLD_MIN, IntBox.WORLD_MAX);
    }

    public SphereShell(Point3 center, long innerExclusiveRadius2, long outerInclusiveRadius2,
            int minY, int maxY) {
        this.center = Objects.requireNonNull(center, "center");
        ExactGeometry.checkRadius(outerInclusiveRadius2);
        if (innerExclusiveRadius2 < NO_INNER_RADIUS_2 || innerExclusiveRadius2 > outerInclusiveRadius2) {
            throw new IllegalArgumentException("Inner radius must be -1 (no inner sphere) or in [0,outerRadius]");
        }
        this.innerExclusiveRadius2 = innerExclusiveRadius2;
        this.outerInclusiveRadius2 = outerInclusiveRadius2;
        this.innerSquared = innerExclusiveRadius2 < 0 ? -1 : innerExclusiveRadius2 * innerExclusiveRadius2;
        this.outerSquared = outerInclusiveRadius2 * outerInclusiveRadius2;
        this.minY = minY;
        this.maxY = maxY;
        IntBox height = new IntBox(IntBox.WORLD_MIN, minY, IntBox.WORLD_MIN,
                IntBox.WORLD_MAX, maxY, IntBox.WORLD_MAX);
        IntBox clipped = new Sphere(center, outerInclusiveRadius2).bounds().intersection(height);
        bounds = innerExclusiveRadius2 == outerInclusiveRadius2
                ? new IntBox(clipped.minX(), clipped.minY(), clipped.minZ(),
                        clipped.minX(), clipped.minY(), clipped.minZ()) : clipped;
    }

    public Point3 center() { return center; }
    public long innerExclusiveRadius2() { return innerExclusiveRadius2; }
    public long outerInclusiveRadius2() { return outerInclusiveRadius2; }
    /** Configured clipping minimum, which can extend beyond the outer sphere's bounds. */
    public int minY() { return minY; }
    /** Configured clipping maximum, excluded. */
    public int maxY() { return maxY; }
    @Override public IntBox bounds() { return bounds; }

    @Override
    public boolean contains(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) return false;
        long distance = ExactGeometry.square(2L * x + 1 - center.x2())
                + ExactGeometry.square(2L * y + 1 - center.y2())
                + ExactGeometry.square(2L * z + 1 - center.z2());
        return distance > innerSquared && distance <= outerSquared;
    }

    @Override
    public Coverage classify(IntBox box) {
        Objects.requireNonNull(box, "box");
        if (!bounds.intersects(box)) return Coverage.OUTSIDE;
        IntBox clipped = bounds.intersection(box);
        long nearest = ExactGeometry.square(ExactGeometry.nearestDistance2(center.x2(), clipped.minX(), clipped.maxX()))
                + ExactGeometry.square(ExactGeometry.nearestDistance2(center.y2(), clipped.minY(), clipped.maxY()))
                + ExactGeometry.square(ExactGeometry.nearestDistance2(center.z2(), clipped.minZ(), clipped.maxZ()));
        if (nearest > outerSquared) return Coverage.OUTSIDE;
        long farthest = ExactGeometry.square(ExactGeometry.farthestDistance2(center.x2(), clipped.minX(), clipped.maxX()))
                + ExactGeometry.square(ExactGeometry.farthestDistance2(center.y2(), clipped.minY(), clipped.maxY()))
                + ExactGeometry.square(ExactGeometry.farthestDistance2(center.z2(), clipped.minZ(), clipped.maxZ()));
        if (farthest <= innerSquared) return Coverage.OUTSIDE;
        return bounds.contains(box) && nearest > innerSquared && farthest <= outerSquared
                ? Coverage.INSIDE : Coverage.PARTIAL;
    }

    @Override
    public void emitRanges(IntBox clip, RangeSink sink) {
        Objects.requireNonNull(sink, "sink");
        IntBox box = bounds.intersection(Objects.requireNonNull(clip, "clip"));
        Coverage coverage = classify(box);
        if (coverage == Coverage.OUTSIDE) return;
        if (coverage == Coverage.INSIDE) {
            ExactGeometry.emitBox(box, sink);
            return;
        }
        long nearestYSquared = ExactGeometry.square(ExactGeometry.nearestDistance2(center.y2(), box.minY(), box.maxY()));
        long farthestYSquared = ExactGeometry.square(ExactGeometry.farthestDistance2(center.y2(), box.minY(), box.maxY()));
        long nearestXSquared = ExactGeometry.square(ExactGeometry.nearestDistance2(center.x2(), box.minX(), box.maxX()));
        long projectedZ = outerSquared - nearestYSquared - nearestXSquared;
        if (projectedZ < 0) return;
        long reachZ = ExactGeometry.floorSqrt(projectedZ);
        int firstZ = Math.max(box.minZ(), ExactGeometry.lowerBound(center.z2(), reachZ));
        int endZ = Math.min(box.maxZ(), ExactGeometry.upperBound(center.z2(), reachZ));
        for (int z = firstZ; z < endZ; z++) {
            long dzSquared = ExactGeometry.square(2L * z + 1 - center.z2());
            long projectedX = outerSquared - nearestYSquared - dzSquared;
            if (projectedX < 0) continue;
            long reachX = ExactGeometry.floorSqrt(projectedX);
            int firstX = Math.max(box.minX(), ExactGeometry.lowerBound(center.x2(), reachX));
            int endX = Math.min(box.maxX(), ExactGeometry.upperBound(center.x2(), reachX));
            if (firstX >= endX) continue;
            long innerProjection = innerSquared - farthestYSquared - dzSquared;
            if (innerProjection < 0) {
                emitColumns(box, z, dzSquared, firstX, endX, sink);
                continue;
            }
            // This entire X interval has every clipped Y center inside the
            // already completed inner ball. Jump over it in constant time.
            long innerReachX = ExactGeometry.floorSqrt(innerProjection);
            int innerFirstX = Math.max(firstX, ExactGeometry.lowerBound(center.x2(), innerReachX));
            int innerEndX = Math.min(endX, ExactGeometry.upperBound(center.x2(), innerReachX));
            if (innerFirstX >= innerEndX) {
                emitColumns(box, z, dzSquared, firstX, endX, sink);
            } else {
                emitColumns(box, z, dzSquared, firstX, innerFirstX, sink);
                emitColumns(box, z, dzSquared, innerEndX, endX, sink);
            }
        }
    }

    private void emitColumns(IntBox box, int z, long dzSquared, int firstX, int endX, RangeSink sink) {
        for (int x = firstX; x < endX; x++) {
            long horizontalSquared = dzSquared + ExactGeometry.square(2L * x + 1 - center.x2());
            long outerRemaining = outerSquared - horizontalSquared;
            if (outerRemaining < 0) continue;
            long outerReach = ExactGeometry.floorSqrt(outerRemaining);
            int lo = Math.max(box.minY(), ExactGeometry.lowerBound(center.y2(), outerReach));
            int hi = Math.min(box.maxY(), ExactGeometry.upperBound(center.y2(), outerReach));
            if (lo >= hi) continue;
            long innerRemaining = innerSquared - horizontalSquared;
            if (innerRemaining < 0) {
                sink.accept(x, z, lo, hi);
                continue;
            }
            long innerReach = ExactGeometry.floorSqrt(innerRemaining);
            int innerLo = Math.max(lo, ExactGeometry.lowerBound(center.y2(), innerReach));
            int innerHi = Math.min(hi, ExactGeometry.upperBound(center.y2(), innerReach));
            if (innerLo >= innerHi) {
                sink.accept(x, z, lo, hi);
            } else {
                if (lo < innerLo) sink.accept(x, z, lo, innerLo);
                if (innerHi < hi) sink.accept(x, z, innerHi, hi);
            }
        }
    }
}
