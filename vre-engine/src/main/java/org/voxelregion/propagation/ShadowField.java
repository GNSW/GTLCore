/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.propagation;

import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.VoxelMask;
import org.voxelregion.spatial.SectionKey;

/** Internal immutable implementations behind FrozenOcclusion's stable facade. */
interface ShadowField {
    Coverage classify(IntBox box);
    VoxelMask shadowMask(SectionKey section);
    int sourceBlockerCount();
    int indexedPrimitiveCount();
    long estimatedBytes();
}
