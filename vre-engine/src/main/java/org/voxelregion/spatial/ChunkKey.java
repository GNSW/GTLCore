/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion.spatial;

import org.voxelregion.geometry.IntBox;

/** A 16x16 horizontal chunk, with height supplied explicitly by the caller. */
public record ChunkKey(int x, int z) {
    public ChunkKey {
        SectionKey.checkIndex(x);
        SectionKey.checkIndex(z);
    }

    public static ChunkKey fromBlock(int x, int z) {
        SectionKey.checkBlock(x);
        SectionKey.checkBlock(z);
        return new ChunkKey(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
    }

    public IntBox bounds(int minY, int maxY) {
        return new IntBox(x * 16, minY, z * 16, x * 16 + 16, maxY, z * 16 + 16);
    }

    public SectionKey section(int sectionY) { return new SectionKey(x, sectionY, z); }
}
