/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Server-thread, whole-section replacement for an explicitly audited set of static vanilla states.
 * The caller must prove full geometric coverage, reserve the entire write budget, persist its intent,
 * and hold the chunk loaded until its own light/save fence completes. This class only queues lighting
 * and client updates: {@link CommitResult#APPLIED_QUEUED} is not a durable completion acknowledgement.
 *
 * <p>The native notification policy is flags {@code 2 | 16}: clients and state-change observers are
 * notified, but neighbor physics and shape propagation are suppressed. Use the native per-block
 * backend when another physics policy is required. Fluids, block entities, POIs, random-ticking
 * states, and unknown blocks always require that fallback.</p>
 */
public final class StaticSectionClearBackend {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int CELLS = 16 * 16 * 16;
    private static final Set<Heightmap.Types> HEIGHTMAPS = Collections.unmodifiableSet(EnumSet.of(
            Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE));
    private static final Set<BlockState> STATIC_STATES = staticStates();

    private StaticSectionClearBackend() {}

    /**
     * Integration contract for hooks skipped by replacing a section rather than calling
     * LevelChunk/LevelChunkSection.setBlockState. Unknown integrations must return false.
     * In particular, installing this backend does not automatically support chunk watchers,
     * section revision counters, custom storage, or replacement lighting engines added by mods.
     */
    public interface HostHooks {
        boolean supportsBulk(ServerLevel level);

        /**
         * Invalidate mutation epochs before writing. Called before the final snapshot check, so a
         * stale rejection can conservatively invalidate an old receipt without changing terrain.
         */
        default void beforePaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY) {}

        /** Called immediately after the palette swap, before any derived data is rebuilt. */
        default void afterPaletteReplaced(ServerLevel level, LevelChunk chunk, int sectionY) {}

        /**
         * Called once after terrain, heightmaps, light queues, ticks, and native notifications are
         * updated. Implementations must update any audited host indices/watchers/revision counters.
         * No observer may assume that a bulk replacement delivers the intermediate per-voxel world.
         */
        default void afterSectionCleared(ServerLevel level, LevelChunk chunk, Prepared previous) {}
    }

    public enum Rejection {
        NONE, WRONG_THREAD, WRONG_CHUNK, INVALID_SECTION, CAPTURING_SNAPSHOTS, HOST_UNSUPPORTED,
        BLOCK_ENTITY, POST_PROCESSING, UNSUPPORTED_STATE, PROTECTED_STATE, EMPTY, STALE
    }

    public enum CommitResult {
        APPLIED_QUEUED, ALREADY_CONSUMED, STALE, PERMISSION_DENIED, PROTECTED_STATE,
        HOST_UNSUPPORTED, CAPTURING_SNAPSHOTS
    }

    public record Preparation(Prepared prepared, Rejection rejection) {
        public boolean accepted() { return prepared != null; }
    }

    /** Immutable terrain snapshot, with a single-use commit token. Never send this to a worker. */
    public static final class Prepared {
        private final ServerLevel level;
        private final LevelChunk chunk;
        private final int sectionIndex;
        private final int minY;
        private final LevelChunkSection original;
        private final BlockState[] states;
        private final Set<BlockState> distinctStates;
        private final int changedBlocks;
        private final Predicate<BlockState> mayClear;
        private final HostHooks hooks;
        private boolean consumed;

        private Prepared(ServerLevel level, LevelChunk chunk, int sectionIndex,
                         LevelChunkSection original, BlockState[] states,
                         Set<BlockState> distinctStates, int changedBlocks,
                         Predicate<BlockState> mayClear, HostHooks hooks) {
            this.level = level;
            this.chunk = chunk;
            this.sectionIndex = sectionIndex;
            this.minY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
            this.original = original;
            this.states = states;
            this.distinctStates = distinctStates;
            this.changedBlocks = changedBlocks;
            this.mayClear = mayClear;
            this.hooks = hooks;
        }

        public int sectionIndex() { return sectionIndex; }
        public int sectionY() { return minY >> 4; }
        public int changedBlocks() { return changedBlocks; }
        public BlockPos minimumPosition() { return new BlockPos(chunk.getPos().getMinBlockX(), minY, chunk.getPos().getMinBlockZ()); }

        public BlockState previousState(int localX, int localY, int localZ) {
            if ((localX | localY | localZ) < 0 || localX > 15 || localY > 15 || localZ > 15)
                throw new IndexOutOfBoundsException("Section-local coordinates must be 0..15");
            return states[index(localX, localY, localZ)];
        }
    }

    /**
     * {@code mayClear} is a palette-only protection decision for these exact, context-independent
     * states (for example tags and a task resistance threshold). Context-sensitive policies must
     * reject the bulk path. The predicate and host hook may run twice; neither may defer mutations.
     *
     * <p>Ordinary AIR is preserved. CAVE_AIR and VOID_AIR deliberately cause fallback: replacing
     * those with AIR would differ from the native path, which skips all existing air states.</p>
     */
    public static Preparation prepare(ServerLevel level, LevelChunk chunk, int sectionIndex,
                                      Predicate<BlockState> mayClear, HostHooks hooks) {
        Objects.requireNonNull(level);
        Objects.requireNonNull(chunk);
        Objects.requireNonNull(mayClear);
        Objects.requireNonNull(hooks);
        if (!level.getServer().isSameThread()) return rejected(Rejection.WRONG_THREAD);
        if (!isCurrentChunk(level, chunk)) return rejected(Rejection.WRONG_CHUNK);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length)
            return rejected(Rejection.INVALID_SECTION);
        if (level.captureBlockSnapshots) return rejected(Rejection.CAPTURING_SNAPSHOTS);
        if (!hooks.supportsBulk(level)) return rejected(Rejection.HOST_UNSUPPORTED);
        Rejection auxiliary = auxiliaryState(chunk, sectionIndex);
        if (auxiliary != Rejection.NONE) return rejected(auxiliary);

        LevelChunkSection original = chunk.getSection(sectionIndex);
        BlockState[] states = new BlockState[CELLS];
        Set<BlockState> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        int changed = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = original.getBlockState(x, y, z);
                    states[index(x, y, z)] = state;
                    if (state == AIR) continue;
                    changed++;
                    if (distinct.add(state)) {
                        if (!isSupportedState(state)) return rejected(Rejection.UNSUPPORTED_STATE);
                        if (!mayClear.test(state)) return rejected(Rejection.PROTECTED_STATE);
                    }
                }
            }
        }
        if (changed == 0) return rejected(Rejection.EMPTY);
        Prepared prepared = new Prepared(level, chunk, sectionIndex, original, states,
                Collections.unmodifiableSet(distinct), changed, mayClear, hooks);
        if (!snapshotMatches(prepared)) return rejected(Rejection.STALE);
        return new Preparation(prepared, Rejection.NONE);
    }

    /**
     * Attempts this token exactly once. The permission callback is the final external callback
     * before comparing all current states and swapping the palette. False leaves terrain untouched.
     * The caller must not interpret {@link CommitFailure} as an uncommitted attempt or replay it.
     */
    public static CommitResult commit(Prepared prepared, BooleanSupplier finalPermission) {
        Objects.requireNonNull(prepared);
        Objects.requireNonNull(finalPermission);
        if (!prepared.level.getServer().isSameThread())
            throw new IllegalStateException("Section commit must run on the Minecraft server thread");
        if (prepared.consumed) return CommitResult.ALREADY_CONSUMED;
        prepared.consumed = true;
        ServerLevel level = prepared.level;
        LevelChunk chunk = prepared.chunk;
        if (level.captureBlockSnapshots) return CommitResult.CAPTURING_SNAPSHOTS;
        if (!prepared.hooks.supportsBulk(level)) return CommitResult.HOST_UNSUPPORTED;
        for (BlockState state : prepared.distinctStates) {
            if (!isSupportedState(state) || !prepared.mayClear.test(state)) return CommitResult.PROTECTED_STATE;
        }
        prepared.hooks.beforePaletteReplaced(level, chunk, prepared.sectionY());
        if (!finalPermission.getAsBoolean()) return CommitResult.PERMISSION_DENIED;
        // These checks deliberately follow every policy/integration callback.
        if (level.captureBlockSnapshots) return CommitResult.CAPTURING_SNAPSHOTS;
        if (!isCurrentChunk(level, chunk) || auxiliaryState(chunk, prepared.sectionIndex) != Rejection.NONE
                || !snapshotMatches(prepared)) return CommitResult.STALE;

        LevelChunkSection empty = new LevelChunkSection(new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, AIR, PalettedContainer.Strategy.SECTION_STATES),
                prepared.original.getBiomes());
        chunk.setUnsaved(true);
        chunk.getSections()[prepared.sectionIndex] = empty;
        // Everything below may fail after terrain was applied. Make that fact unambiguous to callers.
        try {
            prepared.hooks.afterPaletteReplaced(level, chunk, prepared.sectionY());
            // primeHeightmaps does not reset columns that have become wholly empty. Reset first.
            for (Heightmap.Types type : HEIGHTMAPS)
                chunk.setHeightmap(type, new Heightmap(chunk, type).getRawData());
            Heightmap.primeHeightmaps(chunk, HEIGHTMAPS);
            chunk.initializeLightSources();

            BlockPos minimum = prepared.minimumPosition();
            BoundingBox area = new BoundingBox(minimum.getX(), prepared.minY, minimum.getZ(),
                    minimum.getX() + 15, prepared.minY + 15, minimum.getZ() + 15);
            level.getBlockTicks().clearArea(area);
            level.getFluidTicks().clearArea(area);
            var lighting = level.getChunkSource().getLightEngine();
            lighting.updateSectionStatus(minimum, true);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState previous = prepared.states[index(x, y, z)];
                        if (previous == AIR) continue;
                        BlockPos position = minimum.offset(x, y, z);
                        lighting.checkBlock(position);
                        // Includes native navigation invalidation, client aggregation, POI and Forge
                        // state-change observers. It checks actual new state before notifying.
                        level.markAndNotifyBlock(position, chunk, previous, AIR, FLAGS, 512);
                    }
                }
            }
            prepared.hooks.afterSectionCleared(level, chunk, prepared);
        } catch (RuntimeException | LinkageError failure) {
            throw new CommitFailure(failure);
        }
        return CommitResult.APPLIED_QUEUED;
    }

    /** A post-swap failure; terrain changed and automatic replay is unsafe. */
    public static final class CommitFailure extends RuntimeException {
        private CommitFailure(Throwable cause) { super("Section terrain applied but bookkeeping failed; do not replay", cause); }
        public boolean terrainChanged() { return true; }
    }

    private static Preparation rejected(Rejection rejection) { return new Preparation(null, rejection); }

    private static boolean isCurrentChunk(ServerLevel level, LevelChunk chunk) {
        return chunk.getLevel() == level
                && level.getChunkSource().getChunkNow(chunk.getPos().x, chunk.getPos().z) == chunk;
    }

    private static Rejection auxiliaryState(LevelChunk chunk, int sectionIndex) {
        int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
        for (BlockPos position : chunk.getBlockEntitiesPos()) {
            if ((position.getY() >> 4) == sectionY) return Rejection.BLOCK_ENTITY;
        }
        var postProcessing = chunk.getPostProcessing()[sectionIndex];
        return postProcessing != null && !postProcessing.isEmpty() ? Rejection.POST_PROCESSING : Rejection.NONE;
    }

    private static boolean snapshotMatches(Prepared prepared) {
        if (prepared.chunk.getSection(prepared.sectionIndex) != prepared.original) return false;
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    if (prepared.original.getBlockState(x, y, z) != prepared.states[index(x, y, z)]) return false;
        return true;
    }

    private static boolean isSupportedState(BlockState state) {
        return STATIC_STATES.contains(state) && state.getBlock().getClass() == Block.class
                && state == state.getBlock().defaultBlockState() && state.getValues().isEmpty()
                && !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && !state.isRandomlyTicking() && PoiTypes.forState(state).isEmpty();
    }

    private static int index(int x, int y, int z) { return (y << 8) | (z << 4) | x; }

    private static Set<BlockState> staticStates() {
        Set<BlockState> result = Collections.newSetFromMap(new IdentityHashMap<>());
        // Exact vanilla constructors were audited in 1.20.1 Blocks and BlockBehaviour. Do not
        // generalize this to namespace checks, Block subclasses, or arbitrary default states.
        for (Block block : new Block[] { Blocks.STONE, Blocks.GRANITE, Blocks.POLISHED_GRANITE,
                Blocks.DIORITE, Blocks.POLISHED_DIORITE, Blocks.ANDESITE, Blocks.POLISHED_ANDESITE,
                Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.COBBLESTONE, Blocks.END_STONE,
                Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.COBBLED_DEEPSLATE })
            result.add(block.defaultBlockState());
        return Collections.unmodifiableSet(result);
    }
}
