package org.gtlcore.gtlcore.common.worldgen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Orders native generation work by its complete mutable chunk footprint. Registration must happen
 * at the original native dispatch point, including when offloading is disabled, so an offloaded
 * feature cannot overtake previously started asynchronous generation. This class never blocks a
 * native executor while waiting for predecessors.
 *
 * <p>
 * Only disjoint guarded operations may run together. Ordinary operations without an overlapping
 * guard retain their native execution behavior. A guard lasts until the operation's returned future
 * completes, including after disabling offloading or cancelling the caller-facing future.
 *
 * <p>
 * The caller supplies the actual region footprint, not just the target chunk. FULL/load callbacks
 * which can synchronously request neighboring generation must not hold a region guard.
 */
public final class OrderedGenerationScheduler implements AutoCloseable {

    private static final ThreadLocal<ArrayDeque<Runnable>> READY = new ThreadLocal<>();
    private final Object lock = new Object();
    private final IdentityHashMap<Object, List<Job<?>>> worlds = new IdentityHashMap<>();
    private final ThreadPoolExecutor workers;
    private final int maximumReservedOffloads;
    private int reservedOffloads;
    private boolean closed;
    private long submitted;
    private long guarded;
    private long delayed;
    private long dependencyEdges;
    private long completed;
    private long failed;
    private long offloaded;
    private long nativeFallbacks;
    private long waiting;
    private long running;
    private long peakRunning;
    private long activeOffloads;
    private long peakOffloads;

    /**
     * The queue and worker count bound reserved offloads, including those awaiting dependencies.
     * Overflow work uses its native executor but still participates in all conflict ordering.
     * Native fallback is not an additional worker pool and is not a global concurrency cap.
     */
    public OrderedGenerationScheduler(int workerCount, int queuedOffloads) {
        if (workerCount < 1 || workerCount > 2 || queuedOffloads < 1) {
            throw new IllegalArgumentException("Expected 1..2 workers and a positive offload queue");
        }
        maximumReservedOffloads = Math.addExact(workerCount, queuedOffloads);
        AtomicInteger threadIds = new AtomicInteger();
        workers = new ThreadPoolExecutor(workerCount, workerCount, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queuedOffloads), runnable -> {
                    Thread thread = new Thread(runnable, "GTL region generation " + threadIds.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
    }

    /** Inclusive chunk-coordinate bounds; comparison uses no overflow-prone coordinate subtraction. */
    public record ChunkArea(int minX, int minZ, int maxX, int maxZ) {

        public ChunkArea {
            if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("Inverted chunk footprint");
        }

        public boolean overlaps(ChunkArea other) {
            return minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ;
        }
    }

    public <T> CompletableFuture<T> submit(Object worldIdentity, ChunkArea area, Executor nativeExecutor,
                                           boolean offloadFeature, Supplier<CompletableFuture<T>> operation) {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(nativeExecutor, "nativeExecutor");
        Objects.requireNonNull(operation, "operation");
        Job<T> job;
        List<CompletableFuture<Void>> predecessors = new ArrayList<>();
        synchronized (lock) {
            List<Job<?>> active = worlds.computeIfAbsent(worldIdentity, ignored -> new ArrayList<>());
            boolean requested = offloadFeature && !closed;
            boolean needsGuard = requested;
            if (!needsGuard) {
                for (Job<?> previous : active) {
                    if (previous.guarded && area.overlaps(previous.area)) {
                        needsGuard = true;
                        break;
                    }
                }
            }
            // Wait for every older overlapping operation, including passive asynchronous NOISE
            // registered before the first parallel feature. The private fence never fails.
            if (needsGuard) {
                List<Job<?>> frontier = new ArrayList<>();
                for (int index = active.size() - 1; index >= 0; index--) {
                    Job<?> previous = active.get(index);
                    if (!area.overlaps(previous.area)) continue;
                    // A newer guarded job already waits for this older overlapping job. Retain
                    // only its frontier fence, avoiding a quadratic future graph for a dense wave.
                    boolean covered = false;
                    for (Job<?> later : frontier) {
                        if (later.area.overlaps(previous.area)) {
                            covered = true;
                            break;
                        }
                    }
                    if (covered) continue;
                    predecessors.add(previous.finished);
                    if (previous.guarded) frontier.add(previous);
                }
            }
            boolean reserve = requested && reservedOffloads < maximumReservedOffloads;
            if (reserve) reservedOffloads++;
            job = new Job<>(worldIdentity, area, nativeExecutor, operation, needsGuard, reserve);
            active.add(job);
            submitted++;
            if (needsGuard) guarded++;
            if (!predecessors.isEmpty()) delayed++;
            dependencyEdges += predecessors.size();
            if (requested && !reserve) nativeFallbacks++;
            waiting++;
        }
        if (predecessors.isEmpty()) {
            if (job.reservedOffload) {
                dispatch(job);
            } else {
                // Native passive work preserves its original thread and immediate call order.
                start(job, false);
            }
        } else {
            CompletableFuture.allOf(predecessors.toArray(CompletableFuture[]::new))
                    .thenRun(() -> ready(() -> dispatch(job)));
        }
        return job.result;
    }

    private void dispatch(Job<?> job) {
        if (job.reservedOffload) {
            try {
                workers.execute(() -> start(job, true));
                return;
            } catch (RejectedExecutionException rejected) {
                // Closing/queue saturation cannot drop a registered lease or bypass dependencies.
                synchronized (lock) {
                    nativeFallbacks++;
                }
            }
        }
        try {
            job.nativeExecutor.execute(() -> start(job, false));
        } catch (Throwable failure) {
            finishRejected(job, failure);
        }
    }

    private <T> void start(Job<T> job, boolean onWorker) {
        if (!job.started.compareAndSet(false, true)) return;
        synchronized (lock) {
            waiting--;
            running++;
            peakRunning = Math.max(peakRunning, running);
            if (onWorker) {
                job.offloadedStart = true;
                offloaded++;
                activeOffloads++;
                peakOffloads = Math.max(peakOffloads, activeOffloads);
            }
        }
        try {
            CompletableFuture<T> nativeFuture = Objects.requireNonNull(job.operation.get(), "native generation future");
            nativeFuture.whenComplete((value, failure) -> finish(job, value, failure));
        } catch (Throwable failure) {
            finish(job, null, failure);
        }
    }

    private <T> void finishRejected(Job<T> job, Throwable failure) {
        // A custom executor may run the action and then throw; the real future still owns its lease.
        if (!job.started.compareAndSet(false, true)) return;
        synchronized (lock) {
            waiting--;
            running++;
            peakRunning = Math.max(peakRunning, running);
        }
        finish(job, null, failure);
    }

    private <T> void finish(Job<T> job, T value, Throwable failure) {
        if (!job.ended.compareAndSet(false, true)) return;
        synchronized (lock) {
            List<Job<?>> active = worlds.get(job.world);
            active.remove(job);
            if (active.isEmpty()) worlds.remove(job.world);
            if (job.reservedOffload) reservedOffloads--;
            running--;
            if (job.offloadedStart) activeOffloads--;
            completed++;
            if (failure != null) failed++;
        }
        // Callbacks cannot run under the registry lock. Report only this operation's exception;
        // a failed unrelated spatial predecessor does not poison later generation.
        // Release the private ordering fence before invoking external result callbacks, which may
        // synchronously request another region operation. Its native operation has already ended.
        job.finished.complete(null);
        if (failure == null) job.result.complete(value);
        else job.result.completeExceptionally(failure);
    }

    /** Avoid recursive completion chains with inline executors without changing executor choice. */
    private static void ready(Runnable task) {
        ArrayDeque<Runnable> queue = READY.get();
        if (queue != null) {
            queue.addLast(task);
            return;
        }
        queue = new ArrayDeque<>();
        READY.set(queue);
        queue.addLast(task);
        try {
            Runnable next;
            while ((next = queue.pollFirst()) != null) next.run();
        } finally {
            READY.remove();
        }
    }

    /** Disables new offloads; accepted operations and their native completion fences drain normally. */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
        }
        // shutdownNow would discard queued generation and violate save dependencies.
        workers.shutdown();
    }

    public Statistics statistics() {
        synchronized (lock) {
            return new Statistics(submitted, guarded, delayed, dependencyEdges, completed, failed, offloaded, nativeFallbacks,
                    waiting, running, peakRunning, activeOffloads, peakOffloads,
                    reservedOffloads, worlds.size(), closed);
        }
    }

    public record Statistics(long submitted, long guarded, long delayed, long dependencyEdges, long completed, long failed,
                             long offloaded, long nativeFallbacks, long waiting, long running,
                             long peakRunning, long activeOffloads, long peakOffloads, int reservedOffloads,
                             int activeWorlds, boolean closed) {}

    private static final class Job<T> {

        private final Object world;
        private final ChunkArea area;
        private final Executor nativeExecutor;
        private final Supplier<CompletableFuture<T>> operation;
        private final boolean guarded;
        private final boolean reservedOffload;
        private boolean offloadedStart;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean ended = new AtomicBoolean();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private final CompletableFuture<T> result = new CompletableFuture<>() {

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }
        };

        private Job(Object world, ChunkArea area, Executor nativeExecutor, Supplier<CompletableFuture<T>> operation,
                    boolean guarded, boolean reservedOffload) {
            this.world = world;
            this.area = area;
            this.nativeExecutor = nativeExecutor;
            this.operation = operation;
            this.guarded = guarded;
            this.reservedOffload = reservedOffload;
        }
    }
}
