/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.propagation;

import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;

/** Half-open physical blocker boxes, exact half-coordinate rays and extrusion intervals. */
final class ExactShadowMath {
    private ExactShadowMath() { }

    static boolean containsCenter(IntBox box, Point3 point) {
        return 2L * box.minX() <= point.x2() && point.x2() < 2L * box.maxX()
                && 2L * box.minY() <= point.y2() && point.y2() < 2L * box.maxY()
                && 2L * box.minZ() <= point.z2() && point.z2() < 2L * box.maxZ();
    }

    /** Segment parameter t is in [0,1]; each box's maximum face is excluded. */
    static boolean rayHits(Point3 center, long px, long py, long pz, IntBox blocker) {
        long lowN = 0, lowD = 1, highN = 1, highD = 1;
        boolean lowClosed = true, highClosed = true;
        for (int axis = 0; axis < 3; axis++) {
            long source = coordinate(center, axis);
            long target = axis == 0 ? px : axis == 1 ? py : pz;
            long delta = target - source;
            long min = 2L * min(blocker, axis), max = 2L * max(blocker, axis);
            if (delta == 0) {
                if (source < min || source >= max) return false;
                continue;
            }
            long denominator = Math.abs(delta);
            long a = delta > 0 ? min - source : source - max;
            long b = delta > 0 ? max - source : source - min;
            boolean aClosed = delta > 0, bClosed = delta < 0;
            int lowComparison = compare(a, denominator, lowN, lowD);
            if (lowComparison > 0) {
                lowN = a;
                lowD = denominator;
                lowClosed = aClosed;
            } else if (lowComparison == 0) lowClosed &= aClosed;
            int highComparison = compare(b, denominator, highN, highD);
            if (highComparison < 0) {
                highN = b;
                highD = denominator;
                highClosed = bClosed;
            } else if (highComparison == 0) highClosed &= bClosed;
            int order = compare(lowN, lowD, highN, highD);
            if (order > 0 || (order == 0 && !(lowClosed && highClosed))) return false;
        }
        return true;
    }

    /**
     * Tests whether the closed target center-box intersects B+t*direction for
     * any real t>=0. Direction components are -1, 0 or 1, so all bounds here
     * are integers; open/closed endpoints retain half-open blocker semantics.
     */
    static boolean extrusionHits(IntBox blocker, int dx, int dy, int dz,
            long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {
        long lower = 0, upper = Long.MAX_VALUE;
        boolean lowerClosed = true, upperClosed = true;
        for (int axis = 0; axis < 3; axis++) {
            int direction = axis == 0 ? dx : axis == 1 ? dy : dz;
            long targetMin = axis == 0 ? minX : axis == 1 ? minY : minZ;
            long targetMax = axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
            long blockerMin = 2L * min(blocker, axis), blockerMax = 2L * max(blocker, axis);
            if (direction == 0) {
                if (targetMax < blockerMin || targetMin >= blockerMax) return false;
                continue;
            }
            long lo = direction > 0 ? targetMin - blockerMax : blockerMin - targetMax;
            long hi = direction > 0 ? targetMax - blockerMin : blockerMax - targetMin;
            boolean loClosed = direction < 0, hiClosed = direction > 0;
            if (lo > lower) {
                lower = lo;
                lowerClosed = loClosed;
            } else if (lo == lower) lowerClosed &= loClosed;
            if (hi < upper) {
                upper = hi;
                upperClosed = hiClosed;
            } else if (hi == upper) upperClosed &= hiClosed;
            if (lower > upper || (lower == upper && !(lowerClosed && upperClosed))) return false;
        }
        return true;
    }

    /** All operands are bounded by 120M, making signed-long cross products safe. */
    private static int compare(long an, long ad, long bn, long bd) {
        return Long.compare(an * bd, bn * ad);
    }

    static long coordinate(Point3 point, int axis) {
        return axis == 0 ? point.x2() : axis == 1 ? point.y2() : point.z2();
    }

    static int min(IntBox box, int axis) { return axis == 0 ? box.minX() : axis == 1 ? box.minY() : box.minZ(); }
    static int max(IntBox box, int axis) { return axis == 0 ? box.maxX() : axis == 1 ? box.maxY() : box.maxZ(); }
}
