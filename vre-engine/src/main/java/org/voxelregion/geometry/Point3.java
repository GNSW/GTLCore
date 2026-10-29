/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion.geometry;

/**
 * Exact coordinates in half-block units: (1, 1, 1) means (0.5, 0.5, 0.5).
 * Each doubled coordinate is restricted to [-60,000,000, 60,000,000].
 */
public record Point3(long x2, long y2, long z2) {
    public static final long MAX_ABS_COORDINATE_2 = 60_000_000L;

    public Point3 {
        checkCoordinate(x2);
        checkCoordinate(y2);
        checkCoordinate(z2);
    }

    private static void checkCoordinate(long value) {
        if (value < -MAX_ABS_COORDINATE_2 || value > MAX_ABS_COORDINATE_2) {
            throw new IllegalArgumentException("Doubled coordinate is outside the world: " + value);
        }
    }

    /**
     * Accepts only finite coordinates that are exact multiples of 0.5.
     * Arbitrary floating-point positions are rejected, never rounded.
     */
    public static Point3 of(double x, double y, double z) {
        return new Point3(ExactGeometry.halfUnits(x, "x"),
                ExactGeometry.halfUnits(y, "y"), ExactGeometry.halfUnits(z, "z"));
    }
}
