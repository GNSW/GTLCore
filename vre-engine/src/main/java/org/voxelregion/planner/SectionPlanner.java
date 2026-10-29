/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.planner;

import java.util.Objects;
import java.util.HashMap;
import java.util.Map;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Volume;
import org.voxelregion.geometry.VoxelMask;
import org.voxelregion.propagation.FrozenOcclusion;

/** Pure planning over immutable inputs; claims must be checked separately per chunk. */
public final class SectionPlanner {
    public SectionPlan plan(Volume volume, SectionSnapshot section,
                            ProtectionOracle protection, FrozenOcclusion occlusion) {
        return plan(volume, section, protection, occlusion, ClearOptions.DEFAULT);
    }

    public SectionPlan plan(Volume volume, SectionSnapshot section,
                            ProtectionOracle protection, FrozenOcclusion occlusion, ClearOptions options) {
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(protection, "protection");
        Objects.requireNonNull(occlusion, "occlusion");
        Objects.requireNonNull(options, "options");
        // The default path never reads fluid metadata. Preservation requires a
        // complete snapshot, even when an early geometric/air skip is possible.
        boolean retainFluids = !options.clearFluids() && section.hasFluids();
        long revision = protection.revision();
        if (section.isAllAir()) return result(section, revision, WriteKind.NOOP, VoxelMask.EMPTY);
        IntBox bounds = section.key().bounds();
        Coverage coverage = volume.classify(bounds);
        if (coverage == Coverage.OUTSIDE) return result(section, revision, WriteKind.NOOP, VoxelMask.EMPTY);
        VoxelMask shadow = occlusion.shadowMask(section.key());
        if (shadow.isFull()) return result(section, revision, WriteKind.NOOP, VoxelMask.EMPTY);

        ProtectionOracle.StaticSummary summary = Objects.requireNonNull(protection.staticSummary(section), "summary");
        boolean contextual = protection.requiresContextualChecks();
        if (!contextual && summary == ProtectionOracle.StaticSummary.NONE_DESTROYABLE) {
            return checkedResult(section, protection, revision, WriteKind.NOOP, VoxelMask.EMPTY);
        }
        if (retainFluids) {
            return retainingFluids(volume, section, protection, shadow, bounds, revision,
                    !contextual && summary == ProtectionOracle.StaticSummary.ALL_DESTROYABLE);
        }
        if (coverage == Coverage.INSIDE && shadow.isEmpty() && !contextual
                && summary == ProtectionOracle.StaticSummary.ALL_DESTROYABLE) {
            return checkedResult(section, protection, revision, WriteKind.WHOLE_AIR, VoxelMask.ALL);
        }

        if (!contextual && summary == ProtectionOracle.StaticSummary.ALL_DESTROYABLE) {
            VoxelMask.Builder geometry = VoxelMask.builder();
            volume.emitRanges(bounds, (x, z, minY, maxY) -> geometry.setRange(
                    x - bounds.minX(), z - bounds.minZ(), minY - bounds.minY(), maxY - bounds.minY()));
            VoxelMask mask = geometry.build().andNot(shadow);
            return checkedResult(section, protection, revision,
                    mask.isEmpty() ? WriteKind.NOOP : WriteKind.MASKED_CLEAR, mask);
        }

        VoxelMask.Builder clear = VoxelMask.builder();
        volume.emitRanges(bounds, (x, z, minY, maxY) -> {
            int localX = x - bounds.minX(), localZ = z - bounds.minZ();
            for (int y = minY; y < maxY; y++) {
                int localY = y - bounds.minY();
                if (shadow.contains(localX, localY, localZ)) continue;
                int state = section.state(localX, localY, localZ);
                if (state != section.airState() && protection.mayDestroy(state, x, y, z)) {
                    clear.set(localX, localY, localZ);
                }
            }
        });
        VoxelMask mask = clear.build();
        return checkedResult(section, protection, revision,
                mask.isEmpty() ? WriteKind.NOOP : WriteKind.MASKED_CLEAR, mask);
    }

    private static SectionPlan retainingFluids(Volume volume, SectionSnapshot section,
                                               ProtectionOracle protection, VoxelMask shadow, IntBox bounds,
                                               long revision, boolean staticallyDestroyable) {
        VoxelMask.Builder clear = VoxelMask.builder();
        Map<Integer, VoxelMask.Builder> retained = new HashMap<>();
        volume.emitRanges(bounds, (x, z, minY, maxY) -> {
            int localX = x - bounds.minX(), localZ = z - bounds.minZ();
            for (int y = minY; y < maxY; y++) {
                int localY = y - bounds.minY();
                if (shadow.contains(localX, localY, localZ)) continue;
                int state = section.state(localX, localY, localZ);
                if (state == section.airState()) continue;
                int fluid = section.fluidState(localX, localY, localZ);
                if (fluid == state) continue; // Preserve pure fluid without issuing an identical write.
                if (!staticallyDestroyable && !protection.mayDestroy(state, x, y, z)) continue;
                if (fluid == section.airState()) clear.set(localX, localY, localZ);
                else retained.computeIfAbsent(fluid, ignored -> VoxelMask.builder()).set(localX, localY, localZ);
            }
        });
        VoxelMask clearMask = clear.build();
        Map<Integer, VoxelMask> fluidWrites = new HashMap<>();
        retained.forEach((state, builder) -> fluidWrites.put(state, builder.build()));
        if (revision != protection.revision()) throw new IllegalStateException("Protection policy changed while planning");
        WriteKind kind = !fluidWrites.isEmpty() ? WriteKind.MASKED_REPLACE
                : clearMask.isEmpty() ? WriteKind.NOOP : WriteKind.MASKED_CLEAR;
        return new SectionPlan(section.key(), section.revision(), revision, kind, clearMask, fluidWrites);
    }

    private static SectionPlan checkedResult(SectionSnapshot section, ProtectionOracle protection,
                                             long revision, WriteKind kind, VoxelMask mask) {
        if (revision != protection.revision()) throw new IllegalStateException("Protection policy changed while planning");
        return result(section, revision, kind, mask);
    }

    private static SectionPlan result(SectionSnapshot section, long revision, WriteKind kind, VoxelMask mask) {
        return new SectionPlan(section.key(), section.revision(), revision, kind, mask);
    }
}
