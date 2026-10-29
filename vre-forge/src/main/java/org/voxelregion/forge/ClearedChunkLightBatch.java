/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LightEngine;
import org.voxelregion.forge.runtime.ChunkMutationTracking;
import org.voxelregion.forge.runtime.RuntimeBridge;
import org.voxelregion.lighting.ClearedChunkLighting;
import org.voxelregion.lighting.ClearedChunkLighting.Face;

/**
 * Full-height AIR/opaque non-emitter proof, detached solution, legal native layer
 * installation, then exterior reconciliation. The returned future is a prerequisite
 * of save/packet work, not merely another future joined after an earlier save.
 */
public final class ClearedChunkLightBatch {
    /** Bounded diagnostics: each real native fallback increments exactly one reason. */
    public enum FallbackReason {
        INVALID_HEIGHT, CAPACITY, MEMORY_LIMIT, HOST_UNSUPPORTED, NATIVE_REMAINDER,
        MISSING_NEIGHBOR, UNTRACKED_REVISION, STALE_BEFORE_CAPTURE,
        UNSUPPORTED_DOMAIN, STALE_AFTER_CAPTURE
    }
    public static final PaletteSectionClearBackend.LightingStrategy STRATEGY = ClearedChunkLightBatch::queue;
    private static final Set<Job> pending = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final AtomicLong bytes = new AtomicLong(), installed = new AtomicLong(), fallback = new AtomicLong();
    private static final AtomicLong refreshed = new AtomicLong();
    private static final AtomicLongArray fallbackReasons = new AtomicLongArray(FallbackReason.values().length);
    private static final AtomicLong captureCalls = new AtomicLong(), captureNanos = new AtomicLong(), boundaryNanos = new AtomicLong();
    private static final AtomicLong copiedLayers = new AtomicLong(), cachedBlockSamples = new AtomicLong(), cachedSkySamples = new AtomicLong();
    private static final AtomicLong nativeBlockSamples = new AtomicLong(), nativeSkySamples = new AtomicLong();
    private static final int MAX_JOBS = 8;

    public static long estimatedPendingBytes() { return bytes.get(); }
    public static long installedBatches() { return installed.get(); }
    public static long fallbackBatches() { return fallback.get(); }
    public static long preCaptureRefreshes() { return refreshed.get(); }
    /** Actual synchronous capture cost and read counts; moving work to a different callback does not reset these. */
    public static Map<String, Long> captureStatistics() {
        return Map.of("calls", captureCalls.get(), "nanos", captureNanos.get(), "boundaryNanos", boundaryNanos.get(),
                "copiedVisibleLayers", copiedLayers.get(), "cachedBlockSamples", cachedBlockSamples.get(),
                "cachedSkySamples", cachedSkySamples.get(), "nativeBlockSamples", nativeBlockSamples.get(),
                "nativeSkySamples", nativeSkySamples.get());
    }
    public static Map<FallbackReason, Long> fallbackReasonCounts() {
        EnumMap<FallbackReason, Long> result = new EnumMap<>(FallbackReason.class);
        for (FallbackReason reason : FallbackReason.values()) result.put(reason, fallbackReasons.get(reason.ordinal()));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Called on the server thread after the complete chunk transaction, including
     * its native callbacks and remainder removals, has finished. Historical plan
     * remainder counts do not describe this final image. The final entire build
     * height must still pass the same AIR/opaque non-emitter proof.
     *
     * <p>The caller must keep this future ahead of save and packet work, and must
     * invoke its real native fallback instead when the transaction did not finish.
     * The older {@link #STRATEGY} retains its pre-native-remainder contract.</p>
     */
    public static CompletableFuture<Void> afterWholeChunkMutation(ServerLevel level, LevelChunk chunk,
            Supplier<CompletableFuture<Void>> nativeFallback) {
        var air = AirRegionLightBatch.tryQueue(level, chunk, nativeFallback);
        if (air != null) return air;
        return queue(level, chunk, List.of(), nativeFallback);
    }

    private static CompletableFuture<Void> queue(ServerLevel level, LevelChunk chunk,
            List<PaletteSectionClearBackend.Prepared> plans, Supplier<CompletableFuture<Void>> nativeFallback) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Light proof requires the server thread");
        int height = level.getHeight();
        if (height < 16 || height > ClearedChunkLighting.MAX_HEIGHT || height % 16 != 0)
            return fallback(nativeFallback, FallbackReason.INVALID_HEIGHT);
        if (pending.size() >= MAX_JOBS) return fallback(nativeFallback, FallbackReason.CAPACITY);
        if (!HostIntegration.supportsBulk(level)) return fallback(nativeFallback, FallbackReason.HOST_UNSUPPORTED);
        if (plans.stream().anyMatch(plan -> plan.nativeRemainderCount() != 0))
            return fallback(nativeFallback, FallbackReason.NATIVE_REMAINDER);
        if (height * 5120L > RuntimeBridge.availableMetadataBytes(level.getServer()))
            return fallback(nativeFallback, FallbackReason.MEMORY_LIMIT);
        List<LevelChunk> ring = new ArrayList<>(9); long[] revisions = new long[9];
        for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
            LevelChunk neighbor = level.getChunkSource().getChunkNow(chunk.getPos().x + x, chunk.getPos().z + z);
            if (neighbor == null || (x == 0 && z == 0 && neighbor != chunk))
                return fallback(nativeFallback, FallbackReason.MISSING_NEIGHBOR);
            long revision = ChunkMutationTracking.revision(neighbor);
            if (revision < 0 || revision == Long.MAX_VALUE) return fallback(nativeFallback, FallbackReason.UNTRACKED_REVISION);
            revisions[ring.size()] = revision; ring.add(neighbor);
        }
        Job job = new Job(level, chunk, ring, revisions, nativeFallback);
        pending.add(job);
        job.unsubscribe = ChunkMutationTracking.subscribe(level, (changed, revision) -> {
            if (ring.contains(changed)) job.valid.set(false);
        });
        awaitStableRing(job, (NativeAccess.LightFence) level.getChunkSource().getLightEngine());
        return job.result;
    }

    private static void awaitStableRing(Job job, NativeAccess.LightFence light) {
        ServerLevel level = job.level; LevelChunk chunk = job.chunk;
        CompletableFuture<?>[] before;
        try {
            before = job.ring.stream().map(neighbor -> light.vre$afterChunkWork(neighbor.getPos()))
                    .toArray(CompletableFuture[]::new);
        } catch (RuntimeException | LinkageError error) { job.finish(error); return; }
        CompletableFuture.allOf(before).whenCompleteAsync((ignored, failure) -> {
            if (job.closed) return;
            if (failure != null) { job.finish(failure); return; }
            try {
                if (!job.current()) {
                    // Only pre-capture waiting may restart, and only once. A new ring
                    // revision must pass fresh native barriers before its light can be
                    // sampled; merely resetting the valid bit would reuse stale light.
                    if (job.refreshes == 0) {
                        FallbackReason rejection = job.refreshRing();
                        if (rejection == null) { refreshed.incrementAndGet(); awaitStableRing(job, light); return; }
                        job.nativeFallback(rejection);
                    } else job.nativeFallback(FallbackReason.STALE_BEFORE_CAPTURE);
                    return;
                }
                ClearedChunkLighting.Snapshot snapshot = capture(level, chunk, job.ring);
                if (snapshot == null) { job.nativeFallback(FallbackReason.UNSUPPORTED_DOMAIN); return; }
                if (!job.current()) { job.nativeFallback(FallbackReason.STALE_BEFORE_CAPTURE); return; }
                job.setInput(snapshot);
                light.vre$solveAndInstall(chunk.getPos(), level.getMinSection(), job::takeInput,
                        job.valid::get, job::releaseInput).whenCompleteAsync((applied, error) -> {
                    if (job.closed) return;
                    if (error != null) { job.finish(error); return; }
                    try {
                        if (!Boolean.TRUE.equals(applied) || !job.current()) {
                            // A player can change an originally retained opaque voxel into an emitter.
                            // Old changed-cell masks cannot repair that stale installed light image.
                            light.vre$fullChecks(chunk.getPos(), level.getMinSection(), level.getSectionsCount())
                                    .whenCompleteAsync((done, repairFailure) -> {
                                        if (repairFailure != null) job.finish(repairFailure);
                                        else job.nativeFallback(FallbackReason.STALE_AFTER_CAPTURE);
                                    }, level.getServer());
                        } else { installed.incrementAndGet(); job.finish(null); }
                    } catch (RuntimeException | LinkageError repairError) { job.finish(repairError); }
                }, level.getServer());
            } catch (RuntimeException | LinkageError error) { job.finish(error); }
        }, level.getServer());
    }

    private static CompletableFuture<Void> fallback(Supplier<CompletableFuture<Void>> nativeFallback, FallbackReason reason) {
        fallback.incrementAndGet(); fallbackReasons.incrementAndGet(reason.ordinal()); return nativeFallback.get();
    }

    private static ClearedChunkLighting.Snapshot capture(ServerLevel level, LevelChunk chunk, List<LevelChunk> ring) {
        long started = System.nanoTime();
        try { return captureImage(level, chunk, ring); }
        finally { captureNanos.addAndGet(System.nanoTime() - started); captureCalls.incrementAndGet(); }
    }

    private static ClearedChunkLighting.Snapshot captureImage(ServerLevel level, LevelChunk chunk, List<LevelChunk> ring) {
        int height = level.getHeight(), minY = level.getMinBuildHeight();
        BitSet opaque = new BitSet(height * 256);
        Map<BlockState, Integer> types = new IdentityHashMap<>();
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        for (int section = 0; section < chunk.getSections().length; section++) {
            var states = chunk.getSection(section);
            if (states.hasOnlyAir() && !states.maybeHas(state -> !vanillaAir(state))) continue;
            position.set(minX, minY + section * 16, minZ);
            boolean hasAir = states.maybeHas(ClearedChunkLightBatch::vanillaAir);
            if (states.maybeHas(state -> type(level, position, state, types) < 0)) {
                // Native removal may leave unused emitter/fluid entries in its palette.
                // Keep the O(palette) proof when it succeeds; only ambiguous palettes
                // inspect currently used storage IDs, rather than rejecting phantom states.
                int[] usedTypes = { 0 };
                states.getStates().getAll(state -> usedTypes[0] |= switch (type(level, position, state, types)) {
                    case 0 -> 1;
                    case 1 -> 2;
                    default -> 4;
                });
                if ((usedTypes[0] & 4) != 0) return null;
                if ((usedTypes[0] & 2) == 0) continue;
                hasAir = (usedTypes[0] & 1) != 0;
            }
            if (!hasAir) { opaque.set(section * 4096, (section + 1) * 4096); continue; }
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                if (!vanillaAir(states.getBlockState(x, y, z))) opaque.set(section * 4096 + (x | z << 4 | y << 8));
        }
        byte[][] block = new byte[6][], sky = new byte[6][]; BitSet[] open = new BitSet[6];
        for (Face face : Face.values()) {
            int f = face.ordinal(), size = ClearedChunkLighting.faceSize(height, face);
            block[f] = new byte[size]; sky[f] = new byte[size]; open[f] = new BitSet(size);
        }
        // The domain includes the entire legal build height. Above it is an explicit
        // dimension sky source; below it has no independently loaded emitter.
        BitSet direct = new BitSet(256);
        if (level.dimensionType().hasSkyLight()) {
            direct.set(0, 256); open[Face.UP.ordinal()].set(0, 256);
            Arrays.fill(sky[Face.UP.ordinal()], (byte) 15);
        }
        captureBoundaries(level, ring, block, sky, open);
        return new ClearedChunkLighting.Snapshot(height, opaque, block, sky, open, direct);
    }

    private static void captureBoundaries(ServerLevel level, List<LevelChunk> ring,
            byte[][] block, byte[][] sky, BitSet[] open) {
        var lighting = level.getChunkSource().getLightEngine();
        var blockListener = lighting.getLayerListener(LightLayer.BLOCK);
        var skyListener = lighting.getLayerListener(LightLayer.SKY);
        NativeAccess.VisibleLightData blockStorage = blockListener instanceof NativeAccess.VisibleLightData data ? data : null;
        NativeAccess.VisibleLightData skyStorage = skyListener instanceof NativeAccess.VisibleLightData data ? data : null;
        captureBoundaryLayers(level, ring, block, sky, open, blockStorage, skyStorage);
    }

    private static void captureBoundaryLayers(ServerLevel level, List<LevelChunk> ring, byte[][] block, byte[][] sky,
            BitSet[] open, NativeAccess.VisibleLightData blockStorage, NativeAccess.VisibleLightData skyStorage) {
        BlockPos.MutableBlockPos source = new BlockPos.MutableBlockPos(), destination = new BlockPos.MutableBlockPos();
        long started = System.nanoTime(), copies = 0, cachedBlock = 0, cachedSky = 0, nativeBlock = 0, nativeSky = 0;
        try {
            // Face-major, then section-major: each native visible layer and block-state
            // section is fetched once for its 16x16 face. No queued layer is consulted.
            for (Face face : new Face[] { Face.WEST, Face.EAST, Face.NORTH, Face.SOUTH }) {
                int neighborIndex = switch (face) { case WEST -> 3; case EAST -> 5; case NORTH -> 1; case SOUTH -> 7; default -> throw new AssertionError(); };
                Direction inward = switch (face) { case WEST -> Direction.EAST; case EAST -> Direction.WEST; case NORTH -> Direction.SOUTH; case SOUTH -> Direction.NORTH; default -> throw new AssertionError(); };
                LevelChunk neighbor = ring.get(neighborIndex);
                int f = face.ordinal(), baseX = neighbor.getPos().getMinBlockX(), baseZ = neighbor.getPos().getMinBlockZ();
                for (int section = 0; section < neighbor.getSections().length; section++) {
                    long sectionPosition = SectionPos.asLong(neighbor.getPos().x, level.getSectionYFromSectionIndex(section), neighbor.getPos().z);
                    DataLayer blockData = null, skyData = null;
                    // Optional acceleration: a custom provider may expose the interface but
                    // decline it. Distinguish that from a supported read returning no layer.
                    if (blockStorage != null) {
                        try { blockData = blockStorage.vre$copyVisibleSectionData(sectionPosition); }
                        catch (UnsupportedOperationException | LinkageError unavailable) { blockStorage = null; }
                    }
                    if (skyStorage != null) {
                        try { skyData = skyStorage.vre$copyVisibleSectionData(sectionPosition); }
                        catch (UnsupportedOperationException | LinkageError unavailable) { skyStorage = null; }
                    }
                    if (blockData != null) copies++;
                    if (skyData != null) copies++;
                    var states = neighbor.getSection(section);
                    for (int y = 0; y < 16; y++) for (int edge = 0; edge < 16; edge++) {
                        int x = face == Face.WEST ? 15 : face == Face.EAST ? 0 : edge;
                        int z = face == Face.NORTH ? 15 : face == Face.SOUTH ? 0 : edge;
                        int worldY = level.getMinBuildHeight() + section * 16 + y, index = (section * 16 + y) * 16 + edge;
                        source.set(baseX + x, worldY, baseZ + z);
                        destination.setWithOffset(source, inward);
                        BlockState state = states.getBlockState(x, y, z);
                        if (blockStorage != null) {
                            // BlockLightSectionStorage.getLightValue: absent visible data is exactly zero.
                            block[f][index] = (byte) (blockData == null ? 0 : blockData.get(x, y, z)); cachedBlock++;
                        } else { block[f][index] = (byte) level.getBrightness(LightLayer.BLOCK, source); nativeBlock++; }
                        if (skyData != null) {
                            sky[f][index] = (byte) skyData.get(x, y, z); cachedSky++;
                        } else {
                            // Missing sky layers can repeat the bottom face of a higher stored
                            // layer, or resolve to native sky/dimension defaults. Never assume 15.
                            sky[f][index] = (byte) level.getBrightness(LightLayer.SKY, source); nativeSky++;
                        }
                        if (LightEngine.getLightBlockInto(level, state, source, Blocks.AIR.defaultBlockState(), destination, inward, 1) < 16)
                            open[f].set(index);
                    }
                }
            }
        } finally {
            boundaryNanos.addAndGet(System.nanoTime() - started); copiedLayers.addAndGet(copies);
            cachedBlockSamples.addAndGet(cachedBlock); cachedSkySamples.addAndGet(cachedSky);
            nativeBlockSamples.addAndGet(nativeBlock); nativeSkySamples.addAndGet(nativeSky);
        }
    }
    private static int type(ServerLevel level, BlockPos position, BlockState state, Map<BlockState, Integer> known) {
        return known.computeIfAbsent(state, value -> {
            if (vanillaAir(value)) return 0;
            if (!BuiltInRegistries.BLOCK.getKey(value.getBlock()).getNamespace().equals("minecraft")
                    || !value.getFluidState().isEmpty() || value.getLightEmission(level, position) != 0
                    || value.useShapeForLightOcclusion() || value.getLightBlock(level, position) < 15) return -1;
            return 1;
        });
    }
    private static boolean vanillaAir(BlockState state) {
        return state.is(Blocks.AIR) || state.is(Blocks.CAVE_AIR) || state.is(Blocks.VOID_AIR);
    }

    /** Stops admission/control without blocking the server on a native worker. */
    public static void stop(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Stop requires server thread");
        for (Job job : List.copyOf(pending)) if (job.level.getServer() == server) {
            job.valid.set(false); job.finish(new IllegalStateException("Server stopped before solved light acknowledgement"));
        }
    }
    private static final class Job {
        final ServerLevel level; final LevelChunk chunk; final List<LevelChunk> ring; final long[] revisions;
        final Supplier<CompletableFuture<Void>> fallback;
        final AtomicBoolean valid = new AtomicBoolean(true), released = new AtomicBoolean();
        final CompletableFuture<Void> result = new CompletableFuture<>();
        Runnable unsubscribe = () -> {}; boolean closed, started; int refreshes; long reserved;
        ClearedChunkLighting.Snapshot input;
        Job(ServerLevel level, LevelChunk chunk, List<LevelChunk> ring, long[] revisions, Supplier<CompletableFuture<Void>> fallback) {
            this.level = level; this.chunk = chunk; this.ring = ring; this.revisions = revisions; this.fallback = fallback;
            // Reserve before any native barrier or detached snapshot is allocated.
            // Sixteen bytes per voxel covers the pure solver; another four bytes per
            // voxel reserves four neighbors x two light layers x 2048 bytes per section.
            reserved = level.getHeight() * 5120L;
            bytes.addAndGet(reserved);
        }
        boolean current() {
            if (!valid.get()) return false;
            for (int i = 0; i < ring.size(); i++) {
                LevelChunk current = ring.get(i);
                if (level.getChunkSource().getChunkNow(current.getPos().x, current.getPos().z) != current
                        || ChunkMutationTracking.revision(current) != revisions[i]) { valid.set(false); return false; }
            }
            return true;
        }
        FallbackReason refreshRing() {
            if (closed || started || input != null || refreshes != 0)
                throw new IllegalStateException("Only an uncaptured live light job may refresh once");
            List<LevelChunk> next = new ArrayList<>(9); long[] nextRevisions = new long[9];
            for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
                LevelChunk neighbor = level.getChunkSource().getChunkNow(chunk.getPos().x + x, chunk.getPos().z + z);
                if (neighbor == null || (x == 0 && z == 0 && neighbor != chunk)) return FallbackReason.MISSING_NEIGHBOR;
                long revision = ChunkMutationTracking.revision(neighbor);
                if (revision < 0 || revision == Long.MAX_VALUE) return FallbackReason.UNTRACKED_REVISION;
                nextRevisions[next.size()] = revision; next.add(neighbor);
            }
            ring.clear(); ring.addAll(next); System.arraycopy(nextRevisions, 0, revisions, 0, revisions.length);
            refreshes++; valid.set(true);
            return null;
        }
        synchronized void setInput(ClearedChunkLighting.Snapshot value) {
            if (released.get() || value.estimatedWorkingBytes() > reserved)
                throw new IllegalStateException("Solved-light snapshot exceeds its admission reservation");
            input = value;
        }
        synchronized ClearedChunkLighting.Snapshot takeInput() {
            if (closed || !valid.get()) { input = null; releaseInput(); return null; }
            started = true; ClearedChunkLighting.Snapshot value = input; input = null; return value;
        }
        synchronized void releaseInput() {
            input = null;
            if (released.compareAndSet(false, true)) bytes.addAndGet(-reserved);
        }
        void nativeFallback(FallbackReason reason) {
            if (closed) return;
            releaseInput();
            try { ClearedChunkLightBatch.fallback(fallback, reason).whenCompleteAsync((ignored, error) -> finish(error), level.getServer()); }
            catch (RuntimeException | LinkageError error) { finish(error); }
        }
        synchronized void finish(Throwable error) {
            if (closed) return; closed = true; unsubscribe.run(); pending.remove(this);
            if (!started) releaseInput();
            if (error == null) result.complete(null); else result.completeExceptionally(error);
        }
    }
    private ClearedChunkLightBatch() { }
}
