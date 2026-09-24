package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Bounded FIFO work slices shared by orders and their parallel construction batches. */
public final class PlanningScheduler implements AutoCloseable {

    public interface Work<T> {

        boolean advance(Slice slice);

        T result();

        default CompletableFuture<?> waitingFor() {
            return null;
        }

        default T limited(PlanningBudget.Exhausted limit) {
            throw limit;
        }
    }

    private final int workers;
    private final int maxRequests;
    private final int stepsPerSlice;
    private final long nanosPerSlice;
    private final ExecutorService executor;
    private final Set<Job<?>> jobs = ConcurrentHashMap.newKeySet();
    private final AtomicInteger admitted = new AtomicInteger();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger peakActive = new AtomicInteger();
    private final AtomicLong slices = new AtomicLong();
    private final AtomicLong activeNanos = new AtomicLong();

    public PlanningScheduler(int workers, int maxRequests, int stepsPerSlice, long nanosPerSlice) {
        if (workers <= 0 || maxRequests <= 0 || stepsPerSlice <= 0 || nanosPerSlice <= 0)
            throw new IllegalArgumentException("Invalid scheduling limits");
        this.workers = workers;
        this.maxRequests = maxRequests;
        this.stepsPerSlice = stepsPerSlice;
        this.nanosPerSlice = nanosPerSlice;
        var ids = new AtomicInteger();
        executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.multiplyExact(maxRequests, workers + 1)), task -> {
                    Thread thread = new Thread(task, "GTL-Graph-Planner-" + ids.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> CompletableFuture<T> submit(Work<T> work, PlanningBudget budget) {
        if (admitted.incrementAndGet() > maxRequests) {
            admitted.decrementAndGet();
            return CompletableFuture.failedFuture(new PlanningBudget.Exhausted(PlanningBudget.Limit.QUEUE_LIMIT));
        }
        Job<T> job = new Job<>(work, budget);
        jobs.add(job);
        job.future.whenComplete((result, error) -> {
            if (error != null) budget.cancel();
            jobs.remove(job);
            admitted.decrementAndGet();
        });
        job.enqueue();
        return job.future;
    }

    public int parallelism() {
        return workers;
    }

    public int peakActive() {
        return peakActive.get();
    }

    public long slices() {
        return slices.get();
    }

    public long activeNanos() {
        return activeNanos.get();
    }

    public int pendingRequests() {
        return admitted.get();
    }

    @Override
    public void close() {
        for (Job<?> job : jobs) job.future.cancel(false);
        executor.shutdownNow();
    }

    public final class Slice {

        private final Job<?> owner;
        private final long deadline = System.nanoTime() + nanosPerSlice;
        private int steps;

        private Slice(Job<?> owner) {
            this.owner = owner;
        }

        /** At least one bounded operation per slice; no tick gate or artificial sleep. */
        public boolean next() {
            if (steps != 0 && (steps >= stepsPerSlice || System.nanoTime() - deadline >= 0)) return false;
            owner.budget.check();
            steps++;
            return true;
        }

        public PlanningBudget budget() {
            return owner.budget;
        }

        public int parallelism() {
            return workers;
        }

        /** Private result buffers, merged in submission order. Never wait inside the pool. */
        public <R> CompletableFuture<List<R>> fork(List<? extends Supplier<R>> partitions) {
            if (partitions.size() > workers) throw new IllegalArgumentException("Too many parallel partitions");
            var children = new ArrayList<CompletableFuture<R>>();
            for (Supplier<R> partition : partitions) {
                CompletableFuture<R> child = new CompletableFuture<>();
                children.add(child);
                try {
                    executor.execute(() -> {
                        long start = enter();
                        try (var timing = owner.budget.work(PlanningBudget.Phase.BUILD)) {
                            if (owner.future.isDone()) throw new java.util.concurrent.CancellationException();
                            owner.budget.checkpoint();
                            child.complete(partition.get());
                        } catch (Throwable failure) {
                            child.completeExceptionally(failure);
                        } finally {
                            leave(start);
                        }
                    });
                } catch (RuntimeException rejected) {
                    child.completeExceptionally(rejected);
                }
            }
            return CompletableFuture.allOf(children.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> children.stream().map(CompletableFuture::join).toList());
        }
    }

    private long enter() {
        peakActive.accumulateAndGet(active.incrementAndGet(), Math::max);
        slices.incrementAndGet();
        return System.nanoTime();
    }

    private void leave(long started) {
        activeNanos.addAndGet(System.nanoTime() - started);
        active.decrementAndGet();
    }

    private final class Job<T> implements Runnable {

        private final Work<T> work;
        private final PlanningBudget budget;
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private final AtomicBoolean queued = new AtomicBoolean();

        private Job(Work<T> work, PlanningBudget budget) {
            this.work = work;
            this.budget = budget;
        }

        private void enqueue() {
            if (future.isDone() || !queued.compareAndSet(false, true)) return;
            try {
                executor.execute(this);
            } catch (RuntimeException rejected) {
                future.completeExceptionally(rejected);
            }
        }

        @Override
        public void run() {
            if (future.isDone()) return;
            long start = enter();
            boolean again = false;
            CompletableFuture<?> waiting = null;
            try (var timing = budget.work(budget.phase())) {
                if (work.advance(new Slice(this))) future.complete(work.result());
                else {
                    waiting = work.waitingFor();
                    again = waiting == null;
                }
            } catch (PlanningBudget.Exhausted limit) {
                try {
                    future.complete(work.limited(limit));
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                }
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            } finally {
                leave(start);
                queued.set(false);
            }
            // Register only after releasing this slice: even immediately-completed
            // dependencies cannot run two coordinator slices concurrently.
            if (again) enqueue();
            else if (waiting != null) waiting.whenComplete((ignored, failure) -> {
                if (failure != null) future.completeExceptionally(failure);
                else enqueue();
            });
        }
    }
}
