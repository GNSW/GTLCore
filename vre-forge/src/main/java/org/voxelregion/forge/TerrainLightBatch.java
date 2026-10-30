/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.voxelregion.forge.runtime.ChunkMutationTracking;
import org.voxelregion.lighting.GeneralChunkLighting;

/** Complete optical recomputation of dirty terrain and its influence halo, independent of old neighbor light. */
public final class TerrainLightBatch {
    private static final TicketType<UUID> HOLD = TicketType.create("vre_terrain_light",
            Comparator.<UUID>naturalOrder());
    private static final Map<ServerLevel, List<Request>> waiting = new IdentityHashMap<>();
    private static final List<Job> active = new ArrayList<>();
    // Native completion can arrive off the world owner. These requests do not occupy solver slots.
    private static final Set<Request> fallbackInFlight = ConcurrentHashMap.newKeySet();
    private static final AtomicLong bytes = new AtomicLong(), solved = new AtomicLong(), retried = new AtomicLong();
    private static final AtomicLong unavailable = new AtomicLong(), captured = new AtomicLong();
    private static final AtomicLong contextual = new AtomicLong();
    private static final Map<String, Long> unsupportedDetails = new LinkedHashMap<>();
    private static final AtomicLong unsupportedDetailUnrecordedEvents = new AtomicLong();
    private static final long WORKING_BUDGET = Math.min(512L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8);
    private static ThreadPoolExecutor workers;
    private enum Admission { ACQUIRED, DEFER, UNAVAILABLE }
    private enum UnavailableReason {
        MISSING_HALO("missingHaloRequests"), UNLIT_HALO("unlitHaloRequests"),
        UNTRACKED_REVISION("untrackedRevisionRequests"), UNKNOWN_PROVIDER("unknownProviderRequests"),
        DOMAIN("unsupportedDomainRequests"), CAP_TOO_SMALL("capTooSmallRequests"),
        CHANGED_IDENTITY("changedIdentityRequests");
        final String statistic;
        UnavailableReason(String statistic) { this.statistic = statistic; }
    }
    private static final AtomicLongArray unavailableReasons = new AtomicLongArray(UnavailableReason.values().length);
    private static int workerCount() { return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1)); }
    private static int maxInFlight() { return workerCount() * 3; }

    private record Request(ServerLevel level, LevelChunk chunk, Supplier<CompletableFuture<Void>> fallback,
                           CompletableFuture<Void> result, int attempts) { }

    /** Invoke only after all terrain writes and synchronous native remainder callbacks have finished. */
    public static CompletableFuture<Void> queue(ServerLevel level, LevelChunk chunk,
            Supplier<CompletableFuture<Void>> fallback) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Terrain light admission requires owner");
        var result = new CompletableFuture<Void>();
        waiting.computeIfAbsent(level, ignored -> new ArrayList<>()).add(new Request(level, chunk, fallback, result, 0));
        return result;
    }

    public static long estimatedBytes() { return bytes.get(); }
    public static Map<String, Long> statistics() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("solvedColumns", solved.get()); result.put("retryRequests", retried.get());
        result.put("unavailableRequests", unavailable.get()); result.put("contextualOpticsRequests", contextual.get());
        result.put("capturedSections", captured.get()); result.put("bytes", bytes.get());
        result.put("workingBudgetBytes", WORKING_BUDGET); result.put("fallbackInFlightRequests", (long) fallbackInFlight.size());
        result.put("workerCount", (long) workerCount()); result.put("maxInFlight", (long) maxInFlight());
        result.put("unsupportedDetailUnrecordedEvents", unsupportedDetailUnrecordedEvents.get());
        for (var reason : UnavailableReason.values()) result.put(reason.statistic, unavailableReasons.get(reason.ordinal()));
        synchronized (unsupportedDetails) { result.putAll(unsupportedDetails); }
        return Map.copyOf(result);
    }

    private static void recordUnsupported(TerrainLightSnapshot.UnsupportedOptics unsupported, int requests) {
        synchronized (unsupportedDetails) {
            for (String key : List.of("unsupportedState." + unsupported.stateId,
                    "unsupportedClass." + unsupported.blockClass, "unsupportedReason." + unsupported.reason)) {
                Long count = unsupportedDetails.get(key);
                if (count != null) unsupportedDetails.put(key, count + requests);
                else if (unsupportedDetails.size() < 64) unsupportedDetails.put(key, (long) requests);
                else unsupportedDetailUnrecordedEvents.addAndGet(requests);
            }
        }
    }

    public static void flush(MinecraftServer server) {
        // Optical capture is time-sliced; numerical expansion and propagation never run on this thread.
        long deadline = System.nanoTime() + 3_000_000L;
        for (Job job : List.copyOf(active)) if (!job.running && !job.closed && job.level.getServer() == server) {
            if (job.handoffFailure != null) { job.fail(job.handoffFailure); continue; }
            if (!job.current()) { job.retry(); continue; }
            job.captureUntil(deadline);
            if (System.nanoTime() >= deadline) return;
        }
        for (var entry : List.copyOf(waiting.entrySet())) {
            var level = entry.getKey(); var requests = entry.getValue();
            if (level.getServer() != server) continue;
            while (!requests.isEmpty() && active.size() < maxInFlight() && System.nanoTime() < deadline) {
                Request first = requests.remove(0);
                int tileX = Math.floorDiv(first.chunk.getPos().x, 2), tileZ = Math.floorDiv(first.chunk.getPos().z, 2);
                List<Request> group = new ArrayList<>(); group.add(first);
                requests.removeIf(request -> {
                    if (Math.floorDiv(request.chunk.getPos().x, 2) != tileX || Math.floorDiv(request.chunk.getPos().z, 2) != tileZ)
                        return false;
                    group.add(request); return true;
                });
                Job job = new Job(level, group);
                Admission admission;
                try { admission = job.acquire(); }
                catch (Throwable failure) { job.fail(failure); continue; }
                if (admission == Admission.DEFER) { requests.addAll(0, group); break; }
                if (admission == Admission.UNAVAILABLE) { group.forEach(TerrainLightBatch::fallback); continue; }
                active.add(job);
                job.captureUntil(deadline);
            }
            if (requests.isEmpty()) waiting.remove(level);
        }
    }

    private static ThreadPoolExecutor workers() {
        if (workers == null) {
            int count = workerCount();
            workers = new ThreadPoolExecutor(count, count, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(maxInFlight()), task -> {
                Thread thread = new Thread(task, "VRE-terrain-light"); thread.setDaemon(true); return thread;
            });
            workers.allowCoreThreadTimeOut(true);
        }
        return workers;
    }

    private static void fallback(Request request) {
        if (request.result.isDone()) return;
        fallbackInFlight.add(request);
        request.result.whenComplete((ignored, error) -> fallbackInFlight.remove(request));
        if (request.result.isDone()) return;
        unavailable.incrementAndGet();
        try { request.fallback.get().whenComplete((ignored, error) -> {
            if (error == null) request.result.complete(null); else request.result.completeExceptionally(error);
        }); } catch (Throwable failure) { request.result.completeExceptionally(failure); }
    }

    private static final class Job implements Runnable {
        final ServerLevel level;
        final MinecraftServer server;
        final boolean hasSky;
        final int minSectionY;
        final List<Request> requests;
        final int minX, minZ, maxX, maxZ, width, depth;
        final List<LevelChunk> chunks = new ArrayList<>();
        final List<ChunkPos> positions = new ArrayList<>();
        final AtomicBoolean valid = new AtomicBoolean(true);
        final UUID ticketOwner = UUID.randomUUID();
        long[] revisions;
        long reserved;
        Runnable unsubscribe = () -> {};
        TerrainLightSnapshot capture;
        volatile boolean running, closed;
        volatile Throwable handoffFailure;
        boolean computationOwnsMemory;
        Job(ServerLevel level, List<Request> requests) {
            this.level = level; this.server = level.getServer(); this.requests = List.copyOf(requests);
            hasSky = level.dimensionType().hasSkyLight(); minSectionY = level.getMinSection();
            minX = requests.stream().mapToInt(r -> r.chunk.getPos().x).min().orElseThrow();
            minZ = requests.stream().mapToInt(r -> r.chunk.getPos().z).min().orElseThrow();
            maxX = requests.stream().mapToInt(r -> r.chunk.getPos().x).max().orElseThrow();
            maxZ = requests.stream().mapToInt(r -> r.chunk.getPos().z).max().orElseThrow();
            width = maxX - minX + 5; depth = maxZ - minZ + 5;
        }
        Admission acquire() {
            int height = level.getHeight();
            long cells = (long) width * depth * 256 * height;
            if (height < 16 || height > org.voxelregion.lighting.ClearedChunkLighting.MAX_HEIGHT || height % 16 != 0
                    || width * 16 > GeneralChunkLighting.MAX_HORIZONTAL_SIZE || depth * 16 > GeneralChunkLighting.MAX_HORIZONTAL_SIZE
                    || cells > GeneralChunkLighting.MAX_CELLS) return unavailable(UnavailableReason.DOMAIN);
            if (!(level.getChunkSource().getLightEngine() instanceof NativeAccess.LightFence fence)
                    || !fence.vre$supportsSolvedMutationRegion()) return unavailable(UnavailableReason.UNKNOWN_PROVIDER);
            long estimate = cells * 24;
            // Solver arrays have their own bounded lease; treating them as pending save metadata
            // can leave a valid 81 MiB batch waiting forever behind a 128 MiB IO budget.
            if (estimate > WORKING_BUDGET) return unavailable(UnavailableReason.CAP_TOO_SMALL);
            if (bytes.get() > WORKING_BUDGET - estimate) return Admission.DEFER;
            for (int z = minZ - 2; z <= maxZ + 2; z++) for (int x = minX - 2; x <= maxX + 2; x++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                // Do not trigger extra terrain generation for lighting. Full, light-correct columns have
                // already crossed native INITIALIZE_LIGHT/LIGHT before they can supply this snapshot.
                if (chunk == null) return unavailable(UnavailableReason.MISSING_HALO);
                if (!chunk.isLightCorrect()) return unavailable(UnavailableReason.UNLIT_HALO);
                if (ChunkMutationTracking.revision(chunk) < 0) return unavailable(UnavailableReason.UNTRACKED_REVISION);
                chunks.add(chunk); positions.add(chunk.getPos());
            }
            for (Request request : requests) if (level.getChunkSource().getChunkNow(request.chunk.getPos().x,
                    request.chunk.getPos().z) != request.chunk) return unavailable(UnavailableReason.CHANGED_IDENTITY);
            revisions = new long[chunks.size()];
            for (int i = 0; i < chunks.size(); i++) {
                revisions[i] = ChunkMutationTracking.revision(chunks.get(i));
                level.getChunkSource().addRegionTicket(HOLD, positions.get(i), 0, ticketOwner);
            }
            unsubscribe = ChunkMutationTracking.subscribe(level, (chunk, revision) -> {
                if (chunks.contains(chunk)) valid.set(false);
            });
            reserved = estimate; bytes.addAndGet(estimate);
            capture = new TerrainLightSnapshot(level, chunks, width, depth);
            return Admission.ACQUIRED;
        }
        Admission unavailable(UnavailableReason reason) {
            unavailableReasons.addAndGet(reason.ordinal(), requests.size());
            return Admission.UNAVAILABLE;
        }
        void captureUntil(long deadline) {
            try {
                if (capture.capture(deadline)) {
                    captured.addAndGet((long) chunks.size() * level.getSectionsCount()); dispatch();
                }
            } catch (TerrainLightSnapshot.UnsupportedOptics unsupported) {
                contextual.addAndGet(requests.size());
                recordUnsupported(unsupported, requests.size());
                release(); requests.forEach(TerrainLightBatch::fallback);
            } catch (Throwable failure) { fail(failure); }
        }
        boolean current() {
            if (!valid.get() || closed) return false;
            for (int i = 0; i < chunks.size(); i++) {
                var pos = positions.get(i); var chunk = chunks.get(i);
                if (level.getChunkSource().getChunkNow(pos.x, pos.z) != chunk
                        || ChunkMutationTracking.revision(chunk) != revisions[i]) { valid.set(false); return false; }
            }
            return true;
        }
        void dispatch() {
            running = true;
            synchronized (this) { computationOwnsMemory = true; }
            try { workers().execute(this); }
            catch (Throwable failure) {
                synchronized (this) { computationOwnsMemory = false; }
                fail(failure);
            }
        }
        @Override public void run() {
            TerrainLightSnapshot snapshot;
            synchronized (this) {
                if (closed) { computationOwnsMemory = false; releaseMemory(); return; }
                snapshot = capture;
            }
            List<NativeAccess.SolvedLightColumn> columns = new ArrayList<>();
            Throwable error = null;
            try {
                if (valid.get()) {
                    var input = snapshot.assemble(hasSky);
                    var session = new GeneralChunkLighting.Session(input);
                    while (!session.isComplete() && valid.get() && !Thread.currentThread().isInterrupted()) session.step(16_384);
                    if (session.isComplete() && valid.get()) {
                        var solution = session.result();
                        for (int z = 1; z < depth - 1; z++) for (int x = 1; x < width - 1; x++)
                            columns.add(new NativeAccess.SolvedLightColumn(new ChunkPos(minX - 2 + x, minZ - 2 + z),
                                    minSectionY, solution.cropColumn(x, z)));
                    }
                }
            } catch (Throwable failure) { error = failure; }
            finally {
                snapshot = null;
                synchronized (this) {
                    computationOwnsMemory = false;
                    if (closed) releaseMemory();
                }
            }
            if (closed) return;
            Throwable failure = error;
            try {
                server.execute(() -> {
                    if (closed) return;
                    if (failure != null) { fail(failure); return; }
                    if (columns.isEmpty() || !current()) { retry(); return; }
                    try { ((NativeAccess.LightFence) level.getChunkSource().getLightEngine()).vre$installSolvedMutationRegion(
                            columns, positions, minX, minZ, maxX, maxZ, valid::get).whenCompleteAsync((applied, installError) -> {
                                if (closed) return;
                                if (installError != null) { fail(installError); return; }
                                if (!Boolean.TRUE.equals(applied) || !current()) { retry(); return; }
                                for (LevelChunk chunk : chunks) {
                                    var position = chunk.getPos();
                                    if (position.x >= minX - 1 && position.x <= maxX + 1
                                            && position.z >= minZ - 1 && position.z <= maxZ + 1)
                                        chunk.setUnsaved(true);
                                }
                                solved.addAndGet(requests.stream().map(r -> r.chunk.getPos()).distinct().count());
                                release(); requests.forEach(r -> r.result.complete(null));
                            }, server).whenComplete((ignored, callbackFailure) -> {
                                if (callbackFailure != null && !closed) signalHandoffFailure(callbackFailure);
                            });
                    } catch (Throwable installFailure) { fail(installFailure); }
                });
            } catch (Throwable dispatchFailure) { signalHandoffFailure(dispatchFailure); }
        }
        void signalHandoffFailure(Throwable failure) {
            // Completing futures is thread-safe; ticket/list cleanup stays on the server owner.
            valid.set(false); handoffFailure = failure; running = false;
            requests.forEach(request -> request.result.completeExceptionally(failure));
        }
        void retry() {
            release();
            for (Request request : requests) {
                retried.incrementAndGet();
                waiting.computeIfAbsent(level, ignored -> new ArrayList<>()).add(new Request(level, request.chunk,
                        request.fallback, request.result, Math.min(Integer.MAX_VALUE - 1, request.attempts) + 1));
            }
        }
        void fail(Throwable error) { release(); requests.forEach(r -> r.result.completeExceptionally(error)); }
        void release() {
            if (closed) return;
            closed = true; valid.set(false); unsubscribe.run(); active.remove(this); capture = null;
            for (ChunkPos position : positions) level.getChunkSource().removeRegionTicket(HOLD, position, 0, ticketOwner);
            synchronized (this) { if (!computationOwnsMemory) releaseMemory(); }
        }
        private synchronized void releaseMemory() {
            bytes.addAndGet(-reserved); reserved = 0;
        }
        synchronized void cancelQueuedComputation() {
            computationOwnsMemory = false;
            if (closed) releaseMemory();
        }
    }
    public static void stop(MinecraftServer server) {
        var failure = new IllegalStateException("Server stopped before terrain lighting completed");
        for (Job job : List.copyOf(active)) if (job.level.getServer() == server) job.fail(failure);
        for (var entry : List.copyOf(waiting.entrySet())) if (entry.getKey().getServer() == server) {
            entry.getValue().forEach(request -> request.result.completeExceptionally(failure)); waiting.remove(entry.getKey());
        }
        for (Request request : Set.copyOf(fallbackInFlight)) if (request.level.getServer() == server)
            request.result.completeExceptionally(failure);
        if (workers != null) {
            for (Runnable queued : workers.shutdownNow()) if (queued instanceof Job job) job.cancelQueuedComputation();
            workers = null;
        }
    }
    /** Dimension unload must complete native fallback requests even if that light owner never runs again. */
    public static void stop(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Terrain light unload requires owner");
        var failure = new IllegalStateException("Level unloaded before terrain lighting completed");
        for (Job job : List.copyOf(active)) if (job.level == level) job.fail(failure);
        var pending = waiting.remove(level);
        if (pending != null) pending.forEach(request -> request.result.completeExceptionally(failure));
        for (Request request : Set.copyOf(fallbackInFlight)) if (request.level == level)
            request.result.completeExceptionally(failure);
    }
    private TerrainLightBatch() {}
}
