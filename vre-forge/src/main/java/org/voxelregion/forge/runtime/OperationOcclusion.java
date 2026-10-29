/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;
import org.voxelregion.forge.FrozenOcclusionCapture;
import org.voxelregion.forge.HostIntegration;
import org.voxelregion.geometry.Point3;
import org.voxelregion.propagation.FrozenOcclusion;
import org.voxelregion.spatial.ChunkKey;

/** Complete read-only capture, immutable journal publication, then world-free index compilation. */
final class OperationOcclusion {
    private static final TagKey<Block> BLOCKERS = TagKey.create(Registries.BLOCK, new ResourceLocation("voxelregion_test", "blocks_propagation"));
    private static final AtomicLong RULE_REVISION = new AtomicLong();
    private static final AtomicLong RESERVED_BYTES = new AtomicLong();
    private static final Map<UUID, Work> pending = new HashMap<>();
    private record Resident(OperationData.Task task, MemoryLease lease) {}
    private static final Map<UUID, Resident> resident = new HashMap<>();
    private static ThreadPoolExecutor worker;
    record Result(boolean ready, int visits) {}
    record Compiled(String hash, FrozenOcclusionCapture.Snapshot snapshot) {}
    private static final class Work {
        FrozenOcclusionCapture.Session capture;
        Runnable unsubscribe = () -> {};
        CompileJob job;
        MemoryLease lease;
    }
    static void rulesChanged() { RULE_REVISION.incrementAndGet(); }
    static long estimatedBytes() { return RESERVED_BYTES.get(); }
    static String stage(UUID id) {
        Work work = pending.get(id);
        if (work != null) return work.job == null ? "CAPTURING" : "PERSISTING_OR_INDEXING";
        return resident.containsKey(id) ? "READY" : "NOT_STARTED";
    }
    private static ThreadPoolExecutor worker() {
        if (worker == null) worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128), r -> { Thread t = new Thread(r, "vre-frozen-index"); t.setDaemon(true); return t; });
        return worker;
    }
    static Result step(ServerLevel level, OperationData.Task task, int visits, long deadline,
                       BooleanSupplier hold, Runnable release) {
        if (task.propagation == FrozenOcclusion.Mode.NONE || task.occlusionReady) return new Result(true, 0);
        Work work = pending.get(task.id);
        if (work == null) {
            // Leave room for bounded queue/bookkeeping growth while this capture
            // owns its reservation. Metadata and snapshots still use one ledger.
            long limit = AdapterConfig.MAX_PENDING_BYTES.get();
            long remaining = limit - Math.min(1_048_576L, limit / 16)
                    - ForgeOperationRunner.estimatedMetadataBytes(level.getServer()) - estimatedBytes();
            if (remaining < 524_288) { task.message = "Waiting for the global frozen-snapshot memory reservation."; return new Result(false, 0); }
            work = new Work(); work.lease = new MemoryLease(remaining); pending.put(task.id, work);
            Path directory = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/voxelregion_test_intents");
            UUID id = task.id; String intent = task.intentHash();
            if (!task.frozenHash.isEmpty()) {
                String hash = task.frozenHash; long reservation = work.lease.bytes();
                work.job = new CompileJob(work.lease,
                        () -> new Compiled(hash, FrozenFieldStore.read(directory, id, intent, hash, reservation).compileIndex()));
                work.job.submit(worker());
            } else {
                if (task.recovering) { pause(task, "Restart has no durable original blocker snapshot; recapture is unsafe."); return new Result(false, 0); }
                if (!HostIntegration.supportsBulk(level)) { pause(task, "Occlusion capture requires audited mutation tracking for all installed integrations."); return new Result(false, 0); }
                work.capture = new FrozenOcclusionCapture.Session(level, task.volume, Point3.of(task.cx, task.cy, task.cz),
                        task.propagation, task.occlusionCell, 32_768, 262_144,
                        work.lease.bytes(),
                        new FrozenOcclusionCapture.BlockerPolicy() {
                            public boolean blocks(net.minecraft.world.level.block.state.BlockState state) { return state.is(BLOCKERS); }
                            public long revision() { return RULE_REVISION.get(); }
                        }, chunk -> ChunkReceipts.of(chunk).trackingValid() ? ChunkReceipts.of(chunk).mutationRevision() : -1);
                var capture = work.capture;
                work.unsubscribe = ChunkMutationTracking.subscribe(level, (chunk, revision) -> {
                    if (chunk.getLevel() == level) capture.observeMutation(new ChunkKey(chunk.getPos().x, chunk.getPos().z), revision);
                });
            }
        }
        if (work.job != null) {
            if (!work.job.finished().isDone()) { task.message = "Persisting frozen blockers and compiling the immutable index."; return new Result(false, 0); }
            Compiled compiled = work.job.take();
            if (compiled.snapshot.estimatedBytes() > work.lease.bytes()) throw new IllegalStateException("Restored snapshot exceeds available global memory");
            work.lease.shrinkTo(compiled.snapshot.estimatedBytes());
            resident.put(task.id, new Resident(task, work.lease));
            task.frozenHash = compiled.hash; task.occlusion = compiled.snapshot.occlusion(); task.occlusionReady = true;
            task.message = "";
            IntentJournal.checkpoint(level, task); // Must precede the first removal.
            pending.remove(task.id); work.unsubscribe.run(); release.run();
            return new Result(true, 0);
        }
        var capture = work.capture;
        if (capture.state() == FrozenOcclusionCapture.State.NEED_CHUNK) {
            ChunkKey key = capture.nextChunk(); task.chunkX = key.x(); task.chunkZ = key.z();
            var chunk = level.getChunkSource().getChunkNow(key.x(), key.z());
            if (chunk == null) {
                if (!task.generate) { pause(task, "Frozen propagation requires the entire region to be readable; an unloaded chunk is not evidence of empty space."); release.run(); return new Result(false, 0); }
                hold.getAsBoolean(); task.message = "Loading normal terrain for the original blocker snapshot."; return new Result(false, 0);
            }
            if (!hold.getAsBoolean()) return new Result(false, 0);
            capture.acceptChunk(chunk, ChunkReceipts.of(chunk).mutationRevision());
        }
        var progress = capture.step(Math.min(4096, visits), deadline);
        if (progress.state() != FrozenOcclusionCapture.State.SCANNING) release.run();
        if (progress.state() == FrozenOcclusionCapture.State.READY_TO_FREEZE) {
            // The mutation subscription has remained installed since before the first capture.
            var sealed = capture.sealSnapshot((key, revision) -> capture.state() == FrozenOcclusionCapture.State.READY_TO_FREEZE);
            work.unsubscribe.run(); work.unsubscribe = () -> {};
            work.capture = null; // No Session/world reference enters the worker's payload.
            Path directory = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/voxelregion_test_intents");
            UUID id = task.id; String intent = task.intentHash();
            work.job = new CompileJob(work.lease, () -> {
                String hash = FrozenFieldStore.write(directory, id, intent, sealed);
                return new Compiled(hash, sealed.compileIndex());
            });
            work.job.submit(worker());
        } else if (progress.state() != FrozenOcclusionCapture.State.SCANNING && progress.state() != FrozenOcclusionCapture.State.NEED_CHUNK) {
            pause(task, progress.message());
        } else task.message = "Capturing original blockers: chunks=" + progress.chunks() + ", boxes=" + progress.boxes();
        return new Result(false, progress.visited());
    }
    private static void pause(OperationData.Task task, String reason) {
        ForgeOperationRunner.pause(task, OperationData.State.PAUSED_RECOVERY, reason);
        release(task.id);
    }
    static void release(UUID id) {
        Resident loaded = resident.remove(id);
        if (loaded != null) {
            loaded.task.occlusion = FrozenOcclusion.none(); loaded.task.occlusionReady = false;
            loaded.lease.close();
        }
        Work work = pending.remove(id);
        if (work != null) {
            work.unsubscribe.run(); work.capture = null;
            if (work.job == null) work.lease.close();
            else work.job.cancel();
        }
    }
    static void stop() {
        for (UUID id : java.util.List.copyOf(pending.keySet())) release(id);
        for (UUID id : java.util.List.copyOf(resident.keySet())) release(id);
        if (worker != null) {
            ThreadPoolExecutor stopped = worker; worker = null;
            for (Runnable queued : stopped.shutdownNow())
                if (queued instanceof CompileJob job) job.discard(new CancellationException("Server stopped before frozen worker started"));
            // Running jobs have been marked cancelled above. They retain their
            // lease until their actual finally block; never block the world thread.
        }
    }

    /** One transferable estimate for capture, worker payload and resident index. */
    static final class MemoryLease implements AutoCloseable {
        private long bytes;
        private boolean closed;
        MemoryLease(long bytes) {
            if (bytes < 1) throw new IllegalArgumentException("Invalid snapshot reservation");
            this.bytes = bytes;
            RESERVED_BYTES.updateAndGet(current -> Math.addExact(current, bytes));
        }
        synchronized long bytes() { return bytes; }
        synchronized void shrinkTo(long remaining) {
            if (closed || remaining < 1 || remaining > bytes) throw new IllegalStateException("Invalid reservation transfer");
            RESERVED_BYTES.addAndGet(remaining - bytes); bytes = remaining;
        }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true; RESERVED_BYTES.addAndGet(-bytes); bytes = 0;
        }
    }

    /**
     * Owns a world-free callable and its memory lease. Cancellation never marks
     * completion or returns memory while a running callable still holds it.
     * The completion signal contains no snapshot; cancelled jobs can drop it.
     */
    static final class CompileJob implements Runnable {
        private final MemoryLease lease;
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private Callable<Compiled> action;
        private ThreadPoolExecutor executor;
        private Compiled value;
        private Throwable failure;
        private boolean cancelled, done;
        CompileJob(MemoryLease lease, Callable<Compiled> action) {
            this.lease = java.util.Objects.requireNonNull(lease);
            this.action = java.util.Objects.requireNonNull(action);
        }
        synchronized CompletableFuture<Void> finished() { return finished; }
        void submit(ThreadPoolExecutor executor) {
            synchronized (this) { this.executor = executor; }
            try { executor.execute(this); }
            catch (RuntimeException rejected) { discard(rejected); }
        }
        @Override public void run() {
            Callable<Compiled> operation;
            synchronized (this) {
                if (done) return;
                if (cancelled) { complete(null, new CancellationException("Frozen worker cancelled")); return; }
                operation = action; action = null;
            }
            Compiled result = null;
            Throwable error = null;
            try { result = operation.call(); }
            catch (Throwable problem) { error = problem; }
            finally {
                operation = null; // Release the detached input before returning its reservation.
                synchronized (this) {
                    if (cancelled || error != null) result = null;
                    complete(result, error);
                }
            }
        }
        synchronized Compiled take() {
            if (!done) throw new IllegalStateException("Frozen worker is still running");
            if (cancelled) throw new CancellationException("Frozen worker cancelled");
            if (failure != null) throw new CompletionException(failure);
            if (value == null) throw new IllegalStateException("Frozen worker result already consumed");
            Compiled result = value; value = null;
            return result; // The same lease is transferred to the main-thread resident.
        }
        synchronized void cancel() {
            cancelled = true;
            if (done) { value = null; lease.close(); return; }
            if (executor != null && executor.remove(this))
                complete(null, new CancellationException("Queued frozen worker cancelled"));
            // remove(false) also covers a worker that just dequeued this job:
            // run/finally is then the only authority allowed to release memory.
        }
        synchronized void discard(Throwable reason) {
            if (done) return;
            cancelled = true;
            complete(null, reason);
        }
        private void complete(Compiled result, Throwable error) {
            if (!cancelled && error == null && result == null)
                error = new IllegalStateException("Missing frozen worker result");
            action = null; done = true; failure = error;
            if (cancelled || error != null) {
                value = null; lease.close();
            } else value = result;
            finished.complete(null);
        }
    }
    private OperationOcclusion() {}
}
