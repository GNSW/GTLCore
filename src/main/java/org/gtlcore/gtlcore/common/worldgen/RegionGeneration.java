package org.gtlcore.gtlcore.common.worldgen;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

import org.voxelregion.forge.ForgeRegionEngine;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Keeps native generation dependencies while moving independent decoration work off its mailbox. */
public final class RegionGeneration {

    private static final ConcurrentHashMap<ServerLevel, World> WORLDS = new ConcurrentHashMap<>();
    private static final AtomicLong TRACKED = new AtomicLong();
    private static final AtomicLong FEATURE_REQUESTS = new AtomicLong();
    private static final AtomicLong FINISHED = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();
    private static final boolean DISABLED = Boolean.getBoolean("gtlcore.disableParallelChunkGeneration");

    private static final class World {

        final OrderedGenerationScheduler scheduler = new OrderedGenerationScheduler(
                Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 1)), 64);
        volatile boolean active;
        volatile boolean stopping;
    }

    public static void install() {
        MinecraftForge.EVENT_BUS.addListener(RegionGeneration::tick);
        MinecraftForge.EVENT_BUS.addListener(RegionGeneration::stopping);
        MinecraftForge.EVENT_BUS.addListener(RegionGeneration::stopped);
    }

    private static void tick(TickEvent.LevelTickEvent event) {
        if (DISABLED || event.phase != TickEvent.Phase.START || !(event.level instanceof ServerLevel level)) return;
        World world = WORLDS.computeIfAbsent(level, ignored -> new World());
        if (world.stopping) return;
        // Only the server thread reads the adapter's operation registry. Workers see a publication.
        world.active = ForgeRegionEngine.statuses(level).stream().anyMatch(status -> status.state() == ForgeRegionEngine.State.RUNNING ||
                status.state() == ForgeRegionEngine.State.SETTLING ||
                status.state() == ForgeRegionEngine.State.RECOVERING);
    }

    private static void stopping(ServerStoppingEvent event) {
        WORLDS.forEach((level, world) -> {
            if (level.getServer() == event.getServer()) {
                world.stopping = true;
                world.active = false;
                world.scheduler.close();
            }
        });
    }

    private static void stopped(ServerStoppedEvent event) {
        WORLDS.keySet().removeIf(level -> level.getServer() == event.getServer());
    }

    public static <T> CompletableFuture<T> generate(ServerLevel level, ChunkStatus status,
                                                    List<ChunkAccess> chunks, Executor executor,
                                                    Supplier<CompletableFuture<T>> operation) {
        // FULL fires mod callbacks which may synchronously request neighbors. A lease through
        // those callbacks would deadlock against the neighbor's earlier generation stages.
        if (DISABLED || status == ChunkStatus.FULL || chunks.isEmpty()) return operation.get();
        World world = WORLDS.computeIfAbsent(level, ignored -> new World());
        boolean offload = status == ChunkStatus.FEATURES && world.active && !world.stopping &&
                WorldgenSharedState.isInitialized(level);
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (ChunkAccess chunk : chunks) {
            var pos = chunk.getPos();
            minX = Math.min(minX, pos.x);
            minZ = Math.min(minZ, pos.z);
            maxX = Math.max(maxX, pos.x);
            maxZ = Math.max(maxZ, pos.z);
        }
        if (status == ChunkStatus.LIGHT && chunks.size() == 1) {
            // Loading supplies only its center; native lighting still accesses neighboring columns.
            minX--;
            minZ--;
            maxX++;
            maxZ++;
        }
        TRACKED.incrementAndGet();
        if (offload) FEATURE_REQUESTS.incrementAndGet();
        return world.scheduler.submit(level, new OrderedGenerationScheduler.ChunkArea(minX, minZ, maxX, maxZ),
                executor, offload, operation).whenComplete((result, failure) -> {
                    FINISHED.incrementAndGet();
                    if (failure != null) FAILURES.incrementAndGet();
                });
    }

    public record Statistics(long tracked, long featureRequests, long finished, long failures,
                             long offloaded, long delayed, long nativeFallbacks, long activeOffloads,
                             long peakOffloads, long waiting) {}

    public static Statistics statistics() {
        long offloaded = 0, delayed = 0, nativeFallbacks = 0, active = 0, peak = 0, waiting = 0;
        for (World world : WORLDS.values()) {
            var stats = world.scheduler.statistics();
            offloaded += stats.offloaded();
            delayed += stats.delayed();
            nativeFallbacks += stats.nativeFallbacks();
            active += stats.activeOffloads();
            peak += stats.peakOffloads();
            waiting += stats.waiting();
        }
        return new Statistics(TRACKED.get(), FEATURE_REQUESTS.get(), FINISHED.get(), FAILURES.get(),
                offloaded, delayed, nativeFallbacks, active, peak, waiting);
    }

    private RegionGeneration() {}
}
