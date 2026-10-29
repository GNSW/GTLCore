/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.voxelregion.forge.runtime.ChunkMutationTracking;

/**
 * Read-only, bounded proof that pending native shape/fluid work will be erased by a terrain commit.
 * It never completes a native generation future or edits a post-processing list during preparation.
 * Unknown terrain, upgrades and both live/pending block entities keep the native lifecycle.
 */
public final class PostProcessingClearance {
    private static final int MAX_ENTRIES = 4096;
    private final ServerLevel level;
    private final LevelChunk chunk;
    private final long revision;
    private final short[][] entries;
    private boolean consumed;

    private PostProcessingClearance(ServerLevel level, LevelChunk chunk, long revision, short[][] entries) {
        this.level = level; this.chunk = chunk; this.revision = revision; this.entries = entries;
    }

    /** The caller supplies exact geometry, world-border, claim and removal-policy permission. */
    public static PostProcessingClearance capture(ServerLevel level, LevelChunk chunk,
            BiPredicate<BlockPos, BlockState> mayRemove) {
        if (!owner(level, chunk) || !noUpgrade(chunk) || !chunk.getBlockEntitiesPos().isEmpty()) return null;
        long revision = ChunkMutationTracking.revision(chunk);
        if (revision < 0 || revision == Long.MAX_VALUE) return null;
        var lists = chunk.getPostProcessing();
        if (lists.length != chunk.getSections().length) return null;
        int count = 0;
        for (var list : lists) if (list != null) {
            if (list.size() > MAX_ENTRIES - count) return null;
            count += list.size();
        }
        if (count == 0) return null;
        // The eventual transaction rejects every native remainder, not merely work at
        // the post-list positions. Reject unsupported palettes before read-ahead elects
        // to defer native promotion; otherwise late fallback serializes preparation,
        // neighbor generation and the same native lifecycle on the commit cursor.
        // This is a conservative palette check, not a block-by-block scan or permission
        // grant. The actual selection and every pending entry are still rechecked below.
        for (int section = 0; section < lists.length; section++) {
            var origin = new BlockPos(chunk.getPos().getMinBlockX(),
                    chunk.getSectionYFromSectionIndex(section) << 4, chunk.getPos().getMinBlockZ());
            if (chunk.getSection(section).getStates().maybeHas(state ->
                    !vanillaAir(state) && !TerrainRemovalProof.supports(state, level, origin))) return null;
        }
        short[][] entries = new short[lists.length][];
        for (int section = 0; section < lists.length; section++) {
            if (lists[section] == null) continue;
            entries[section] = lists[section].toShortArray();
            for (short packed : entries[section]) {
                // Native packed offsets are x | y<<4 | z<<8, unlike palette bit masks.
                if ((packed & 0xF000) != 0) return null;
                BlockPos pos = ProtoChunk.unpackOffsetCoordinates(packed, chunk.getSectionYFromSectionIndex(section), chunk.getPos());
                BlockState state = chunk.getBlockState(pos);
                if ((!vanillaAir(state) && !TerrainRemovalProof.supports(state, level, pos)) || !mayRemove.test(pos, state)) return null;
            }
        }
        var proof = new PostProcessingClearance(level, chunk, revision, entries);
        return proof.currentRevision(level, chunk) && proof.matchesMetadata() ? proof : null;
    }

    /** Cheap preparation-cache guard only; the complete metadata is rechecked before the first write. */
    public boolean currentRevision(ServerLevel level, LevelChunk chunk) {
        return this.level == level && this.chunk == chunk && !consumed && owner(level, chunk)
                && ChunkMutationTracking.revision(chunk) == revision
                && noUpgrade(chunk) && chunk.getBlockEntitiesPos().isEmpty();
    }

    public boolean matchesSection(ServerLevel level, LevelChunk chunk, int section) {
        return this.level == level && this.chunk == chunk && !consumed && owner(level, chunk)
                && noUpgrade(chunk) && chunk.getBlockEntitiesPos().isEmpty()
                && chunk.getPostProcessing().length == entries.length && section >= 0 && section < entries.length
                && sectionMatches(section);
    }

    /** Does not compare the mutation epoch: trusted commit hooks advance it before the final guard. */
    public boolean matchesMetadata() {
        if (consumed || !owner(level, chunk) || !noUpgrade(chunk)
                || !chunk.getBlockEntitiesPos().isEmpty() || chunk.getPostProcessing().length != entries.length) return false;
        for (int section = 0; section < entries.length; section++) if (!sectionMatches(section)) return false;
        return true;
    }

    private boolean sectionMatches(int section) {
        var actual = chunk.getPostProcessing()[section];
        short[] expected = entries[section];
        if (expected == null) return actual == null;
        if (actual == null || actual.size() != expected.length) return false;
        for (int i = 0; i < expected.length; i++) if (actual.getShort(i) != expected[i]) return false;
        return true;
    }

    /** Every entry must resolve to exact vanilla AIR in the detached plans, with no native remainder. */
    public boolean plansEraseAll(List<PaletteSectionClearBackend.Prepared> plans) {
        if (!currentRevision(level, chunk) || !matchesMetadata()) return false;
        var bySection = new PaletteSectionClearBackend.Prepared[entries.length];
        for (var plan : plans) {
            if (plan.nativeRemainderCount() != 0 || plan.sectionIndex() < 0 || plan.sectionIndex() >= entries.length
                    || bySection[plan.sectionIndex()] != null) return false;
            bySection[plan.sectionIndex()] = plan;
        }
        for (int section = 0; section < entries.length; section++) if (entries[section] != null) {
            for (short packed : entries[section]) {
                int x = packed & 15, y = (packed >>> 4) & 15, z = (packed >>> 8) & 15;
                var plan = bySection[section];
                if (plan == null ? !vanillaAir(chunk.getSection(section).getBlockState(x, y, z))
                        : !plan.preparesVanillaAir(this, x, y, z)) return false;
            }
        }
        return true;
    }

    /**
     * All-or-nothing metadata consumption after successful terrain writes. False means writes may
     * already exist: the caller must stop for recovery/native repair, never retry as an unapplied plan.
     */
    public boolean consumeAfterCommit() {
        return consumeAfterCommit(() -> true);
    }

    /** Final permission is evaluated even for an all-AIR, metadata-only transaction. */
    public boolean consumeAfterCommit(BooleanSupplier finalPermission) {
        if (!finalPermission.getAsBoolean()) return false;
        if (!matchesMetadata()) return false;
        for (int section = 0; section < entries.length; section++) if (entries[section] != null)
            for (short packed : entries[section])
                if (!vanillaAir(chunk.getSection(section).getBlockState(packed & 15, (packed >>> 4) & 15, (packed >>> 8) & 15))) return false;
        for (int section = 0; section < entries.length; section++)
            if (entries[section] != null && entries[section].length != 0) chunk.getPostProcessing()[section].clear();
        consumed = true;
        chunk.setUnsaved(true);
        return true;
    }

    public long estimatedBytes() {
        long bytes = 128L + entries.length * 24L;
        for (short[] section : entries) if (section != null) bytes += section.length * 2L;
        return bytes;
    }

    private static boolean owner(ServerLevel level, LevelChunk chunk) {
        return level.getServer().isSameThread() && !level.captureBlockSnapshots && chunk.getLevel() == level
                && level.getChunkSource().getChunkNow(chunk.getPos().x, chunk.getPos().z) == chunk;
    }
    private static boolean noUpgrade(LevelChunk chunk) {
        // isEmpty() ignores saved neighbor fluid/block ticks. The canonical EMPTY instance
        // is the narrow proof for ordinary new/current-version chunks; other data stays native.
        return chunk.getUpgradeData() == UpgradeData.EMPTY;
    }
    private static boolean vanillaAir(BlockState state) {
        return state == Blocks.AIR.defaultBlockState() || state == Blocks.CAVE_AIR.defaultBlockState()
                || state == Blocks.VOID_AIR.defaultBlockState();
    }
}
