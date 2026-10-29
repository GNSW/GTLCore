/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.planner;

import java.util.Objects;
import java.util.Map;
import org.voxelregion.geometry.VoxelMask;
import org.voxelregion.spatial.SectionKey;

/**
 * Immutable intent. A host must revalidate chunk permissions, section revision,
 * and protection revision immediately before any mutation. Mask cardinality is
 * selected cells, not a count of destroyed blocks. clearMask writes only air;
 * retainedFluidWrites maps host fluid state IDs to disjoint replacement masks.
 */
public record SectionPlan(SectionKey key, long sectionRevision, long protectionRevision,
                          WriteKind kind, VoxelMask clearMask, Map<Integer, VoxelMask> retainedFluidWrites) {
    /** Existing air-only callers retain their original construction contract. */
    public SectionPlan(SectionKey key, long sectionRevision, long protectionRevision,
                       WriteKind kind, VoxelMask clearMask) {
        this(key, sectionRevision, protectionRevision, kind, clearMask, Map.of());
    }

    public SectionPlan {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(clearMask, "clearMask");
        retainedFluidWrites = Map.copyOf(Objects.requireNonNull(retainedFluidWrites, "retainedFluidWrites"));
        if (kind == WriteKind.MASKED_REPLACE ? retainedFluidWrites.isEmpty() : !retainedFluidWrites.isEmpty()) {
            throw new IllegalArgumentException("Only MASKED_REPLACE must contain fluid replacement masks");
        }
        if (!retainedFluidWrites.isEmpty()) {
            VoxelMask selected = clearMask;
            for (VoxelMask fluidMask : retainedFluidWrites.values()) {
                if (fluidMask.isEmpty()) throw new IllegalArgumentException("Fluid replacement masks cannot be empty");
                VoxelMask union = selected.union(fluidMask);
                if (union.cardinality() != selected.cardinality() + fluidMask.cardinality()) {
                    throw new IllegalArgumentException("Air and fluid replacement masks must be disjoint");
                }
                selected = union;
            }
        }
        if (kind == WriteKind.NOOP && !clearMask.isEmpty()) {
            throw new IllegalArgumentException("NOOP must have an empty mask");
        }
        if (kind == WriteKind.WHOLE_AIR && !clearMask.isFull()) {
            throw new IllegalArgumentException("WHOLE_AIR must select the whole section");
        }
        if (kind == WriteKind.MASKED_CLEAR && clearMask.isEmpty()) {
            throw new IllegalArgumentException("An empty clear must be NOOP");
        }
    }

    public long estimatedBytes() {
        long bytes = 96L + clearMask.estimatedBytes();
        for (VoxelMask fluidMask : retainedFluidWrites.values()) bytes += 64L + fluidMask.estimatedBytes();
        return bytes;
    }
}
