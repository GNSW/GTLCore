/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;

/** Native mailbox owns admission/cache/read overlays; independent region lanes perform actual writes. */
public final class ParallelRegionStores {
    private static final Map<RegionFileStorage, ParallelRegionStores> stores = new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor workers;
    private static final java.util.concurrent.atomic.AtomicInteger retained = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong forcedFiles = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong forceNanos = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicInteger activeFlushes = new java.util.concurrent.atomic.AtomicInteger();
    static {
        int count = org.voxelregion.forge.runtime.RuntimeBridge.ioThreads();
        workers = new ThreadPoolExecutor(count, count, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
            var thread = new Thread(runnable, "VRE region storage"); thread.setDaemon(true); return thread;
        });
        workers.allowCoreThreadTimeOut(true);
    }
    private final RegionFileStorage storage;
    private final Executor owner;
    private final Map<Long, Lane> lanes = new HashMap<>();
    private final Map<Long, FlushLease> flushFiles = new HashMap<>();
    private final Map<Long, Write> latest = new HashMap<>();
    private final Set<CompletableFuture<Void>> pending = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<Void>> pendingFlushes = ConcurrentHashMap.newKeySet();

    public static ParallelRegionStores find(RegionFileStorage storage) { return stores.get(storage); }
    public static CompletableFuture<Void> flush(RegionFile file) {
        return CompletableFuture.runAsync(() -> {
            long started = System.nanoTime();
            try { file.flush(); forcedFiles.incrementAndGet(); }
            catch (IOException error) { throw new CompletionException(error); }
            finally { forceNanos.addAndGet(System.nanoTime() - started); }
        }, workers);
    }
    public static Map<String, Long> flushStatistics() {
        return Map.of("forcedFiles", forcedFiles.get(), "forceNanos", forceNanos.get(),
                "activeFlushes", (long) activeFlushes.get());
    }
    public static ParallelRegionStores create(RegionFileStorage storage, Executor owner) {
        return stores.computeIfAbsent(storage, ignored -> new ParallelRegionStores(storage, owner));
    }
    private ParallelRegionStores(RegionFileStorage storage, Executor owner) { this.storage = storage; this.owner = owner; }
    public RegionFile leasedFile(ChunkPos position) {
        long key = region(position);
        var lane = lanes.get(key);
        if (lane != null) return lane.file;
        var flushing = flushFiles.get(key);
        return flushing == null ? null : flushing.file;
    }
    public boolean hasLatest(ChunkPos position) { return latest.containsKey(position.toLong()); }
    public CompoundTag latest(ChunkPos position) { var data = latest.get(position.toLong()).data; return data == null ? null : data.copy(); }

    /**
     * Called on the native mailbox only after the caller's actual store futures completed.
     * Retain/cache admission and release stay on that mailbox; durable file forces do not.
     * The file lease also prevents opening a second handle if native LRU eviction occurs
     * while a force is running. Later stores may run, but cannot move ahead of earlier
     * writes through the same RegionFile lock or make this force acknowledge less data.
     */
    public CompletableFuture<Void> flushStored(Collection<ChunkPos> positions) {
        var regions = new LinkedHashMap<Long, ChunkPos>();
        for (ChunkPos position : positions) regions.putIfAbsent(region(position), position);
        if (regions.isEmpty()) return CompletableFuture.completedFuture(null);
        var result = new CompletableFuture<Void>();
        var leases = new ArrayList<Map.Entry<Long, FlushLease>>();
        var forces = new ArrayList<CompletableFuture<Void>>();
        pendingFlushes.add(result); activeFlushes.incrementAndGet();
        Throwable captureFailure = null;
        try {
            for (var entry : regions.entrySet()) {
                long key = entry.getKey();
                FlushLease lease = flushFiles.get(key);
                if (lease == null) {
                    RegionFile file = ((NativeAccess.RegionFiles)(Object) storage).vre$regionFile(entry.getValue());
                    if (file.getClass() != RegionFile.class || !(file instanceof NativeAccess.PreparedRegionFile)) {
                        // Unknown providers keep their native owner-thread contract.
                        file.flush();
                        continue;
                    }
                    lease = new FlushLease(file);
                    ((NativeAccess.PreparedRegionFile) file).vre$retainFile();
                    flushFiles.put(key, lease);
                }
                lease.references++;
                leases.add(Map.entry(key, lease));
                forces.add(flush(lease.file));
            }
        } catch (Throwable failure) { captureFailure = failure; }
        Throwable captured = captureFailure;
        CompletableFuture.allOf(forces.toArray(CompletableFuture[]::new)).whenCompleteAsync((ignored, failure) -> {
            Throwable error = captured == null ? failure : captured;
            if (captured != null && failure != null && captured != failure) captured.addSuppressed(failure);
            for (var entry : leases) {
                FlushLease lease = entry.getValue();
                if (--lease.references == 0) {
                    flushFiles.remove(entry.getKey(), lease);
                    try { ((NativeAccess.PreparedRegionFile) lease.file).vre$releaseFile(); }
                    catch (Throwable releaseFailure) {
                        if (error == null) error = releaseFailure;
                        else if (error != releaseFailure) error.addSuppressed(releaseFailure);
                    }
                }
            }
            activeFlushes.decrementAndGet(); pendingFlushes.remove(result);
            if (error == null) result.complete(null); else result.completeExceptionally(error);
        }, owner);
        return result;
    }

    /** False keeps unrelated native stores on their original path. Called exclusively by the IOWorker owner. */
    public boolean store(ChunkPos position, CompoundTag data, CompletableFuture<Void> nativeResult) {
        var compressed = data == null ? null : ParallelChunkCompression.find(data);
        long key = region(position);
        Lane lane = lanes.get(key);
        if (compressed == null && lane == null) return false;
        try {
            if (lane == null) {
                RegionFile file = ((NativeAccess.RegionFiles)(Object)storage).vre$regionFile(position);
                if (file.getClass() != RegionFile.class || !(file instanceof NativeAccess.PreparedRegionFile access) || !access.vre$usesDeflate()) return false;
                access.vre$retainFile();
                lane = new Lane(file); lanes.put(key, lane);
            }
            var target = lane;
            var write = new Write(data == null ? null : data.copy(), nativeResult);
            latest.put(position.toLong(), write); pending.add(nativeResult); target.count++;
            var previous = target.tail;
            // Queued jobs are bounded across dimensions. Backpressure waits only for worker
            // work, never for callbacks on this owner mailbox.
            boolean inline = retained.incrementAndGet() > 4096;
            var ready = previous.handle((ignored, failure) -> null);
            Runnable action = () -> {
                try {
                    if (write.data == null) {
                        // RegionFile.clear is not synchronized in this Minecraft version.
                        // Delete offsets/header/sectors atomically against detached forces.
                        synchronized (target.file) { target.file.clear(position); }
                    }
                    else if (compressed != null) ((NativeAccess.PreparedRegionFile)target.file).vre$writeCompressed(position, ByteBuffer.wrap(compressed.join()));
                    else try (var output = target.file.getChunkDataOutputStream(position)) { NbtIo.write(write.data, output); }
                } catch (IOException error) { throw new CompletionException(error); }
            };
            CompletableFuture<Void> work;
            if (inline) {
                ready.join();
                try { action.run(); work = CompletableFuture.completedFuture(null); }
                catch (RuntimeException error) { work = CompletableFuture.failedFuture(error); }
            } else work = ready.thenRunAsync(action, workers);
            target.tail = work;
            work.whenCompleteAsync((ignored, failure) -> {
                retained.decrementAndGet(); latest.remove(position.toLong(), write);
                Throwable error = failure;
                if (--target.count == 0) {
                    lanes.remove(key, target);
                    try { ((NativeAccess.PreparedRegionFile)target.file).vre$releaseFile(); }
                    catch (IOException closeError) { if (error == null) error = closeError; else error.addSuppressed(closeError); }
                }
                if (error == null) nativeResult.complete(null); else nativeResult.completeExceptionally(error);
                pending.remove(nativeResult);
            }, owner);
            return true;
        } catch (IOException | RuntimeException error) { nativeResult.completeExceptionally(error); return true; }
    }

    /** Snapshot on the owner, after the original native barrier, includes dispatched stores it no longer lists. */
    public CompletableFuture<Void> afterNativeBarrier(boolean flush) {
        return CompletableFuture.supplyAsync(() -> CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)), owner)
                .thenCompose(future -> future).thenRunAsync(() -> {
                    if (flush) try { storage.flush(); } catch (IOException error) { throw new CompletionException(error); }
                }, owner);
    }
    /** Called after native shutdown priority has drained admission, while its mailbox still accepts completions. */
    public void drainBeforeClose() {
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).handle((ignored, error) -> null).join();
        // IOWorker.close invokes this outside its mailbox, after shutdown admission.
        // Keep the mailbox alive for lease release and completion callbacks.
        CompletableFuture.allOf(pendingFlushes.toArray(CompletableFuture[]::new)).handle((ignored, error) -> null).join();
        stores.remove(storage, this);
    }
    private static long region(ChunkPos position) { return ChunkPos.asLong(position.x >> 5, position.z >> 5); }
    private static final class Lane {
        final RegionFile file; CompletableFuture<Void> tail = CompletableFuture.completedFuture(null); int count;
        Lane(RegionFile file) { this.file = file; }
    }
    private static final class FlushLease {
        final RegionFile file;
        int references;
        FlushLease(RegionFile file) { this.file = file; }
    }
    private record Write(CompoundTag data, CompletableFuture<Void> result) { }
}
