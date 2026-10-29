/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion.geometry;

import java.util.Objects;

/**
 * Half-open voxel coordinates: minima are included and maxima are excluded.
 * Every endpoint must be in [-30,000,000, 30,000,000], including Y.
 * A zero extent on any axis represents an empty box. Reversed endpoints
 * are rejected. Valid voxel coordinates stop at 29,999,999.
 */
public record IntBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public static final int WORLD_MIN = -30_000_000;
    public static final int WORLD_MAX = 30_000_000;

    public IntBox {
        checkAxis(minX, maxX);
        checkAxis(minY, maxY);
        checkAxis(minZ, maxZ);
    }

    private static void checkAxis(int min, int max) {
        if (min < WORLD_MIN || max > WORLD_MAX || min > max) {
            throw new IllegalArgumentException("Invalid half-open world interval: [" + min + ", " + max + ")");
        }
    }

    public boolean isEmpty() {
        return minX == maxX || minY == maxY || minZ == maxZ;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
    }

    /** Empty boxes are subsets of every box. */
    public boolean contains(IntBox other) {
        Objects.requireNonNull(other, "other");
        return other.isEmpty() || (!isEmpty()
                && minX <= other.minX && maxX >= other.maxX
                && minY <= other.minY && maxY >= other.maxY
                && minZ <= other.minZ && maxZ >= other.maxZ);
    }

    public boolean intersects(IntBox other) {
        Objects.requireNonNull(other, "other");
        return !isEmpty() && !other.isEmpty()
                && minX < other.maxX && maxX > other.minX
                && minY < other.maxY && maxY > other.minY
                && minZ < other.maxZ && maxZ > other.minZ;
    }

    public IntBox intersection(IntBox other) {
        Objects.requireNonNull(other, "other");
        int x = Math.max(minX, other.minX);
        int y = Math.max(minY, other.minY);
        int z = Math.max(minZ, other.minZ);
        return new IntBox(x, y, z, Math.max(x, Math.min(maxX, other.maxX)),
                Math.max(y, Math.min(maxY, other.maxY)), Math.max(z, Math.min(maxZ, other.maxZ)));
    }
}
