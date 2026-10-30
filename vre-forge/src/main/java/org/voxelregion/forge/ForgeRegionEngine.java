/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.IEventBus;
import org.voxelregion.forge.runtime.RuntimeBridge;

/**
 * Server-thread entry point for the Forge 1.20.1 adapter. Call install from a host
 * mod constructor. The host must also declare both supplied mixin configurations
 * in its jar manifest; installing listeners cannot retrofit missing native hooks.
 * Calls are privileged host actions: the embedding mod authenticates its callers.
 */
public final class ForgeRegionEngine {
    public enum State { FUSE, RUNNING, SETTLING, RECOVERING, PAUSED, PAUSED_UNLOADED,
        PAUSED_PROTECTION, PAUSED_RECOVERY, FAILED, CANCELLED, WRITTEN }
    public enum Control { PAUSE, RESUME, CANCEL }

    /** Server policy (protection, physics and budgets) is captured or consulted by the adapter. */
    public record Options(int fuseTicks, boolean generateMissingChunks, boolean clearFluids, boolean destroyBedrock) {
        public static final Options DEFAULT = new Options(0, true, true, false);
        /** Existing callers and charges retain bedrock protection. */
        public Options(int fuseTicks, boolean generateMissingChunks, boolean clearFluids) {
            this(fuseTicks, generateMissingChunks, clearFluids, false);
        }
        public Options {
            if (fuseTicks < 0 || fuseTicks > 1200) throw new IllegalArgumentException("Fuse must be 0..1200 ticks");
        }
    }
    public record Handle(ResourceLocation dimension, UUID id) {
        public Handle { Objects.requireNonNull(dimension); Objects.requireNonNull(id); }
    }
    /** Actual bounded stage counts; pending stages may overlap and do not imply a known total or percentage. */
    public record Progress(String preparationStage, int writingChunks, int loadingChunks,
                           int pendingLighting, int pendingSaves, int pendingPackets, int retainedChunks) {}
    /** Detached immutable observation. WRITTEN includes the native light/save acknowledgement. */
    public record Status(Handle handle, State state, int radius, long phase, long phaseCount,
                         long visited, long changed, long kept, long completedChunkPhases,
                         long deniedChunks, long unloadedChunks, long bulkSections,
                         boolean clearFluids, String message, String description, Progress progress) {
        public boolean terminal() { return state == State.CANCELLED || state == State.FAILED || state == State.WRITTEN; }
        public Component asComponent() { return Component.literal(description); }
    }
    public record ControlResult(boolean applied, Optional<Status> status, String reason) {}

    /** Idempotent for multiple calls. Retains the existing test adapter's config filename. */
    public static void install(IEventBus modBus) { install(modBus, "voxelregion_test-server.toml"); }
    public static void install(IEventBus modBus, String serverConfigFile) {
        RuntimeBridge.install(Objects.requireNonNull(modBus), Objects.requireNonNull(serverConfigFile));
    }
    public static int maximumRadius() { return RuntimeBridge.maximumRadius(); }

    /** Acceptance returns only after the immutable intent has been written durably. */
    public static Handle submitSphere(ServerLevel level, UUID actor, BlockPos center, int radius,
                                      Options options) throws IOException {
        return RuntimeBridge.submitSphere(Objects.requireNonNull(level), Objects.requireNonNull(actor),
                Objects.requireNonNull(center).immutable(), radius, Objects.requireNonNull(options));
    }
    public static Handle submit(ServerLevel level, UUID actor, RegionGeometry geometry, Options options) throws IOException {
        return RuntimeBridge.submit(Objects.requireNonNull(level), Objects.requireNonNull(actor),
                Objects.requireNonNull(geometry), Objects.requireNonNull(options));
    }
    public static Optional<Status> status(ServerLevel level, UUID id) {
        return RuntimeBridge.status(Objects.requireNonNull(level), Objects.requireNonNull(id));
    }
    public static List<Status> statuses(ServerLevel level) { return RuntimeBridge.statuses(Objects.requireNonNull(level)); }
    /**
     * Server-thread, read-only diagnostics for the chunk containing {@code position}.
     * Never loads a chunk or serializes terrain. The detached human-readable lines are
     * diagnostic text, not a stable machine-parsing API. Missing evidence is not a skip.
     */
    public static List<String> inspectChunk(ServerLevel level, BlockPos position) {
        return RuntimeBridge.inspectChunk(Objects.requireNonNull(level), Objects.requireNonNull(position).immutable());
    }
    /**
     * Recovery-paused work cannot be resumed without valid co-saved evidence.
     * Synchronous region callbacks receive applied=false and a reason; queue their controls for a later server tick.
     */
    public static ControlResult control(ServerLevel level, UUID id, Control action) {
        return RuntimeBridge.control(Objects.requireNonNull(level), Objects.requireNonNull(id), Objects.requireNonNull(action));
    }
    private ForgeRegionEngine() {}
}
