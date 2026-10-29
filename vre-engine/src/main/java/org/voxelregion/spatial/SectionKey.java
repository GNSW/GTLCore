/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion.spatial;

import org.voxelregion.geometry.IntBox;

/** A 16-cubed section. Each coordinate is a section index, not a block position. */
public record SectionKey(int x, int y, int z) {
    public SectionKey {
        checkIndex(x);
        checkIndex(y);
        checkIndex(z);
    }

    static void checkIndex(int coordinate) {
        if (coordinate < IntBox.WORLD_MIN / 16 || coordinate >= IntBox.WORLD_MAX / 16) {
            throw new IllegalArgumentException("Section/chunk coordinate is outside the world: " + coordinate);
        }
    }

    static void checkBlock(int coordinate) {
        if (coordinate < IntBox.WORLD_MIN || coordinate >= IntBox.WORLD_MAX) {
            throw new IllegalArgumentException("Block coordinate is outside the world: " + coordinate);
        }
    }

    public static SectionKey fromBlock(int x, int y, int z) {
        checkBlock(x);
        checkBlock(y);
        checkBlock(z);
        return new SectionKey(Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16));
    }

    public IntBox bounds() {
        return new IntBox(x * 16, y * 16, z * 16, x * 16 + 16, y * 16 + 16, z * 16 + 16);
    }

    public ChunkKey chunk() { return new ChunkKey(x, z); }
}
