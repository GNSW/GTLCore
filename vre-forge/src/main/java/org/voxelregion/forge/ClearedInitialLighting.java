/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Transient proof for newly materialized open columns; saved markers never authorize this path. */
public final class ClearedInitialLighting {
    private static final ConcurrentHashMap<ChunkAccess, Plan> plans = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Request> ready = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<ServerLevel, AtomicBoolean> wakes = new ConcurrentHashMap<>();
    private static final java.util.Set<Request> active = ConcurrentHashMap.newKeySet();
    private static long columns;

    static void register(ServerLevel level, ChunkAccess chunk) {
        if (!HostIntegration.supportsBulk(level)) return;
        if (((NativeAccess.LightFence) level.getChunkSource().getLightEngine()).vre$rebuildsWholeChunks()) return;
        int floor = AirRegionLightBatch.floor(level, chunk);
        if (floor != Integer.MAX_VALUE && plans.size() < 4096) plans.put(chunk, new Plan(level, floor));
    }

    /** Called by native INITIALIZE_LIGHT; only enables the freshly proved column's light storage. */
    public static boolean initializing(ChunkAccess chunk) { return plans.containsKey(chunk); }

    /** Native LIGHT still owns its final flag and ticket; it runs after the actual batch publication. */
    public static CompletableFuture<ChunkAccess> light(ChunkAccess chunk,
            Function<Boolean, CompletableFuture<ChunkAccess>> nativeLight) {
        Plan plan = plans.remove(chunk);
        if (plan == null) return null;
        var request = new Request(chunk, plan, nativeLight);
        active.add(request);
        request.result.whenComplete((value, error) -> active.remove(request));
        ready.add(request);
        var wake = wakes.computeIfAbsent(plan.level, ignored -> new AtomicBoolean());
        if (wake.compareAndSet(false, true)) {
            ((GenerationBridge) plan.level.getChunkSource().chunkMap).vre$generationExecutor().execute(() -> {
                wake.set(false); flush(plan.level.getServer());
            });
        }
        return request.result;
    }

    public static void flush(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Initial lighting requires the server owner");
        var groups = new java.util.IdentityHashMap<ServerLevel, List<Request>>();
        for (int i = 0; i < 512; i++) {
            var request = ready.poll(); if (request == null) break;
            if (request.result.isDone()) continue;
            var level = request.plan.level;
            if (level.getServer() != server) { request.result.completeExceptionally(new IllegalStateException("Server stopped")); continue; }
            if (AirRegionLightBatch.floor(level, request.chunk) != request.plan.firstAirY) {
                finishNative(request, false); continue;
            }
            groups.computeIfAbsent(level, ignored -> new ArrayList<>()).add(request);
        }
        for (var entry : groups.entrySet()) {
            var level = entry.getKey(); var requests = List.copyOf(entry.getValue());
            var positions = requests.stream().map(r -> new NativeAccess.OpenColumn(r.chunk.getPos(), r.plan.firstAirY)).toList();
            CompletableFuture<Boolean> publication;
            try {
                publication = ((NativeAccess.LightFence) level.getChunkSource().getLightEngine()).vre$clearAirRegion(
                        positions, level.getMinSection(), level.getSectionsCount(), level.dimensionType().hasSkyLight(), () -> true);
            } catch (RuntimeException | LinkageError error) { publication = CompletableFuture.failedFuture(error); }
            publication.whenCompleteAsync((applied, error) -> {
                for (var request : requests) {
                    if (error != null) request.result.completeExceptionally(error);
                    else { if (Boolean.TRUE.equals(applied)) columns++; finishNative(request, Boolean.TRUE.equals(applied)); }
                }
            }, ((GenerationBridge)level.getChunkSource().chunkMap).vre$generationExecutor());
        }
    }

    private static void finishNative(Request request, boolean installed) {
        if (request.result.isDone()) return;
        try {
            request.nativeLight.apply(installed).whenComplete((chunk, error) -> {
                if (error != null) request.result.completeExceptionally(error); else request.result.complete(chunk);
            });
        } catch (RuntimeException | LinkageError error) { request.result.completeExceptionally(error); }
    }

    public static long columns() { return columns; }
    static void release(java.util.UUID operation) {
        plans.keySet().removeIf(chunk -> chunk instanceof NativeAccess.ClearedTerrain terrain
                && terrain.vre$clearedTerrain().hasUUID("operation")
                && terrain.vre$clearedTerrain().getUUID("operation").equals(operation));
    }
    public static void stop() {
        var error = new IllegalStateException("Server stopped during initial lighting publication");
        for (var request : active) request.result.completeExceptionally(error);
        ready.clear(); plans.clear(); wakes.clear();
    }
    private record Plan(ServerLevel level, int firstAirY) { }
    private static final class Request {
        final ChunkAccess chunk; final Plan plan; final Function<Boolean, CompletableFuture<ChunkAccess>> nativeLight;
        final CompletableFuture<ChunkAccess> result = new CompletableFuture<>();
        Request(ChunkAccess chunk, Plan plan, Function<Boolean, CompletableFuture<ChunkAccess>> nativeLight) {
            this.chunk = chunk; this.plan = plan; this.nativeLight = nativeLight;
        }
    }
    private ClearedInitialLighting() { }
}
