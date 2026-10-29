/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

/** Compress detached NBT in parallel; native IOWorker still orders/coalesces every actual region write. */
public final class ParallelChunkCompression {
    private static final Map<CompoundTag, Job> jobs = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final AtomicLong bytes = new AtomicLong();
    private static final ThreadPoolExecutor pool;
    static {
        int count = org.voxelregion.forge.runtime.RuntimeBridge.ioThreads();
        pool = new ThreadPoolExecutor(count, count, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2048), runnable -> {
            Thread thread = new Thread(runnable, "VRE chunk compression"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
    }
    public static long estimatedBytes() { return bytes.get(); }
    public static void prepare(CompoundTag source, long allowance) {
        if (source == null) return;
        long reservation = Math.max(4096L, 3L * source.sizeInBytes() + 4096);
        if (reservation > allowance || jobs.size() >= 2048 || jobs.containsKey(source)) return;
        // Heightmap/light tags can retain native backing arrays. No live mutable tag crosses threads.
        CompoundTag snapshot = source.copy();
        var job = new Job(reservation); bytes.addAndGet(reservation); jobs.put(source, job);
        try {
            pool.execute(() -> {
                try {
                    var output = new ByteArrayOutputStream();
                    output.write(new byte[5]);
                    try (var compressed = new DataOutputStream(new DeflaterOutputStream(output))) { NbtIo.write(snapshot, compressed); }
                    byte[] data = output.toByteArray();
                    ByteBuffer.wrap(data).putInt(data.length - 4).put((byte)2);
                    job.result.complete(data);
                } catch (Throwable error) { job.result.completeExceptionally(error); }
            });
        } catch (RejectedExecutionException full) { jobs.remove(source); bytes.addAndGet(-reservation); }
    }
    /** Called from the native storage owner, after its ordinary latest-write selection. */
    public static CompletableFuture<byte[]> find(CompoundTag source) {
        var job = jobs.get(source); return job == null ? null : job.result;
    }
    public static void stored(CompoundTag source, CompletableFuture<Void> store) {
        if (source == null) return;
        var job = jobs.get(source);
        if (job == null || !job.observed.compareAndSet(false, true)) return;
        store.whenComplete((ignored, error) -> {
            jobs.remove(source, job);
            job.result.whenComplete((data, compressionFailure) -> bytes.addAndGet(-job.bytes));
        });
    }
    private static final class Job {
        final long bytes; final CompletableFuture<byte[]> result = new CompletableFuture<>();
        final java.util.concurrent.atomic.AtomicBoolean observed = new java.util.concurrent.atomic.AtomicBoolean();
        Job(long bytes) { this.bytes = bytes; }
    }
    private ParallelChunkCompression() { }
}
