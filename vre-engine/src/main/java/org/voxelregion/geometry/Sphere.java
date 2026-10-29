/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion.geometry;

import java.util.Objects;

/**
 * A closed sphere sampled at voxel centers, with an exact half-block center
 * and radius. A zero radius selects one voxel only when all center coordinates
 * are block centers. Doubled radii are in [0,120,000,000]. The bounded inputs
 * make the sum of three squared distances safe in a signed long.
 */
public final class Sphere implements Volume {
    private final Point3 center;
    private final long radius2;
    private final long radiusSquared;
    private final IntBox bounds;

    public Sphere(Point3 center, long radius2) {
        this.center = Objects.requireNonNull(center, "center");
        ExactGeometry.checkRadius(radius2);
        this.radius2 = radius2;
        this.radiusSquared = radius2 * radius2;
        this.bounds = new IntBox(ExactGeometry.lowerBound(center.x2(), radius2),
                ExactGeometry.lowerBound(center.y2(), radius2), ExactGeometry.lowerBound(center.z2(), radius2),
                ExactGeometry.upperBound(center.x2(), radius2), ExactGeometry.upperBound(center.y2(), radius2),
                ExactGeometry.upperBound(center.z2(), radius2));
    }

    /** Accepts finite, exact multiples of 0.5 only; no coordinate or radius is rounded. */
    public static Sphere of(double x, double y, double z, double radius) {
        return new Sphere(Point3.of(x, y, z), ExactGeometry.halfUnits(radius, "radius"));
    }

    public Point3 center() { return center; }
    public long radius2() { return radius2; }
    @Override public IntBox bounds() { return bounds; }

    @Override
    public Coverage classify(IntBox box) {
        Objects.requireNonNull(box, "box");
        if (box.isEmpty() || !bounds.intersects(box)) return Coverage.OUTSIDE;
        long minimum = ExactGeometry.square(ExactGeometry.nearestDistance2(center.x2(), box.minX(), box.maxX()))
                + ExactGeometry.square(ExactGeometry.nearestDistance2(center.y2(), box.minY(), box.maxY()))
                + ExactGeometry.square(ExactGeometry.nearestDistance2(center.z2(), box.minZ(), box.maxZ()));
        if (minimum > radiusSquared) return Coverage.OUTSIDE;
        long maximum = ExactGeometry.square(ExactGeometry.farthestDistance2(center.x2(), box.minX(), box.maxX()))
                + ExactGeometry.square(ExactGeometry.farthestDistance2(center.y2(), box.minY(), box.maxY()))
                + ExactGeometry.square(ExactGeometry.farthestDistance2(center.z2(), box.minZ(), box.maxZ()));
        return maximum <= radiusSquared ? Coverage.INSIDE : Coverage.PARTIAL;
    }

    @Override
    public boolean contains(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) return false;
        return ExactGeometry.square(2L * x + 1 - center.x2())
                + ExactGeometry.square(2L * y + 1 - center.y2())
                + ExactGeometry.square(2L * z + 1 - center.z2()) <= radiusSquared;
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
        for (int z = box.minZ(); z < box.maxZ(); z++) {
            long dzSquared = ExactGeometry.square(2L * z + 1 - center.z2());
            for (int x = box.minX(); x < box.maxX(); x++) {
                long remaining = radiusSquared - dzSquared - ExactGeometry.square(2L * x + 1 - center.x2());
                if (remaining < 0) continue;
                long reach2 = ExactGeometry.floorSqrt(remaining);
                int min = Math.max(box.minY(), ExactGeometry.lowerBound(center.y2(), reach2));
                int max = Math.min(box.maxY(), ExactGeometry.upperBound(center.y2(), reach2));
                if (min < max) sink.accept(x, z, min, max);
            }
        }
    }
}
