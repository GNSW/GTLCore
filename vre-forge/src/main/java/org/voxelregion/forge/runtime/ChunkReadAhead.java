/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.voxelregion.forge.GenerationBridge;
import org.voxelregion.forge.NativeAccess;
import org.voxelregion.runtime.PreparationWindow;
import org.voxelregion.spatial.CenterOutChunkCursor;

/** Bounded native loading pipeline. Actual terrain commits still consume the authoritative center-out cursor. */
final class ChunkReadAhead {
    private static final TicketType<UUID> TICKET = TicketType.create("vre_read_ahead", Comparator.<UUID>naturalOrder());
    private static final Map<UUID, Window> windows = new HashMap<>();
    private static int retained;

    static void fill(ServerLevel level, OperationData.Task task, long deadline) {
        if (!task.generate || !task.wholeChunk || task.centerCursor == null || task.replayOffset < task.cursorOffset
                || task.propagation != org.voxelregion.propagation.FrozenOcclusion.Mode.NONE
                || AdapterConfig.READ_AHEAD_CHUNKS.get() == 0) return;
        var window = windows.computeIfAbsent(task.id, ignored -> new Window(level,
                CenterOutChunkCursor.restore(task.volume, org.voxelregion.geometry.Point3.of(task.cx, task.cy, task.cz),
                        level.getMinBuildHeight(), level.getMaxBuildHeight(), task.centerCursor.checkpoint()), task.cursorOffset));
        discardPassed(window, task);
        int maximum = Math.min(AdapterConfig.READ_AHEAD_CHUNKS.get(), AdapterConfig.MAX_IN_FLIGHT_CHUNKS.get() / 2);
        // FULL alone leaves fluid/shape/upgrade post-processing dormant. Warm that
        // lifecycle ahead of the commit cursor too, without blocking the server or
        // promoting the whole configured read window to ticking in one frame.
        prepareFrame(window, task, deadline, maximum);
        // A loaded fast path can outrun this frame's bounded ticket submissions.
        // Resume from the authoritative cursor instead of leasing already committed chunks.
        if (window.submissions > 0 && System.nanoTime() < window.frameDeadline && window.cursorOffset < task.cursorOffset) {
            window.cursor = CenterOutChunkCursor.restore(task.volume, org.voxelregion.geometry.Point3.of(task.cx, task.cy, task.cz),
                    level.getMinBuildHeight(), level.getMaxBuildHeight(), task.centerCursor.checkpoint());
            window.cursorOffset = task.cursorOffset;
        }
        while (window.positions.size() < window.target && window.submissions > 0
                && System.nanoTime() < window.frameDeadline
                && RuntimeBridge.availableMetadataBytes(level.getServer()) >= 1024
                && ForgeOperationRunner.retainedChunks() < AdapterConfig.MAX_IN_FLIGHT_CHUNKS.get() - 1
                && window.cursor.hasNext()) {
            window.submissions--;
            var key = window.cursor.next();
            window.cursorOffset++;
            if (task.excludedChunks.contains(ChunkPos.asLong(key.x(), key.z()))
                    || ClaimChecks.check(level, key.x(), key.z(), task.protectEveryClaim) != ClaimChecks.Decision.ALLOW) continue;
            var pos = new ChunkPos(key.x(), key.z());
            // Ticket submission is nonblocking. Never call getChunk/getChunkFuture here: those
            // may managedBlock and turn the preparation window back into serial world generation.
            level.getChunkSource().addRegionTicket(TICKET, pos, 0, task.id);
            window.positions.put(pos, new Read(window.cursorOffset)); retained++; window.waiting++;
        }
    }

    private static void discardPassed(Window window, OperationData.Task task) {
        var iterator = window.positions.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().ordinal >= task.cursorOffset) break;
            // Exclusions/protection changes may skip a prefetched position without
            // adopting it. The current position keeps its lease until runner handoff.
            window.level.getChunkSource().removeRegionTicket(TICKET, entry.getKey(), entry.getValue().distance, task.id);
            if (entry.getValue().ready) window.ready = Math.max(0, window.ready - 1);
            else window.waiting = Math.max(0, window.waiting - 1);
            iterator.remove(); retained--;
        }
    }

    static boolean pendingPostProcessing(LevelChunk chunk) {
        if (!chunk.getUpgradeData().isEmpty()) return true;
        for (var section : chunk.getPostProcessing()) if (section != null && !section.isEmpty()) return true;
        UpgradeData upgrade = chunk.getUpgradeData();
        if (upgrade == UpgradeData.EMPTY) return false;
        // isEmpty() checks indices/sides only, not saved neighbor block/fluid ticks. A separate
        // UpgradeData object needs the real native lifecycle even if that predicate says empty.
        // Native upgrade() leaves its neighbor-tick lists intact. Its successful RETURN records
        // the exact processed identity on this chunk, independent of ticket/future transitions.
        return !(chunk instanceof NativeAccess.ChunkPostProcessing completed && completed.vre$completedUpgrade(upgrade));
    }

    private static void prepareFrame(Window window, OperationData.Task task, long deadline, int maximum) {
        int tick = window.level.getServer().getTickCount();
        if (window.checkedTick == tick) return;
        window.checkedTick = tick;
        long now = System.nanoTime(), headroom = Math.max(0L, deadline - now);
        var source = window.level.getChunkSource();
        var bridge = (GenerationBridge) source.chunkMap;
        var head = source.getChunkNow(task.chunkX, task.chunkZ);
        boolean headWaiting = head == null || pendingPostProcessing(head);
        var decision = window.policy.observe(window.newlyReady, window.consumed, window.ready, window.waiting,
                headWaiting, ForgeOperationRunner.pendingCommits(), ForgeOperationRunner.admissionWindow(),
                headroom, maximum, window.sampleComplete);
        window.target = decision.target();
        window.submissions = decision.submissions();
        window.newlyReady = window.consumed = 0;
        // Maintenance shares the operation deadline and has its own small per-frame
        // slice, so a larger read window cannot consume the entire commit budget.
        window.frameDeadline = Math.min(deadline, now + Math.min(2_000_000L, Math.max(100_000L, headroom / 4)));
        int promotions = decision.promotions(), ready = 0, waiting = 0;
        boolean complete = true;
        for (var entry : window.positions.entrySet()) {
            if (System.nanoTime() >= window.frameDeadline) { complete = false; break; }
            var pos = entry.getKey();
            var read = entry.getValue();
            LevelChunk chunk = source.getChunkNow(pos.x, pos.z);
            boolean pending = chunk != null && pendingPostProcessing(chunk);
            boolean deferred = pending && read.distance == 0 && canDeferPostProcessing(window, task, read, chunk);
            boolean usable = chunk != null && (!pending || deferred);
            if (read.distance != 0) {
                usable = usable && bridge.vre$postProcessingReady(chunk);
                if (usable) {
                    source.addRegionTicket(TICKET, pos, 0, task.id);
                    source.removeRegionTicket(TICKET, pos, 1, task.id);
                    read.distance = 0;
                }
            } else if (promotions > 0 && chunk != null && !usable
                    && ClaimChecks.check(window.level, pos.x, pos.z, task.protectEveryClaim) == ClaimChecks.Decision.ALLOW) {
                source.addRegionTicket(TICKET, pos, 1, task.id);
                source.removeRegionTicket(TICKET, pos, 0, task.id);
                read.distance = 1; promotions--;
            }
            if (usable && !read.ready) window.newlyReady++;
            read.ready = usable;
            if (usable) ready++; else waiting++;
        }
        window.sampleComplete = complete;
        if (complete) { window.ready = ready; window.waiting = waiting; }
    }

    /** A bounded speculative cache only. The runner captures and validates its own authoritative token. */
    private static boolean canDeferPostProcessing(Window window, OperationData.Task task, Read read, LevelChunk chunk) {
        long revision = ChunkMutationTracking.revision(chunk);
        int metadataSize = 1;
        for (var list : chunk.getPostProcessing()) metadataSize = 31 * metadataSize + (list == null ? -1 : list.size());
        if (read.proofChunk != chunk || read.proofRevision != revision || read.proofMetadataSize != metadataSize) {
            read.proofChunk = chunk; read.proofRevision = revision; read.proofMetadataSize = metadataSize;
            read.canDefer = ForgeOperationRunner.postProcessingClearance(window.level, task, chunk) != null;
        }
        return read.canDefer;
    }

    static int ticketDistance(UUID operation, ChunkPos pos) {
        var window = windows.get(operation);
        var read = window == null ? null : window.positions.get(pos);
        return read == null ? 0 : read.distance;
    }

    /** Transfer to the runner's own ticket before releasing the prefetch lease. */
    static void adopted(UUID operation, ChunkPos pos) {
        var window = windows.get(operation);
        Read read = window == null ? null : window.positions.remove(pos);
        if (read != null) {
            window.level.getChunkSource().removeRegionTicket(TICKET, pos, read.distance, operation);
            window.consumed++;
            if (read.ready) window.ready = Math.max(0, window.ready - 1);
            else window.waiting = Math.max(0, window.waiting - 1);
            retained--;
        }
    }

    static void release(UUID operation) {
        var window = windows.remove(operation);
        if (window == null) return;
        for (var entry : window.positions.entrySet())
            window.level.getChunkSource().removeRegionTicket(TICKET, entry.getKey(), entry.getValue().distance, operation);
        retained -= window.positions.size();
    }
    static int retained() { return retained; }
    static int retained(UUID id) { var window = windows.get(id); return window == null ? 0 : window.positions.size(); }
    static int loading(UUID id) {
        var window = windows.get(id); if (window == null) return 0;
        int count = 0;
        for (var entry : window.positions.entrySet()) {
            var pos = entry.getKey();
            var chunk = window.level.getChunkSource().getChunkNow(pos.x, pos.z);
            var read = entry.getValue();
            boolean deferred = chunk != null && read.distance == 0 && read.canDefer && read.proofChunk == chunk
                    && read.proofRevision == ChunkMutationTracking.revision(chunk);
            if (chunk == null || pendingPostProcessing(chunk) && !deferred || read.distance != 0
                    && !((GenerationBridge) window.level.getChunkSource().chunkMap).vre$postProcessingReady(chunk)) count++;
        }
        return count;
    }
    static long estimatedBytes() {
        long bytes = retained * 176L;
        for (var window : windows.values()) bytes += window.cursor.estimatedBytes();
        return bytes;
    }
    static void stop() {
        for (UUID id : java.util.List.copyOf(windows.keySet())) release(id);
    }
    private static final class Window {
        final ServerLevel level;
        CenterOutChunkCursor cursor;
        final LinkedHashMap<ChunkPos, Read> positions = new LinkedHashMap<>();
        final PreparationWindow policy = new PreparationWindow();
        int checkedTick = Integer.MIN_VALUE;
        int target, submissions, newlyReady, consumed, ready, waiting;
        long frameDeadline, cursorOffset;
        boolean sampleComplete = true;
        Window(ServerLevel level, CenterOutChunkCursor cursor, long cursorOffset) {
            this.level = level; this.cursor = cursor; this.cursorOffset = cursorOffset;
        }
    }
    private static final class Read {
        final long ordinal;
        int distance;
        boolean ready;
        LevelChunk proofChunk;
        long proofRevision;
        int proofMetadataSize;
        boolean canDefer;
        Read(long ordinal) { this.ordinal = ordinal; }
    }
    private ChunkReadAhead() { }
}
