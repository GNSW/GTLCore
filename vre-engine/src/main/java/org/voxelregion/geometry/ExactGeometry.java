/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion.geometry;

/** Integer arithmetic shared by the built-in shapes; not a public API. */
final class ExactGeometry {
    static final long MAX_RADIUS_2 = 120_000_000L;

    private ExactGeometry() { }

    static long halfUnits(double value, String name) {
        double scaled = value * 2.0;
        if (!Double.isFinite(value) || !Double.isFinite(scaled)
                || scaled != Math.rint(scaled) || scaled < -MAX_RADIUS_2 || scaled > MAX_RADIUS_2) {
            throw new IllegalArgumentException(name + " must be a finite half-block value within supported limits");
        }
        return (long) scaled;
    }

    static void checkRadius(long radius2) {
        if (radius2 < 0 || radius2 > MAX_RADIUS_2) {
            throw new IllegalArgumentException("Doubled radius must be in [0, " + MAX_RADIUS_2 + "]");
        }
    }

    static int lowerBound(long center2, long reach2) {
        return clamp(Math.floorDiv(center2 - reach2, 2));
    }

    static int upperBound(long center2, long reach2) {
        return clamp(Math.floorDiv(center2 + reach2 + 1, 2));
    }

    private static int clamp(long value) {
        return (int) Math.max(IntBox.WORLD_MIN, Math.min(IntBox.WORLD_MAX, value));
    }

    static long square(long value) {
        return value * value;
    }

    static long nearestDistance2(long center2, int min, int max) {
        long lo = 2L * min + 1;
        long hi = 2L * max - 1;
        if (center2 < lo) return lo - center2;
        if (center2 > hi) return center2 - hi;
        // Voxel centers are odd half-coordinates, including at negative positions.
        return (center2 & 1L) == 0 ? 1L : 0L;
    }

    static long farthestDistance2(long center2, int min, int max) {
        return Math.max(Math.abs(2L * min + 1 - center2), Math.abs(2L * max - 1 - center2));
    }

    static long floorSqrt(long value) {
        if (value < 0) throw new IllegalArgumentException("Negative radicand");
        if (value == 0) return 0;
        // sqrt supplies only a candidate. The divisions establish the exact
        // floor, even if the floating-point candidate rounded across a boundary.
        long result = (long) Math.sqrt(value);
        while (result > value / result) result--;
        while (result + 1 <= value / (result + 1)) result++;
        return result;
    }

    static void emitBox(IntBox box, RangeSink sink) {
        if (box.isEmpty()) return;
        for (int z = box.minZ(); z < box.maxZ(); z++) {
            for (int x = box.minX(); x < box.maxX(); x++) {
                sink.accept(x, z, box.minY(), box.maxY());
            }
        }
    }
}
