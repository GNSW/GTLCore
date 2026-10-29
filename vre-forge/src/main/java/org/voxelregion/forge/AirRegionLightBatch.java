/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import org.voxelregion.forge.runtime.ChunkMutationTracking;

/** Open columns, optionally over a uniform bedrock floor, share one light publication and exterior repair. */
public final class AirRegionLightBatch {
    private static final Map<ServerLevel, LinkedHashMap<LevelChunk, Request>> queued = new IdentityHashMap<>();
    private static final Set<Cohort> active = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Request> repairing = Collections.newSetFromMap(new IdentityHashMap<>());
    private static long batches, columns, nextSolutionGroup;
    private static int retained;

    /** Null leaves nonuniform/protected terrain with the general solver. */
    public static CompletableFuture<Void> tryQueue(ServerLevel level, LevelChunk chunk,
            Supplier<CompletableFuture<Void>> fallback) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Air proof requires the server owner");
        if (retained >= 4096 || !HostIntegration.supportsBulk(level)) return null;
        int firstAirY = floor(level, chunk);
        BitSet opaque = null;
        if (firstAirY == Integer.MAX_VALUE) {
            opaque = bedrockMask(chunk);
            if (opaque == null) return null;
            firstAirY = level.getMinBuildHeight() + ((opaque.length() + 255) / 256);
        }
        long revision = ChunkMutationTracking.revision(chunk);
        if (revision < 0 || revision == Long.MAX_VALUE) return null;
        var requests = queued.computeIfAbsent(level, ignored -> new LinkedHashMap<>());
        if (requests.containsKey(chunk)) return null;
        var request = new Request(chunk, revision, firstAirY, fallback);
        if (opaque != null) {
            int bandHeight = Math.max(16, ((opaque.length() + 4095) / 4096) * 16);
            long reservation = 65_536L + level.getHeight() * 256L + bandHeight * 1536L;
            if (reservation > org.voxelregion.forge.runtime.RuntimeBridge.availableMetadataBytes(level.getServer())) return null;
            request.bytes = reservation;
            pendingBytes += reservation;
            request.opaque = opaque;
            request.solved = new CompletableFuture<>();
        }
        requests.put(chunk, request); retained++;
        return request.result;
    }

    public static void flush(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Light cohort requires the server owner");
        for (var entry : new ArrayList<>(queued.entrySet())) {
            var level = entry.getKey();
            if (level.getServer() != server) continue;
            dispatchRegions(level, entry.getValue().values());
            var valid = new ArrayList<Request>();
            var iterator = entry.getValue().values().iterator();
            while (iterator.hasNext()) {
                var request = iterator.next();
                if (request.calculation != null && !request.calculation.ready.isDone()) continue;
                if (!request.solved.isDone()) continue;
                iterator.remove();
                if (request.solved.isCompletedExceptionally()) {
                    try { request.solved.join(); } catch (RuntimeException failure) { finish(request, failure); }
                    continue;
                }
                if (ChunkMutationTracking.revision(request.chunk) != request.revision
                        || request.calculation != null && !request.calculation.validate(level)) repair(request);
                else valid.add(request);
            }
            if (entry.getValue().isEmpty()) queued.remove(level);
            if (valid.isEmpty()) continue;
            var cohort = new Cohort(level, List.copyOf(valid)); active.add(cohort);
            Set<LevelChunk> chunks = Collections.newSetFromMap(new IdentityHashMap<>());
            valid.forEach(request -> chunks.add(request.chunk));
            cohort.unsubscribe = ChunkMutationTracking.subscribe(level, (chunk, revision) -> {
                if (chunks.contains(chunk)) cohort.valid.set(false);
            });
            var positions = valid.stream().map(request -> new NativeAccess.OpenColumn(request.chunk.getPos(), request.firstAirY, request.solved.join(), request.solutionGroup)).toList();
            CompletableFuture<Boolean> publication;
            try {
                publication = ((NativeAccess.LightFence) level.getChunkSource().getLightEngine()).vre$clearAirRegion(
                        positions, level.getMinSection(), level.getSectionsCount(), level.dimensionType().hasSkyLight(), cohort::isValid);
            } catch (RuntimeException | LinkageError error) { publication = CompletableFuture.failedFuture(error); }
            publication.whenCompleteAsync((applied, error) -> {
                        if (cohort.closed) return;
                        cohort.closed = true; cohort.unsubscribe.run(); active.remove(cohort);
                        if (error == null && Boolean.TRUE.equals(applied) && cohort.isValid()) {
                            batches++; columns += valid.size();
                            for (var request : valid) finish(request, null);
                        } else {
                            for (var request : valid) {
                                if (error != null) finish(request, error);
                                else repair(request);
                            }
                        }
                    }, server);
        }
    }
    private static void dispatchRegions(ServerLevel level, Collection<Request> requests) {
        Map<RegionBand, List<Request>> groups = new LinkedHashMap<>();
        for (var request : requests) if (request.opaque != null && request.solutionGroup == 0) {
            var pos = request.chunk.getPos();
            // Keep allocation bounded by each request's reserved band; a high retained
            // ceiling must not inflate fifteen short columns after admission.
            int band = Math.max(16, ((request.opaque.length() + 4095) / 4096) * 16);
            groups.computeIfAbsent(new RegionBand(pos.x >> 4, pos.z >> 4, band), ignored -> new ArrayList<>()).add(request);
        }
        final int height = level.getHeight(); final boolean sky = level.dimensionType().hasSkyLight();
        for (var group : groups.values()) {
            long id = ++nextSolutionGroup;
            var calculation = new Calculation(level, List.copyOf(group));
            Map<Integer, BitSet> masks = new HashMap<>();
            Map<Integer, CompletableFuture<org.voxelregion.lighting.ClearedChunkLighting.Result>> outputs = new HashMap<>();
            for (var request : group) {
                var pos = request.chunk.getPos(); int slot = (pos.x & 15) | (pos.z & 15) << 4;
                masks.put(slot, request.opaque); request.opaque = null; request.solutionGroup = id;
                request.calculation = calculation;
                outputs.put(slot, request.solved);
            }
            try {
                CompletableFuture.supplyAsync(() -> org.voxelregion.lighting.OpenRegionLighting.solve(height, 16, masks, sky),
                        RegionPreparationPool.executor()).whenComplete((result, failure) -> {
                            outputs.forEach((slot, future) -> {
                                if (failure == null) future.complete(result.get(slot)); else future.completeExceptionally(failure);
                            });
                            calculation.ready.complete(null);
                        });
            } catch (RuntimeException failure) {
                outputs.values().forEach(future -> future.completeExceptionally(failure)); calculation.ready.complete(null);
            }
        }
    }
    private record RegionBand(int x, int z, int height) { }
    static int floor(net.minecraft.world.level.LevelHeightAccessor level, net.minecraft.world.level.chunk.ChunkAccess chunk) {
        int firstAirY = level.getMinBuildHeight(); boolean open = false;
        for (var section : chunk.getSections()) {
            if (onlyAir(section)) { open = true; continue; }
            if (open || !onlyAirAndBedrock(section))
                return Integer.MAX_VALUE;
            int count = ((NativeAccess.SectionStats)section).vre$accurateNonAirCount();
            if ((count & 255) != 0) return Integer.MAX_VALUE;
            if (count < 4096) {
                // Count plus a proven solid prefix implies that every later cell is AIR.
                for (int index = 0; index < count; index++)
                    if (section.getBlockState(index & 15, index >>> 8, (index >>> 4) & 15) != Blocks.BEDROCK.defaultBlockState())
                        return Integer.MAX_VALUE;
                open = true;
            }
            firstAirY += count / 256;
        }
        return firstAirY;
    }
    /** Ordinary irregular bedrock needs only its retained bit mask, never a full-height voxel/light scan. */
    private static BitSet bedrockMask(LevelChunk chunk) {
        BitSet opaque = new BitSet();
        int offset = 0;
        for (var section : chunk.getSections()) {
            if (onlyAir(section)) { offset += 4096; continue; }
            if (!onlyAirAndBedrock(section)) return null;
            for (int i = 0; i < 4096; i++)
                if (section.getBlockState(i & 15, i >>> 8, (i >>> 4) & 15) == Blocks.BEDROCK.defaultBlockState()) opaque.set(offset + i);
            offset += 4096;
        }
        return opaque;
    }
    private static boolean onlyAir(net.minecraft.world.level.chunk.LevelChunkSection section) {
        if (!section.hasOnlyAir()) return false;
        if (!section.maybeHas(state -> !air(state))) return true;
        // Native remainder deletion leaves unused lava/emitter IDs in the palette.
        // Inspect actual storage IDs only for ambiguous palettes; custom air remains excluded.
        boolean[] other = { false };
        section.getStates().getAll(state -> { if (!air(state)) other[0] = true; });
        return !other[0];
    }
    private static boolean onlyAirAndBedrock(net.minecraft.world.level.chunk.LevelChunkSection section) {
        if (!section.maybeHas(state -> !air(state) && state != Blocks.BEDROCK.defaultBlockState())) return true;
        boolean[] other = { false };
        section.getStates().getAll(state -> { if (!air(state) && state != Blocks.BEDROCK.defaultBlockState()) other[0] = true; });
        return !other[0];
    }
    private static boolean air(net.minecraft.world.level.block.state.BlockState state) {
        return state == Blocks.AIR.defaultBlockState() || state == Blocks.CAVE_AIR.defaultBlockState()
                || state == Blocks.VOID_AIR.defaultBlockState();
    }
    private static void repair(Request request) {
        repairing.add(request);
        // A stale whole-air image can have acquired a new emitter/protected voxel. Repair the
        // complete current column before the original changed-cell fallback can acknowledge it.
        var level = (ServerLevel) request.chunk.getLevel();
        try {
        ((NativeAccess.LightFence) level.getChunkSource().getLightEngine()).vre$fullChecks(request.chunk.getPos(),
                level.getMinSection(), level.getSectionsCount()).thenComposeAsync(ignored -> request.fallback.get(), level.getServer())
                .whenCompleteAsync((ignored, failure) -> {
                    repairing.remove(request); finish(request, failure);
                }, level.getServer());
        } catch (RuntimeException | LinkageError failure) {
            repairing.remove(request); finish(request, failure);
        }
    }
    private static void finish(Request request, Throwable failure) {
        if (request.closed) return;
        request.closed = true; retained--; pendingBytes -= request.bytes;
        if (request.calculation != null) request.calculation.release();
        if (failure == null) request.result.complete(null); else request.result.completeExceptionally(failure);
    }
    public static long batches() { return batches; }
    public static long columns() { return columns; }
    private static long pendingBytes;
    public static long estimatedBytes() { return retained * 512L + pendingBytes; }
    public static void stop() {
        var error = new IllegalStateException("Server stopped during batched light publication");
        for (var requests : queued.values()) for (var request : requests.values()) finish(request, error);
        for (var cohort : active) {
            cohort.closed = true; cohort.valid.set(false); cohort.unsubscribe.run();
            for (var request : cohort.requests) finish(request, error);
        }
        for (var request : repairing) finish(request, error);
        queued.clear(); active.clear(); repairing.clear(); retained = 0;
    }
    private static final class Request {
        final LevelChunk chunk; final long revision; final int firstAirY; final Supplier<CompletableFuture<Void>> fallback;
        final CompletableFuture<Void> result = new CompletableFuture<>();
        CompletableFuture<org.voxelregion.lighting.ClearedChunkLighting.Result> solved = CompletableFuture.completedFuture(null);
        long bytes;
        BitSet opaque;
        long solutionGroup;
        Calculation calculation;
        boolean closed;
        Request(LevelChunk chunk, long revision, int firstAirY, Supplier<CompletableFuture<Void>> fallback) {
            this.chunk = chunk; this.revision = revision; this.firstAirY = firstAirY; this.fallback = fallback;
        }
    }
    private static final class Cohort {
        final ServerLevel level; final List<Request> requests; final AtomicBoolean valid = new AtomicBoolean(true);
        Runnable unsubscribe = () -> {}; boolean closed;
        Cohort(ServerLevel level, List<Request> requests) { this.level = level; this.requests = requests; }
        boolean isValid() {
            if (!valid.get()) return false;
            for (var request : requests) if (request.calculation != null && !request.calculation.valid.get()) return false;
            return true;
        }
    }
    /** Joint solutions depend on every captured member, including changes before workers finish. */
    private static final class Calculation {
        final List<Request> members;
        final AtomicBoolean valid = new AtomicBoolean(true);
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final Runnable unsubscribe;
        int remaining, checkedTick = Integer.MIN_VALUE;
        Calculation(ServerLevel level, List<Request> members) {
            this.members = members; remaining = members.size();
            Set<LevelChunk> dependencies = Collections.newSetFromMap(new IdentityHashMap<>());
            members.forEach(request -> dependencies.add(request.chunk));
            unsubscribe = ChunkMutationTracking.subscribe(level, (chunk, revision) -> {
                if (dependencies.contains(chunk)) valid.set(false);
            });
        }
        boolean validate(ServerLevel level) {
            if (!valid.get()) return false;
            int tick = level.getServer().getTickCount();
            if (checkedTick == tick) return true;
            checkedTick = tick;
            for (var request : members) {
                var pos = request.chunk.getPos();
                if (level.getChunkSource().getChunkNow(pos.x, pos.z) != request.chunk
                        || ChunkMutationTracking.revision(request.chunk) != request.revision) {
                    valid.set(false); return false;
                }
            }
            return true;
        }
        void release() { if (--remaining == 0) { valid.set(false); unsubscribe.run(); } }
    }
    private AirRegionLightBatch() { }
}
