/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion.geometry;

/**
 * An immutable voxel selection. Geometry samples block centers (x+0.5,
 * y+0.5,z+0.5), consistently in every operation. Coordinates outside the
 * supported world are never selected. Implementations must be thread safe.
 */
public interface Volume {
    /** A world-clipped half-open box containing every selected voxel. */
    IntBox bounds();

    /** Empty boxes are OUTSIDE; INSIDE means every voxel center is selected. */
    Coverage classify(IntBox box);

    boolean contains(int x, int y, int z);

    /**
     * Emits exactly the selection intersected with clip, without duplicate
     * voxels. All coordinates are world coordinates. No empty range is emitted.
     */
    void emitRanges(IntBox clip, RangeSink sink);
}
