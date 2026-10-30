/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Palette-proven replacement of audited terrain; protected/native-only cells can remain in place.
 * Whole-section proof costs O(palette), final validation O(sections), light checks O(exterior surface).
 * Selective/retained palettes use detached packed indices when their layout permits it;
 * other selections retain the per-voxel encoding fallback and complete native light checks.
 * Batches containing AIR or water additionally invalidate native sky-source/shadow interfaces.
 * A batch rebuilds chunk heightmaps and sky-source columns once and requests one chunk packet.
 * The caller owns geometric coverage, claim checks, budget reservation, receipts and chunk tickets.
 *
 * <p>This path does not issue native block callbacks for proven terrain. Relevant host mutation
 * hooks receive aggregate observers. Unknown states remain for native callback processing; an
 * unrelated installed mod does not veto all vanilla terrain. Physics policy is NONE.</p>
 */
public final class PaletteSectionClearBackend {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final Set<Heightmap.Types> HEIGHTMAPS = Collections.unmodifiableSet(EnumSet.of(
            Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR,
            Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));

    public enum PreparationPath { WHOLE_PALETTE, RETAINED_PALETTE, PACKED_REMAP, PACKED_SELECTION, VOXEL }
    private static final AtomicLongArray preparationCounts = new AtomicLongArray(PreparationPath.values().length);
    private static final AtomicLongArray preparationUnits = new AtomicLongArray(PreparationPath.values().length);
    private static final AtomicLongArray preparationRemainders = new AtomicLongArray(PreparationPath.values().length);

    /** Successful preparation attempts, including later invalidated/retried plans; never committed terrain counts. */
    public static Map<String, Long> preparationStatistics() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (var path : PreparationPath.values()) {
            result.put(path.name() + ".attemptedSections", preparationCounts.get(path.ordinal()));
            result.put(path.name() + ".planningUnits", preparationUnits.get(path.ordinal()));
            result.put(path.name() + ".nativeRemainderCells", preparationRemainders.get(path.ordinal()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static void recordPreparation(Prepared prepared) {
        int path = prepared.preparationPath.ordinal();
        preparationCounts.incrementAndGet(path);
        preparationUnits.addAndGet(path, prepared.planningWork);
        preparationRemainders.addAndGet(path, prepared.nativeRemainder.cardinality());
    }

    /** Full mutation tracking and aggregate observer support are required, not inferred from mod IDs. */
    public interface HostHooks {
        boolean supportsBulk(ServerLevel level);

        /** Nonnegative only when every relevant storage mutation is tracked, including native callbacks. */
        long mutationRevision(LevelChunk chunk);

        /** Must increment the tracked revision exactly once without editing terrain or calling observers. */
        void beforePaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY);

        void afterPaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY);

        /** The previous palette is an unordered proof set, not a per-position snapshot. */
        default void afterSectionCleared(ServerLevel level, LevelChunk chunk, Prepared previous) {}
    }

    /**
     * Called on the server thread after storage, metadata and observers. Its future must represent
     * actual lighting completion. A proof failure uses nativeFallback on the server thread; it must
     * never substitute an already-completed future for unqueued world light work.
     */
    @FunctionalInterface
    public interface LightingStrategy {
        CompletableFuture<Void> queue(ServerLevel level, LevelChunk chunk, List<Prepared> plans,
                                      Supplier<CompletableFuture<Void>> nativeFallback);
    }

    public enum Rejection {
        NONE, WRONG_THREAD, WRONG_CHUNK, INVALID_SECTION, HOST_UNSUPPORTED, TRACKING_UNAVAILABLE,
        CAPTURING_SNAPSHOTS, UNSUPPORTED_STATE, PROTECTED_STATE, NOT_FULL, BLOCK_ENTITY,
        POST_PROCESSING, STALE
    }

    public enum CommitResult { APPLIED_QUEUED, ALREADY_CONSUMED, STALE, PERMISSION_DENIED, HOST_UNSUPPORTED }

    public record Preparation(Prepared prepared, Rejection rejection) {
        public boolean accepted() { return prepared != null; }
    }

    public record CommitReport(CommitResult result, int changedBlocks, boolean needsChunkPacket,
                               CompletableFuture<Void> lightWork) {
        public boolean applied() { return result == CommitResult.APPLIED_QUEUED; }
    }

    public static final class Prepared {
        private final ServerLevel level;
        private final LevelChunk chunk;
        private final int sectionIndex;
        private final int sectionY;
        private final LevelChunkSection original;
        private final long revision;
        private final int changedBlocks;
        private final int keptBlocks;
        private final int originalNonAir;
        private final Set<BlockState> palette;
        private final Set<BlockState> retainedPolicyStates;
        private final BitSet changed;
        private final BitSet nativeRemainder;
        private final LevelChunkSection replacement;
        private final boolean fullLightChecks;
        private final boolean replaySkySources;
        private final Predicate<BlockState> mayClear;
        private final HostHooks hooks;
        private final PreparationPath preparationPath;
        private final int planningWork;
        private PostProcessingClearance postProcessing;
        private boolean consumed;
        private boolean observerActive;

        private Prepared(ServerLevel level, LevelChunk chunk, int sectionIndex, long revision, int changedBlocks,
                         Set<BlockState> palette, Predicate<BlockState> mayClear, HostHooks hooks) {
            this.level = level;
            this.chunk = chunk;
            this.sectionIndex = sectionIndex;
            this.sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
            this.original = chunk.getSection(sectionIndex);
            this.revision = revision;
            this.changedBlocks = changedBlocks;
            this.keptBlocks = 0;
            this.originalNonAir = ((NativeAccess.SectionStats) original).vre$accurateNonAirCount();
            this.palette = Collections.unmodifiableSet(palette);
            this.retainedPolicyStates = Set.of();
            this.changed = null;
            this.nativeRemainder = new BitSet();
            this.replacement = null;
            BlockPos minimum = minimumPosition();
            this.fullLightChecks = palette.stream().anyMatch(state -> !TerrainRemovalProof.boundaryLighting(state, level, minimum));
            // A full non-air water section is transmissive too; count==4096 is not a proof
            // of an initially black field. Preserve the interface repair for every water batch.
            this.replaySkySources = changedBlocks != 4096 || palette.stream().anyMatch(state -> state.is(Blocks.WATER));
            this.mayClear = mayClear;
            this.hooks = hooks;
            this.preparationPath = PreparationPath.WHOLE_PALETTE;
            this.planningWork = Math.max(1, palette.size());
            recordPreparation(this);
        }

        private Prepared(ServerLevel level, LevelChunk chunk, int sectionIndex, long revision,
                         Set<BlockState> palette, Set<BlockState> retainedPolicyStates, BitSet changed,
                         BitSet nativeRemainder, int keptBlocks, LevelChunkSection replacement,
                         Predicate<BlockState> mayClear, HostHooks hooks, PreparationPath preparationPath, int planningWork) {
            this.level = level; this.chunk = chunk; this.sectionIndex = sectionIndex;
            this.sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
            this.original = chunk.getSection(sectionIndex); this.revision = revision;
            this.changedBlocks = changed.cardinality();
            this.keptBlocks = keptBlocks;
            this.originalNonAir = ((NativeAccess.SectionStats) original).vre$accurateNonAirCount();
            this.palette = Collections.unmodifiableSet(palette);
            this.retainedPolicyStates = Collections.unmodifiableSet(retainedPolicyStates);
            this.changed = changed; this.nativeRemainder = nativeRemainder; this.replacement = replacement;
            // Nonuniform retained solids / geometry boundaries need the complete native check set.
            // They still use one section storage replacement, with no terrain setBlock callbacks.
            this.fullLightChecks = true; this.replaySkySources = true;
            this.mayClear = mayClear; this.hooks = hooks;
            this.preparationPath = preparationPath; this.planningWork = Math.max(1, planningWork);
            recordPreparation(this);
        }

        public int sectionIndex() { return sectionIndex; }
        public int sectionY() { return sectionY; }
        public int changedBlocks() { return changedBlocks; }
        /** Selected non-air states retained by the static protection policy; native remainders are excluded. */
        public int keptBlocks() { return keptBlocks; }
        public PreparationPath preparationPath() { return preparationPath; }
        /** Bulk work units: palette entries + packed/mask words, or 4096 for per-voxel encoding. */
        public int planningWork() { return planningWork; }
        public long revision() { return revision; }
        public Set<BlockState> palette() { return palette; }
        /** Selected unknown states retain native callbacks; callers process these after the safe batch. */
        public BitSet nativeRemainder() { return (BitSet) nativeRemainder.clone(); }
        public int nativeRemainderCount() { return nativeRemainder.cardinality(); }
        /** Restricted read-only preflight for the matching post-processing token, not a commit guarantee. */
        boolean preparesVanillaAir(PostProcessingClearance proof, int x, int y, int z) {
            return proof == postProcessing && !consumed && level.getServer().isSameThread()
                    && currentChunk(level, chunk) && hooks.mutationRevision(chunk) == revision
                    && chunk.getSection(sectionIndex) == original
                    && isVanillaAir(replacement == null ? AIR : replacement.getBlockState(x, y, z));
        }
        /** Actual changed mask, available only during the synchronous aggregate observer. */
        public boolean changed(int localX, int localY, int localZ) {
            checkObserver(localX, localY, localZ);
            return changedAt(localX, localY, localZ);
        }
        public BlockState replacementState(int localX, int localY, int localZ) {
            checkObserver(localX, localY, localZ);
            return replacement == null ? AIR : replacement.getBlockState(localX, localY, localZ);
        }
        /**
         * On-demand old state for audited watchers. Read synchronously inside afterSectionCleared
         * only; retaining this plan for later/off-thread queries is forbidden. No eager copy is made.
         */
        public BlockState previousState(int localX, int localY, int localZ) {
            checkObserver(localX, localY, localZ);
            return original.getBlockState(localX, localY, localZ);
        }
        private void checkObserver(int localX, int localY, int localZ) {
            if (!observerActive || !level.getServer().isSameThread())
                throw new IllegalStateException("Old section states may only be read synchronously during the commit observer");
            if ((localX | localY | localZ) < 0 || localX > 15 || localY > 15 || localZ > 15)
                throw new IndexOutOfBoundsException("Section-local coordinates must be 0..15");
        }
        private boolean changedAt(int x, int y, int z) {
            return changed == null ? !isVanillaAir(original.getBlockState(x, y, z)) : changed.get((y << 8) | (z << 4) | x);
        }
        public BlockPos minimumPosition() {
            return new BlockPos(chunk.getPos().getMinBlockX(), sectionY << 4, chunk.getPos().getMinBlockZ());
        }
    }

    public static Preparation prepare(ServerLevel level, LevelChunk chunk, int sectionIndex,
                                      Predicate<BlockState> mayClear, HostHooks hooks) {
        return prepare(level, chunk, sectionIndex, mayClear, hooks, null);
    }

    private static Preparation prepare(ServerLevel level, LevelChunk chunk, int sectionIndex,
                                      Predicate<BlockState> mayClear, HostHooks hooks, PostProcessingClearance postProcessing) {
        Objects.requireNonNull(level); Objects.requireNonNull(chunk);
        Objects.requireNonNull(mayClear); Objects.requireNonNull(hooks);
        if (!level.getServer().isSameThread()) return rejected(Rejection.WRONG_THREAD);
        if (!currentChunk(level, chunk)) return rejected(Rejection.WRONG_CHUNK);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) return rejected(Rejection.INVALID_SECTION);
        if (level.captureBlockSnapshots) return rejected(Rejection.CAPTURING_SNAPSHOTS);
        if (!hooks.supportsBulk(level)) return rejected(Rejection.HOST_UNSUPPORTED);
        long revision = hooks.mutationRevision(chunk);
        if (revision < 0 || revision == Long.MAX_VALUE) return rejected(Rejection.TRACKING_UNAVAILABLE);
        Rejection auxiliary = auxiliaryState(chunk, sectionIndex, postProcessing);
        if (auxiliary != Rejection.NONE) return rejected(auxiliary);
        LevelChunkSection original = chunk.getSection(sectionIndex);
        if (!(original instanceof NativeAccess.SectionStats stats)
                || stats.vre$accurateNonAirCount() < 1 || stats.vre$accurateNonAirCount() > 4096)
            return rejected(Rejection.NOT_FULL);
        int changedBlocks = stats.vre$accurateNonAirCount();
        BlockPos position = new BlockPos(chunk.getPos().getMinBlockX(), chunk.getSectionYFromSectionIndex(sectionIndex) << 4,
                chunk.getPos().getMinBlockZ());
        Set<BlockState> palette = Collections.newSetFromMap(new IdentityHashMap<>());
        Rejection[] rejection = { Rejection.UNSUPPORTED_STATE };
        boolean unsafe = original.maybeHas(state -> {
            // Only the three exact vanilla air states are equivalent empty terrain. Arbitrary
            // modded isAir() states may have callbacks and are not covered by this proof.
            if (isVanillaAir(state)) return false;
            if (!isSupportedState(state, level, position)) return true;
            if (!mayClear.test(state)) { rejection[0] = Rejection.PROTECTED_STATE; return true; }
            palette.add(state);
            return false;
        });
        if (unsafe || palette.isEmpty()) return rejected(rejection[0]);
        // maybeHas includes unused entries; unsupported ones conservatively reject the fast path.
        if (hooks.mutationRevision(chunk) != revision || chunk.getSection(sectionIndex) != original
                || stats.vre$accurateNonAirCount() != changedBlocks)
            return rejected(Rejection.STALE);
        return new Preparation(new Prepared(level, chunk, sectionIndex, revision, changedBlocks, palette, mayClear, hooks), Rejection.NONE);
    }

    public static CommitReport commit(Prepared prepared, BooleanSupplier finalPermission) {
        return commitBatch(List.of(prepared), finalPermission);
    }

    /**
     * Plans a geometric selection without writing the live chunk. Null selects all 4096 positions.
     * Proven terrain is replaced in a detached palette; policy-protected cells and all native-only
     * states remain intact. The latter are exposed as a separate mask, never silently discarded.
     * Caller may spread preparation across ticks, then commit all sections and native remainders
     * together. Any intervening live mutation invalidates the entire prepared chunk revision.
     */
    public static Preparation prepareSelected(ServerLevel level, LevelChunk chunk, int sectionIndex, BitSet selected,
                                              Predicate<BlockState> mayClear, HostHooks hooks) {
        return prepareSelected(level, chunk, sectionIndex, selected, mayClear, hooks, null);
    }

    /** Explicit opt-in: preparation retains every pending native list until the caller's verified commit. */
    public static Preparation prepareSelected(ServerLevel level, LevelChunk chunk, int sectionIndex, BitSet selected,
                                              Predicate<BlockState> mayClear, HostHooks hooks, PostProcessingClearance postProcessing) {
        if (postProcessing != null && !postProcessing.currentRevision(level, chunk)) return rejected(Rejection.STALE);
        Preparation result = prepareSelectedInternal(level, chunk, sectionIndex, selected, mayClear, hooks, postProcessing);
        if (result.accepted()) result.prepared.postProcessing = postProcessing;
        return result;
    }

    private static Preparation prepareSelectedInternal(ServerLevel level, LevelChunk chunk, int sectionIndex, BitSet selected,
                                              Predicate<BlockState> mayClear, HostHooks hooks, PostProcessingClearance postProcessing) {
        Objects.requireNonNull(level); Objects.requireNonNull(chunk);
        Objects.requireNonNull(mayClear); Objects.requireNonNull(hooks);
        if (selected != null && selected.length() > 4096) throw new IllegalArgumentException("Selection exceeds section");
        BitSet selection = selected == null ? null : (BitSet) selected.clone();
        if (selection != null && selection.cardinality() == 4096) selection = null;
        if (!level.getServer().isSameThread()) return rejected(Rejection.WRONG_THREAD);
        if (!currentChunk(level, chunk)) return rejected(Rejection.WRONG_CHUNK);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) return rejected(Rejection.INVALID_SECTION);
        if (level.captureBlockSnapshots) return rejected(Rejection.CAPTURING_SNAPSHOTS);
        if (!hooks.supportsBulk(level)) return rejected(Rejection.HOST_UNSUPPORTED);
        long revision = hooks.mutationRevision(chunk);
        if (revision < 0 || revision == Long.MAX_VALUE) return rejected(Rejection.TRACKING_UNAVAILABLE);
        var pending = chunk.getPostProcessing()[sectionIndex];
        if (pending != null && !pending.isEmpty()
                && (postProcessing == null || !postProcessing.matchesSection(level, chunk, sectionIndex)))
            return rejected(Rejection.POST_PROCESSING);
        LevelChunkSection original = chunk.getSection(sectionIndex);
        if (!(original instanceof NativeAccess.SectionStats stats)) return rejected(Rejection.TRACKING_UNAVAILABLE);
        int originalCount = stats.vre$accurateNonAirCount();
        if (originalCount < 0 || originalCount > 4096) return rejected(Rejection.TRACKING_UNAVAILABLE);
        // Preserve the O(palette) all-clear path. An unused old unsupported palette entry merely
        // sends this attempt to actual-state planning; it no longer blocks the whole section.
        if (selection == null) {
            var whole = prepare(level, chunk, sectionIndex, mayClear, hooks, postProcessing);
            if (whole.accepted()) return whole;
            if (hooks.mutationRevision(chunk) != revision || chunk.getSection(sectionIndex) != original)
                return rejected(Rejection.STALE);
        }
        BlockPos minimum = new BlockPos(chunk.getPos().getMinBlockX(), chunk.getSectionYFromSectionIndex(sectionIndex) << 4,
                chunk.getPos().getMinBlockZ());
        Set<BlockState> removable = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<BlockState> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<BlockState> nativeStates = Collections.newSetFromMap(new IdentityHashMap<>());
        // A retained-only palette (usually the bedrock band) needs no voxel decoding,
        // replacement encoding, or light repair. Unused entries can only reject this proof.
        if (selection == null && auxiliaryState(chunk, sectionIndex, postProcessing) == Rejection.NONE) {
            boolean actionable = original.maybeHas(state -> {
                if (isVanillaAir(state)) return false;
                if (!isSupportedState(state, level, minimum) || mayClear.test(state)) return true;
                retained.add(state);
                return false;
            });
            if (!actionable && hooks.mutationRevision(chunk) == revision && chunk.getSection(sectionIndex) == original)
                return new Preparation(new Prepared(level, chunk, sectionIndex, revision, removable, retained,
                        new BitSet(), new BitSet(), originalCount, original, mayClear, hooks,
                        PreparationPath.RETAINED_PALETTE, retained.size()), Rejection.NONE);
            retained.clear();
        }
        // Packed planning preserves all unselected values and unknown native-only states.
        // Block entities retain the position-aware ordinary fallback.
        if (chunk.getBlockEntitiesPos().stream().noneMatch(pos -> (pos.getY() >> 4) == chunk.getSectionYFromSectionIndex(sectionIndex))) {
            var remapped = preparePaletteRemap(level, chunk, sectionIndex, revision, original, originalCount,
                    minimum, selection, mayClear, hooks);
            if (remapped != null) return remapped;
        }
        original.getStates().getAll(state -> {
            if (isVanillaAir(state)) return;
            if (!isSupportedState(state, level, minimum)) nativeStates.add(state);
            else if (mayClear.test(state)) removable.add(state);
            else retained.add(state);
        });
        if (hooks.mutationRevision(chunk) != revision || chunk.getSection(sectionIndex) != original)
            return rejected(Rejection.STALE);
        BitSet changed = new BitSet(4096), nativeRemainder = new BitSet(4096);
        BitSet blockEntities = new BitSet(4096);
        int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
        for (BlockPos pos : chunk.getBlockEntitiesPos()) if ((pos.getY() >> 4) == sectionY)
            blockEntities.set(((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15));
        int keptBlocks = 0;
        PalettedContainer<BlockState> replacementStates = new RegionBlockStateContainer(AIR);
        for (int index = 0; index < 4096; index++) {
            int x = index & 15, y = index >>> 8, z = (index >>> 4) & 15;
            BlockState state = original.getBlockState(x, y, z);
            boolean select = selection == null || selection.get(index);
            if (select && removable.contains(state) && !blockEntities.get(index)) changed.set(index);
            else {
                if (state != AIR) replacementStates.set(x, y, z, state);
                if (select && (nativeStates.contains(state) || blockEntities.get(index))) nativeRemainder.set(index);
                if (select && retained.contains(state) && !blockEntities.get(index)) keptBlocks++;
            }
        }
        LevelChunkSection replacement = new LevelChunkSection(replacementStates, original.getBiomes());
        if (hooks.mutationRevision(chunk) != revision || chunk.getSection(sectionIndex) != original
                || stats.vre$accurateNonAirCount() != originalCount) return rejected(Rejection.STALE);
        return new Preparation(new Prepared(level, chunk, sectionIndex, revision, removable, retained,
                changed, nativeRemainder, keptBlocks, replacement, mayClear, hooks,
                PreparationPath.VOXEL, 4096), Rejection.NONE);
    }

    private static Preparation preparePaletteRemap(ServerLevel level, LevelChunk chunk, int sectionIndex, long revision,
            LevelChunkSection original, int originalCount, BlockPos minimum, BitSet selection,
            Predicate<BlockState> mayClear, HostHooks hooks) {
        var image = NativePaletteImage.capture(original.getStates());
        if (image == null) return null;
        byte[] actions = new byte[image.states.size()];
        var replacementPalette = new ArrayList<>(image.states);
        for (int i = 0; i < actions.length; i++) {
            var state = image.states.get(i);
            if (isVanillaAir(state)) continue;
            if (!isSupportedState(state, level, minimum)) actions[i] = 2;
            else if (mayClear.test(state)) { actions[i] = 1; replacementPalette.set(i, AIR); }
            else actions[i] = 3;
        }
        BitSet changed = new BitSet(4096), nativeRemainder = new BitSet(4096), used = new BitSet(actions.length);
        int[] cursorAndKept = new int[2];
        image.indices.getAll(id -> {
            int index = cursorAndKept[0]++;
            if (selection != null && !selection.get(index)) return;
            used.set(id);
            if (actions[id] == 1) changed.set(index);
            else if (actions[id] == 2) nativeRemainder.set(index);
            else if (actions[id] == 3) cursorAndKept[1]++;
        });
        var replacementStates = selection == null ? image.remap(replacementPalette) : image.clear(changed);
        if (replacementStates == null) return null;
        Set<BlockState> removable = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<BlockState> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int id = used.nextSetBit(0); id >= 0; id = used.nextSetBit(id + 1)) {
            if (actions[id] == 1) removable.add(image.states.get(id));
            else if (actions[id] == 3) retained.add(image.states.get(id));
        }
        var replacement = new LevelChunkSection(replacementStates, original.getBiomes());
        if (hooks.mutationRevision(chunk) != revision || chunk.getSection(sectionIndex) != original
                || ((NativeAccess.SectionStats)original).vre$accurateNonAirCount() != originalCount) return rejected(Rejection.STALE);
        return new Preparation(new Prepared(level, chunk, sectionIndex, revision, removable, retained,
                changed, nativeRemainder, cursorAndKept[1], replacement, mayClear, hooks,
                selection == null ? PreparationPath.PACKED_REMAP : PreparationPath.PACKED_SELECTION,
                image.states.size() + image.storageWords() + 2 * 64 + ((image.states.size() + 63) / 64)
                        + (selection == null ? 0 : 64)), Rejection.NONE);
    }

    /** Cheap read-only guard for a caller assembling one chunk across several ticks; commit still rechecks. */
    public static boolean validateBatch(List<Prepared> plans) {
        if (plans.isEmpty()) return true;
        Prepared first = plans.get(0);
        if (!first.level.getServer().isSameThread() || !currentChunk(first.level, first.chunk)
                || first.level.captureBlockSnapshots || first.hooks.mutationRevision(first.chunk) != first.revision)
            return false;
        if (first.postProcessing != null && !first.postProcessing.matchesMetadata()) return false;
        for (Prepared plan : plans) {
            if (plan.consumed || plan.level != first.level || plan.chunk != first.chunk || plan.hooks != first.hooks
                    || plan.postProcessing != first.postProcessing
                    || plan.revision != first.revision || plan.chunk.getSection(plan.sectionIndex) != plan.original
                    || ((NativeAccess.SectionStats) plan.original).vre$accurateNonAirCount() != plan.originalNonAir
                    || hasChangedBlockEntity(plan))
                return false;
        }
        return true;
    }

    /**
     * Commits same-chunk plans made from one revision. All policies and identities are checked before
     * the first palette swap. A post-swap failure is explicitly non-replayable. The returned light
     * future is separate from the caller's disk save acknowledgement and client packet flush.
     */
    public static CommitReport commitBatch(List<Prepared> input, BooleanSupplier finalPermission) {
        return commitBatch(input, finalPermission, (level, chunk, plans, nativeFallback) -> nativeFallback.get());
    }

    public static CommitReport commitBatch(List<Prepared> input, BooleanSupplier finalPermission, LightingStrategy lightingStrategy) {
        List<Prepared> plans = List.copyOf(input);
        if (plans.isEmpty()) throw new IllegalArgumentException("No sections to commit");
        Objects.requireNonNull(finalPermission);
        Objects.requireNonNull(lightingStrategy);
        Prepared first = plans.get(0);
        ServerLevel level = first.level;
        LevelChunk chunk = first.chunk;
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Bulk commit requires the server thread");
        Set<Integer> sectionIndices = new HashSet<>();
        for (Prepared plan : plans) {
            if (plan.level != level || plan.chunk != chunk || plan.revision != first.revision || plan.hooks != first.hooks
                    || plan.postProcessing != first.postProcessing
                    || !sectionIndices.add(plan.sectionIndex))
                throw new IllegalArgumentException("Batch must contain unique sections from one chunk, revision and host");
            if (plan.consumed) return notApplied(CommitResult.ALREADY_CONSUMED);
        }
        if (first.postProcessing != null && !first.postProcessing.plansEraseAll(plans)) return notApplied(CommitResult.STALE);
        plans.forEach(plan -> plan.consumed = true);
        if (!currentChunk(level, chunk) || level.captureBlockSnapshots) return notApplied(CommitResult.STALE);
        HostHooks hooks = first.hooks;
        if (!hooks.supportsBulk(level)) return notApplied(CommitResult.HOST_UNSUPPORTED);
        for (Prepared plan : plans) for (BlockState state : plan.palette)
            if (!isSupportedState(state, level, plan.minimumPosition()) || !plan.mayClear.test(state))
                return notApplied(CommitResult.STALE);
        for (Prepared plan : plans) for (BlockState state : plan.retainedPolicyStates)
            if (!isSupportedState(state, level, plan.minimumPosition()) || plan.mayClear.test(state))
                return notApplied(CommitResult.STALE);
        if (hooks.mutationRevision(chunk) != first.revision) return notApplied(CommitResult.STALE);
        int changedSections = (int) plans.stream().filter(plan -> plan.changedBlocks > 0).count();
        if (first.revision > Long.MAX_VALUE - changedSections) return notApplied(CommitResult.STALE);
        // Trusted epoch-only hooks run before the final claims callback; any native terrain callback
        // adds extra revision changes and will fail the exact revision check below.
        for (Prepared plan : plans) if (plan.changedBlocks > 0) hooks.beforePaletteReplaced(level, chunk, plan.sectionY);
        if (!finalPermission.getAsBoolean()) return notApplied(CommitResult.PERMISSION_DENIED);
        if (!currentChunk(level, chunk) || level.captureBlockSnapshots
                || hooks.mutationRevision(chunk) != first.revision + changedSections) return notApplied(CommitResult.STALE);
        if (first.postProcessing != null && !first.postProcessing.matchesMetadata()) return notApplied(CommitResult.STALE);
        for (Prepared plan : plans)
            if (chunk.getSection(plan.sectionIndex) != plan.original
                    || ((NativeAccess.SectionStats) plan.original).vre$accurateNonAirCount() != plan.originalNonAir
                    || hasChangedBlockEntity(plan)
                    || (plan.changed == null && auxiliaryState(chunk, plan.sectionIndex, plan.postProcessing) != Rejection.NONE)
                    || (chunk.getPostProcessing()[plan.sectionIndex] != null
                    && !chunk.getPostProcessing()[plan.sectionIndex].isEmpty()
                    && plan.postProcessing == null)) return notApplied(CommitResult.STALE);
        if (changedSections == 0) return new CommitReport(CommitResult.APPLIED_QUEUED, 0, false,
                CompletableFuture.completedFuture(null));

        // Allocate before mutating, so allocation/constructor failure cannot leave a partially swapped batch.
        List<LevelChunkSection> replacements = new ArrayList<>(plans.size());
        for (Prepared plan : plans)
            replacements.add(plan.changedBlocks == 0 ? plan.original : plan.replacement != null ? plan.replacement
                    : new LevelChunkSection(new RegionBlockStateContainer(AIR), plan.original.getBiomes()));
        chunk.setUnsaved(true);
        for (int i = 0; i < plans.size(); i++) chunk.getSections()[plans.get(i).sectionIndex] = replacements.get(i);
        try {
            for (Prepared plan : plans) if (plan.changedBlocks > 0) hooks.afterPaletteReplaced(level, chunk, plan.sectionY);
            if (!ClearedColumnMetadata.rebuild(chunk, HEIGHTMAPS)) {
                for (Heightmap.Types type : HEIGHTMAPS) chunk.setHeightmap(type, new Heightmap(chunk, type).getRawData());
                if (chunk.getHighestFilledSectionIndex() >= 0) Heightmap.primeHeightmaps(chunk, HEIGHTMAPS);
                chunk.initializeLightSources();
            }
            var lighting = level.getChunkSource().getLightEngine();
            for (Prepared plan : plans) {
                if (plan.changedBlocks == 0) continue;
                BlockPos minimum = plan.minimumPosition();
                BoundingBox area = new BoundingBox(minimum.getX(), minimum.getY(), minimum.getZ(),
                        minimum.getX() + 15, minimum.getY() + 15, minimum.getZ() + 15);
                if (plan.changed == null) {
                    level.getBlockTicks().clearArea(area);
                    level.getFluidTicks().clearArea(area);
                } else {
                    Predicate<BlockPos> removed = pos -> (pos.getY() >> 4) == plan.sectionY
                            && plan.changedAt(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                    ((NativeAccess.TickFilter) level.getBlockTicks()).vre$clearChanged(chunk.getPos(), removed);
                    ((NativeAccess.TickFilter) level.getFluidTicks()).vre$clearChanged(chunk.getPos(), removed);
                }
                lighting.updateSectionStatus(minimum, chunk.getSection(plan.sectionIndex).hasOnlyAir());
            }
            updateNavigation(level, plans);
            for (Prepared plan : plans) {
                if (plan.changedBlocks == 0) continue;
                plan.observerActive = true;
                try { hooks.afterSectionCleared(level, chunk, plan); }
                finally { plan.observerActive = false; }
            }
            CompletableFuture<Void> lightWork = Objects.requireNonNull(lightingStrategy.queue(level, chunk, plans,
                    () -> queueNativeLighting(level, chunk, plans)), "Lighting strategy returned no completion future");
            return new CommitReport(CommitResult.APPLIED_QUEUED, plans.stream().mapToInt(Prepared::changedBlocks).sum(), true, lightWork);
        } catch (RuntimeException | LinkageError failure) {
            throw new CommitFailure(changedSections, failure);
        }
    }

    private static CompletableFuture<Void> queueNativeLighting(ServerLevel level, LevelChunk chunk, List<Prepared> plans) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Native lighting fallback must queue on the server thread");
        var lighting = level.getChunkSource().getLightEngine();
        List<NativeAccess.LightChanges> changes = new ArrayList<>();
        for (Prepared plan : plans) {
            if (plan.changedBlocks == 0 || !plan.fullLightChecks) continue;
            BitSet cells = plan.changed;
            if (cells == null) {
                cells = new BitSet(4096);
                for (int index = 0; index < 4096; index++)
                    if (plan.changedAt(index & 15, index >>> 8, (index >>> 4) & 15)) cells.set(index);
            }
            changes.add(new NativeAccess.LightChanges(plan.sectionY, cells));
        }
        int[] boundarySections = plans.stream().filter(plan -> plan.changedBlocks > 0 && !plan.fullLightChecks)
                .mapToInt(Prepared::sectionY).toArray();
        var fence = (NativeAccess.LightFence) lighting;
        return fence.vre$combinedChecks(chunk.getPos(), changes, boundarySections,
                plans.stream().anyMatch(plan -> plan.replaySkySources));
    }

    /** Aggregate native navigation checks; mixed cubes may conservatively recompute extra paths once. */
    private static void updateNavigation(ServerLevel level, List<Prepared> plans) {
        List<PathNavigation> affected = new ArrayList<>();
        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof Mob mob)) continue;
            PathNavigation navigation = mob.getNavigation();
            var path = navigation.getPath();
            if (path == null || path.isDone() || path.getNodeCount() == 0) continue;
            var end = path.getEndNode();
            double centerX = (end.x + mob.getX()) / 2.0;
            double centerY = (end.y + mob.getY()) / 2.0;
            double centerZ = (end.z + mob.getZ()) / 2.0;
            for (Prepared plan : plans) {
                BlockPos minimum = plan.minimumPosition();
                // Native shouldRecomputePath tests distance from block centers to this midpoint.
                // The nearest lattice center minimizes that distance over every block in the cube.
                BlockPos nearest = new BlockPos(Mth.clamp(Mth.floor(centerX), minimum.getX(), minimum.getX() + 15),
                        Mth.clamp(Mth.floor(centerY), minimum.getY(), minimum.getY() + 15),
                        Mth.clamp(Mth.floor(centerZ), minimum.getZ(), minimum.getZ() + 15));
                if (navigation.shouldRecomputePath(nearest)) { affected.add(navigation); break; }
            }
        }
        for (PathNavigation navigation : affected) navigation.recomputePath();
    }

    public static final class CommitFailure extends RuntimeException {
        private final int appliedSections;
        private CommitFailure(int appliedSections, Throwable cause) {
            super("Palette batch applied but derived bookkeeping failed; do not replay", cause);
            this.appliedSections = appliedSections;
        }
        public int appliedSections() { return appliedSections; }
        public boolean terrainChanged() { return true; }
    }

    private static CommitReport notApplied(CommitResult result) {
        return new CommitReport(result, 0, false, CompletableFuture.completedFuture(null));
    }

    private static Preparation rejected(Rejection rejection) { return new Preparation(null, rejection); }

    private static boolean currentChunk(ServerLevel level, LevelChunk chunk) {
        return chunk.getLevel() == level && level.getChunkSource().getChunkNow(chunk.getPos().x, chunk.getPos().z) == chunk;
    }

    private static Rejection auxiliaryState(LevelChunk chunk, int sectionIndex) {
        return auxiliaryState(chunk, sectionIndex, null);
    }

    private static Rejection auxiliaryState(LevelChunk chunk, int sectionIndex, PostProcessingClearance proof) {
        int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
        for (BlockPos position : chunk.getBlockEntitiesPos())
            if ((position.getY() >> 4) == sectionY) return Rejection.BLOCK_ENTITY;
        var postProcessing = chunk.getPostProcessing()[sectionIndex];
        return postProcessing != null && !postProcessing.isEmpty()
                && (proof == null || !(chunk.getLevel() instanceof ServerLevel level)
                || !proof.matchesSection(level, chunk, sectionIndex)) ? Rejection.POST_PROCESSING : Rejection.NONE;
    }

    private static boolean hasChangedBlockEntity(Prepared plan) {
        for (BlockPos position : plan.chunk.getBlockEntitiesPos())
            if ((position.getY() >> 4) == plan.sectionY
                    && plan.changedAt(position.getX() & 15, position.getY() & 15, position.getZ() & 15)) return true;
        return false;
    }

    private static boolean isSupportedState(BlockState state, ServerLevel level, BlockPos position) {
        return TerrainRemovalProof.supports(state, level, position);
    }

    private static boolean isVanillaAir(BlockState state) {
        return state == AIR || state == Blocks.CAVE_AIR.defaultBlockState() || state == Blocks.VOID_AIR.defaultBlockState();
    }

    private PaletteSectionClearBackend() {}
}
