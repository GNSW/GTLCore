/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion.geometry;

import java.util.Objects;

/**
 * Vertical cylinder with a closed circular X/Z cross-section and half-open
 * integer Y interval. The Point3 Y coordinate is validated but does not affect
 * the cylinder. Center and radius use exact half-block units as in Sphere.
 */
public final class Cylinder implements Volume {
    private final Point3 center;
    private final long radius2;
    private final long radiusSquared;
    private final IntBox bounds;

    public Cylinder(Point3 center, long radius2, int minY, int maxY) {
        this.center = Objects.requireNonNull(center, "center");
        ExactGeometry.checkRadius(radius2);
        this.radius2 = radius2;
        this.radiusSquared = radius2 * radius2;
        this.bounds = new IntBox(ExactGeometry.lowerBound(center.x2(), radius2), minY,
                ExactGeometry.lowerBound(center.z2(), radius2), ExactGeometry.upperBound(center.x2(), radius2),
                maxY, ExactGeometry.upperBound(center.z2(), radius2));
    }

    /** Accepts finite exact half-block X/Z coordinates and radius, without rounding. */
    public static Cylinder of(double x, double z, double radius, int minY, int maxY) {
        return new Cylinder(Point3.of(x, 0, z), ExactGeometry.halfUnits(radius, "radius"), minY, maxY);
    }

    public Point3 center() { return center; }
    public long radius2() { return radius2; }
    public int minY() { return bounds.minY(); }
    public int maxY() { return bounds.maxY(); }
    @Override public IntBox bounds() { return bounds; }

    @Override
    public Coverage classify(IntBox box) {
        Objects.requireNonNull(box, "box");
        if (box.isEmpty() || !bounds.intersects(box)) return Coverage.OUTSIDE;
        long minimum = ExactGeometry.square(ExactGeometry.nearestDistance2(center.x2(), box.minX(), box.maxX()))
                + ExactGeometry.square(ExactGeometry.nearestDistance2(center.z2(), box.minZ(), box.maxZ()));
        if (minimum > radiusSquared) return Coverage.OUTSIDE;
        long maximum = ExactGeometry.square(ExactGeometry.farthestDistance2(center.x2(), box.minX(), box.maxX()))
                + ExactGeometry.square(ExactGeometry.farthestDistance2(center.z2(), box.minZ(), box.maxZ()));
        return box.minY() >= minY() && box.maxY() <= maxY() && maximum <= radiusSquared
                ? Coverage.INSIDE : Coverage.PARTIAL;
    }

    @Override
    public boolean contains(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) return false;
        return ExactGeometry.square(2L * x + 1 - center.x2())
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
                if (dzSquared + ExactGeometry.square(2L * x + 1 - center.x2()) <= radiusSquared) {
                    sink.accept(x, z, box.minY(), box.maxY());
                }
            }
        }
    }
}
