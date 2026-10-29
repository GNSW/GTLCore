/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.planner;

import org.voxelregion.spatial.ChunkKey;

/** Host-authorized actor identity; bypass authorization is never inferred by the core. */
@FunctionalInterface
public interface ClaimOracle {
    ChunkPermission query(String dimension, ChunkKey chunk, String actor);
}
