/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.voxelregion.forge.ChunkPacketQueue;
import org.voxelregion.forge.ForgeRegionEngine;
import org.voxelregion.forge.NativeAccess;
import org.voxelregion.forge.NativeCommitFences;

/** Internal bridge; integrations use ForgeRegionEngine rather than mutable runtime state. */
public final class RuntimeBridge {
    // Persisted names are intentionally independent of Java package or embedding mod id.
    static final String PERSISTENCE_NAMESPACE = "voxelregion_test";
    static final Logger LOGGER = LogUtils.getLogger();
    private static boolean installed;
    private static String registeredConfigFile;

    /** Marker supplied by the mandatory mutation mixin, checked before any operation is accepted. */
    public interface MutationTracked {}

    public static synchronized void install(IEventBus modBus, String configFile) {
        if (installed) {
            if (!configFile.equals(registeredConfigFile)) LOGGER.warn("VRE adapter is already installed with {}; ignoring registration of {}. Use the active config or remove the duplicate test/host adapter.", registeredConfigFile, configFile);
            return;
        }
        if (configFile.isBlank() || configFile.contains("/") || configFile.contains("\\"))
            throw new IllegalArgumentException("Expected a server config filename");
        if (ServerLifecycleHooks.getCurrentServer() != null)
            throw new IllegalStateException("Install the adapter during mod construction, before a server starts");
        modBus.addListener((RegisterCapabilitiesEvent event) -> event.register(ChunkReceipts.class));
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, AdapterConfig.SPEC, configFile);
        MinecraftForge.EVENT_BUS.addGenericListener(LevelChunk.class, ChunkReceipts::attach);
        MinecraftForge.EVENT_BUS.addListener(RuntimeBridge::tick);
        MinecraftForge.EVENT_BUS.addListener(RuntimeBridge::stop);
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerAboutToStartEvent event) -> org.voxelregion.forge.ServerTickTimings.reset());
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.TagsUpdatedEvent event) -> OperationOcclusion.rulesChanged());
        installed = true;
        registeredConfigFile = configFile;
    }

    private static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long deadline = sharedDeadline();
        org.voxelregion.forge.ClearedGeneration.publishReadyBatch(server, deadline);
        org.voxelregion.forge.ClearedInitialLighting.flush(server);
        NativeCommitFences.tick(server, deadline);
        ChunkPacketQueue.tick(server, AdapterConfig.PACKET_BYTES.get(), deadline);
        EntityOperationRunner.tick(server, deadline);
        ForgeOperationRunner.tick(server, deadline);
        org.voxelregion.forge.AirRegionLightBatch.flush(server);
    }
    static long sharedDeadline() {
        // Use this frame's actual remaining time. Including our previous work in an EMA
        // throttled the next frame again, even when its world work was already finished.
        long remaining = Math.max(100_000L, 45_000_000L - org.voxelregion.forge.ServerTickTimings.currentTickElapsedNanos());
        return System.nanoTime() + Math.min(remaining, (long)(AdapterConfig.MILLIS_PER_TICK.get() * 1_000_000));
    }
    private static void stop(ServerStoppingEvent event) {
        for (var level : event.getServer().getAllLevels()) org.voxelregion.forge.GenerationGate.stop(level);
        org.voxelregion.forge.ClearedChunkLightBatch.stop(event.getServer());
        org.voxelregion.forge.DetachedLightTasks.stop();
        org.voxelregion.forge.AirRegionLightBatch.stop();
        org.voxelregion.forge.ClearedInitialLighting.stop();
        org.voxelregion.forge.UniformSectionEncoding.clear();
        NativeCommitFences.stop(event.getServer());
        ChunkPacketQueue.stop();
        EntityOperationRunner.stop();
        ForgeOperationRunner.stop();
    }
    public static int maximumRadius() { return AdapterConfig.MAX_RADIUS.get(); }
    static String activeConfigFile() { return registeredConfigFile; }
    public static boolean exactNewChunkBiomes() { return AdapterConfig.EXACT_NEW_CHUNK_BIOMES.get(); }
    public static int ioThreads() { return AdapterConfig.IO_THREADS.get(); }
    public static int preparationThreads() { return AdapterConfig.PREPARATION_THREADS.get(); }

    /** Prevent a later neighboring fluid tick from refilling an already cleared part of a no-physics job. */
    public static boolean suppressFluidRefill(ServerLevel level, BlockPos position) {
        if (!level.getServer().isSameThread()) return false;
        var chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
        if (chunk == null) return false;
        for (var task : OperationData.get(level).tasks) {
            if (!task.active() || task.state == OperationData.State.FUSE || !task.clearFluids
                    || task.physics != AdapterConfig.Physics.NONE || !task.wholeChunk
                    || task.propagation != org.voxelregion.propagation.FrozenOcclusion.Mode.NONE
                    || !task.volume.contains(position.getX(), position.getY(), position.getZ())) continue;
            var receipt = ChunkReceipts.of(chunk).checkpoint(task.id);
            if (receipt != null && receipt.completedThroughPhase() >= 0
                    && chunk.getBlockState(position).isAir()) return true;
        }
        return false;
    }

    /** Native edge-only light recipients are insufficient after bulk writes omitted individual block packets. */
    public static boolean needsAuthoritativeLight(ServerLevel level, net.minecraft.world.level.ChunkPos position) {
        if (!level.getServer().isSameThread()) return false;
        var affected = new org.voxelregion.geometry.IntBox(position.getMinBlockX() - 15, level.getMinBuildHeight(),
                position.getMinBlockZ() - 15, position.getMaxBlockX() + 16, level.getMaxBuildHeight(), position.getMaxBlockZ() + 16);
        for (var task : OperationData.get(level).tasks) {
            if (!task.wholeChunk || task.state == OperationData.State.FUSE) continue;
            if (task.authoritativeLightThrough < level.getGameTime() && task.unconfirmedFences == 0 && !task.chunkStarted) continue;
            if (task.volume.classify(affected) != org.voxelregion.geometry.Coverage.OUTSIDE) return true;
        }
        return false;
    }

    /** Remaining adapter metadata allowance; admission must use the server-thread snapshot. */
    public static long availableMetadataBytes(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Metadata admission requires the server thread");
        return Math.max(0L, (long) AdapterConfig.MAX_PENDING_BYTES.get()
                - ForgeOperationRunner.estimatedMetadataBytes(server) - OperationOcclusion.estimatedBytes()
                - EntityOperationRunner.estimatedBytes());
    }

    private static void checkThread(ServerLevel level) {
        if (!installed) throw new IllegalStateException("ForgeRegionEngine.install must run during mod construction");
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Region API requires the server thread");
    }
    public static ForgeRegionEngine.Handle submitSphere(ServerLevel level, UUID actor, BlockPos center, int radius,
                                                         ForgeRegionEngine.Options options) throws IOException {
        checkThread(level);
        if (!MutationTracked.class.isAssignableFrom(LevelChunk.class)
                || !NativeAccess.ChunkSave.class.isAssignableFrom(ChunkMap.class)
                || !(level.getChunkSource().getLightEngine() instanceof NativeAccess.LightFence))
            throw new IllegalStateException("Required VRE mixins are absent; declare both supplied MixinConfigs before mod loading");
        var task = ForgeOperationRunner.submitOptions(level, actor, center, radius, options);
        return new ForgeRegionEngine.Handle(level.dimension().location(), task.id);
    }
    public static ForgeRegionEngine.Handle submit(ServerLevel level, UUID actor, org.voxelregion.forge.RegionGeometry geometry,
            ForgeRegionEngine.Options options) throws IOException {
        checkThread(level);
        if (!MutationTracked.class.isAssignableFrom(LevelChunk.class)
                || !NativeAccess.ChunkSave.class.isAssignableFrom(ChunkMap.class)
                || !(level.getChunkSource().getLightEngine() instanceof NativeAccess.LightFence))
            throw new IllegalStateException("Required VRE mixins are absent");
        var task = ForgeOperationRunner.submitGeometry(level, actor, geometry, options);
        return new ForgeRegionEngine.Handle(level.dimension().location(), task.id);
    }
    public static Optional<ForgeRegionEngine.Status> status(ServerLevel level, UUID id) {
        checkThread(level);
        return OperationData.get(level).tasks.stream().filter(task -> task.id.equals(id)).findFirst().map(task -> snapshot(level, task));
    }
    public static List<ForgeRegionEngine.Status> statuses(ServerLevel level) {
        checkThread(level);
        return OperationData.get(level).tasks.stream().map(task -> snapshot(level, task)).toList();
    }
    public static ForgeRegionEngine.ControlResult control(ServerLevel level, UUID id, ForgeRegionEngine.Control action) {
        checkThread(level);
        OperationData data = OperationData.get(level);
        var found = data.tasks.stream().filter(task -> task.id.equals(id)).findFirst();
        if (found.isEmpty()) return new ForgeRegionEngine.ControlResult(false, Optional.empty(), "Unknown region operation.");
        var task = found.get();
        if (ForgeOperationRunner.executingStep())
            return new ForgeRegionEngine.ControlResult(false, Optional.of(snapshot(level, task)),
                    "Control is unavailable during a synchronous region callback; queue it for a later server tick.");
        boolean applied;
        switch (action) {
            case RESUME -> applied = ForgeOperationRunner.resume(task);
            case CANCEL -> { applied = !task.terminal(); if (applied) ForgeOperationRunner.cancel(task); }
            case PAUSE -> {
                applied = !task.terminal() && task.state != OperationData.State.PAUSED_RECOVERY;
                if (applied) ForgeOperationRunner.pause(task, OperationData.State.PAUSED, "Paused by host.");
            }
            default -> throw new AssertionError(action);
        }
        if (applied) data.setDirty();
        return new ForgeRegionEngine.ControlResult(applied, Optional.of(snapshot(level, task)),
                applied ? "" : "Operation state does not allow this control; uncertain recovery cannot be forced.");
    }
    private static ForgeRegionEngine.Status snapshot(ServerLevel level, OperationData.Task task) {
        return new ForgeRegionEngine.Status(new ForgeRegionEngine.Handle(level.dimension().location(), task.id),
                ForgeRegionEngine.State.valueOf(task.state.name()), task.radius, task.phase, task.phaseCount(),
                task.visited, task.changed, task.kept, task.finishedChunks, task.skippedChunks, task.skippedUnloaded,
                task.bulkSections, task.clearFluids, task.message, ForgeOperationRunner.status(task).getString(), ForgeOperationRunner.progress(task));
    }
    private RuntimeBridge() {}
}
