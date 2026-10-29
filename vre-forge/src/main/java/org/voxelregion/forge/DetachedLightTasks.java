/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.voxelregion.lighting.ClearedChunkLighting;

/** Detached calculation has its own bounded workers; native light storage has one owning executor. */
public final class DetachedLightTasks {
    private static final Set<Job> jobs = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger threadIds = new AtomicInteger();
    private static ThreadPoolExecutor workers;

    private static synchronized ThreadPoolExecutor workers() {
        if (workers == null) {
            int count = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 1));
            workers = new ThreadPoolExecutor(count, count, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(16), runnable -> {
                        Thread thread = new Thread(runnable, "VRE-detached-light-" + threadIds.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    });
            workers.allowCoreThreadTimeOut(true);
        }
        return workers;
    }

    /** Ownership of the admission lease transfers here, including rejection and shutdown paths. */
    public static CompletableFuture<Boolean> submit(ClearedChunkLighting.Snapshot input,
            BooleanSupplier valid, Consumer<Runnable> nativeExecutor,
            Function<ClearedChunkLighting.Result, Boolean> install, Runnable release) {
        Job job = new Job(input, valid, nativeExecutor, install, release);
        jobs.add(job);
        try { workers().execute(job); }
        catch (RuntimeException failure) { job.finish(false, failure); }
        return job.completion;
    }

    /** Never blocks shutdown on computation, and never releases a lease still used by a worker. */
    public static synchronized void stop() {
        ThreadPoolExecutor previous = workers;
        workers = null;
        for (Job job : Set.copyOf(jobs)) job.cancel();
        if (previous != null) previous.shutdownNow();
    }

    private static final class Job implements Runnable {
        final BooleanSupplier valid;
        final Consumer<Runnable> nativeExecutor;
        final Function<ClearedChunkLighting.Result, Boolean> install;
        final Runnable release;
        final CompletableFuture<Boolean> completion = new CompletableFuture<>();
        ClearedChunkLighting.Snapshot input;
        ClearedChunkLighting.Result solution;
        volatile boolean cancelled;
        boolean running, finished;

        Job(ClearedChunkLighting.Snapshot input, BooleanSupplier valid, Consumer<Runnable> nativeExecutor,
                Function<ClearedChunkLighting.Result, Boolean> install, Runnable release) {
            this.input = input; this.valid = valid; this.nativeExecutor = nativeExecutor;
            this.install = install; this.release = release;
        }

        @Override public void run() {
            ClearedChunkLighting.Snapshot snapshot;
            synchronized (this) {
                if (finished) return;
                running = true; snapshot = input; input = null;
            }
            ClearedChunkLighting.Result solved = null;
            Throwable error = null;
            try {
                if (snapshot != null && !cancelled && valid.getAsBoolean()) {
                    var session = new ClearedChunkLighting.Session(snapshot);
                    while (!session.isComplete() && !cancelled && !Thread.currentThread().isInterrupted()
                            && valid.getAsBoolean()) session.step(16_384);
                    if (session.isComplete() && !cancelled && valid.getAsBoolean()) solved = session.result();
                }
            } catch (Throwable failure) { error = failure; }
            snapshot = null;
            synchronized (this) {
                running = false;
                if (!cancelled && error == null) solution = solved;
            }
            solved = null;
            if (error != null) { finish(false, error); return; }
            synchronized (this) {
                if (finished) return;
                if (solution == null || cancelled) { finish(false, null); return; }
            }
            try { nativeExecutor.accept(this::installOnOwner); }
            catch (RuntimeException | LinkageError failure) { finish(false, failure); }
        }

        private void installOnOwner() {
            ClearedChunkLighting.Result solved;
            synchronized (this) {
                if (finished) return;
                running = true; solved = solution; solution = null;
            }
            boolean applied = false; Throwable error = null;
            try {
                if (!cancelled && solved != null && valid.getAsBoolean()) applied = install.apply(solved);
            } catch (Throwable failure) { error = failure; }
            finally {
                solved = null;
                synchronized (this) { running = false; }
                finish(applied && !cancelled, error);
            }
        }

        synchronized void cancel() {
            cancelled = true;
            if (!running) finish(false, null);
        }

        synchronized void finish(boolean applied, Throwable error) {
            if (finished) return;
            finished = true; input = null; solution = null;
            jobs.remove(this);
            try { release.run(); }
            catch (Throwable releaseFailure) {
                if (error == null) error = releaseFailure; else error.addSuppressed(releaseFailure);
            }
            if (error == null) completion.complete(applied); else completion.completeExceptionally(error);
        }
    }
    private DetachedLightTasks() {}
}
