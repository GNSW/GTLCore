/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.planner;

/** Commit must recheck this revision or query again. Neither flag implies occlusion. */
public record ChunkPermission(long revision, boolean mayMutate, boolean mayDamageEntities) { }
