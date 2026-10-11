// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

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

        default void close() {}

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
            work.close();
            return CompletableFuture.failedFuture(new PlanningBudget.Exhausted(PlanningBudget.Limit.QUEUE_LIMIT));
        }
        Job<T> job = new Job<>(work, budget);
        jobs.add(job);
        job.future.whenComplete((result, error) -> {
            if (error != null) budget.cancel();
            job.releaseAdmission();
            if (!job.queued.get()) job.dispose();
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
        for (Runnable task : executor.shutdownNow()) if (task instanceof QueuedTask queued) queued.abandon();
    }

    private interface QueuedTask extends Runnable {

        void abandon();
    }

    public final class Slice {

        private final Job<?> owner;
        private final long deadline = System.nanoTime() + nanosPerSlice;
        private final long workStarted;
        private int steps;

        private Slice(Job<?> owner) {
            this.owner = owner;
            workStarted = owner.budget.threadWork();
        }

        /** At least one bounded operation per slice; no tick gate or artificial sleep. */
        public boolean next() {
            return next(false);
        }

        public boolean nextCompilation() {
            return next(true);
        }

        /** The work charges its own step; only check cancellation and slice fairness here. */
        public boolean nextUncharged() {
            return next(false, false);
        }

        private boolean next(boolean compilation) {
            return next(compilation, true);
        }

        private boolean next(boolean compilation, boolean charge) {
            // An advance can perform many charged arithmetic operations. Count
            // their deterministic effort as well as continuation calls, so one
            // expensive order yields even before its wall-time slice expires.
            if (steps != 0 && (steps >= stepsPerSlice || owner.budget.threadWork() - workStarted >= stepsPerSlice ||
                    System.nanoTime() - deadline >= 0))
                return false;
            if (!charge) owner.budget.checkpoint();
            else if (compilation) owner.budget.compilationCheck();
            else owner.budget.check();
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
            return fork(PlanningBudget.Phase.BUILD, partitions);
        }

        public <R> CompletableFuture<List<R>> fork(PlanningBudget.Phase phase, List<? extends Supplier<R>> partitions) {
            if (partitions.size() > workers) throw new IllegalArgumentException("Too many parallel partitions");
            // Only immutable labels cross workers, never an active timing scope.
            var context = owner.budget.traceContext();
            var children = new ArrayList<CompletableFuture<R>>();
            for (Supplier<R> partition : partitions) {
                CompletableFuture<R> child = new CompletableFuture<>();
                children.add(child);
                try {
                    executor.execute(new QueuedTask() {

                        @Override
                        public void run() {
                            long start = enter();
                            try {
                                R value;
                                try (var timing = owner.budget.work(phase); var costs = owner.budget.trace(context)) {
                                    if (owner.future.isDone()) throw new java.util.concurrent.CancellationException();
                                    owner.budget.checkpoint();
                                    value = partition.get();
                                }
                                // A completed partition must have published its exclusive costs.
                                child.complete(value);
                            } catch (Throwable failure) {
                                child.completeExceptionally(failure);
                            } finally {
                                leave(start);
                            }
                        }

                        @Override
                        public void abandon() {
                            child.cancel(false);
                        }
                    });
                } catch (RuntimeException rejected) {
                    child.completeExceptionally(rejected);
                }
            }
            return CompletableFuture.allOf(children.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> children.stream().map(CompletableFuture::join).toList());
        }

        /** Workers claim bounded continuations from the same order queue; no nested pools or blocking joins. */
        public <R> CompletableFuture<List<R>> forkStealing(PlanningBudget.Phase phase, List<? extends Supplier<R>> partitions) {
            if (partitions.size() <= workers) return fork(phase, partitions);
            if (partitions.size() > 2 * workers) throw new IllegalArgumentException("Too many resident continuations");
            var next = new AtomicInteger();
            var outputs = new java.util.concurrent.atomic.AtomicReferenceArray<R>(partitions.size());
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            List<Supplier<Boolean>> runners = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) runners.add(() -> {
                int id;
                while ((id = next.getAndIncrement()) < partitions.size()) {
                    try {
                        owner.budget.checkpoint();
                        outputs.set(id, partitions.get(id).get());
                    } catch (Throwable stopped) {
                        failure.compareAndSet(null, stopped);
                    }
                }
                return true;
            });
            return fork(phase, runners).thenApply(ignored -> {
                if (failure.get() != null) throw new java.util.concurrent.CompletionException(failure.get());
                List<R> values = new ArrayList<>();
                for (int id = 0; id < partitions.size(); id++) values.add(outputs.get(id));
                return List.copyOf(values);
            });
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

    private final class Job<T> implements QueuedTask {

        private final Work<T> work;
        private final PlanningBudget budget;
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private final AtomicBoolean queued = new AtomicBoolean();
        private final AtomicBoolean disposed = new AtomicBoolean();
        private final AtomicBoolean admissionReleased = new AtomicBoolean();

        private Job(Work<T> work, PlanningBudget budget) {
            this.work = work;
            this.budget = budget;
        }

        private void enqueue() {
            if (future.isDone() || !queued.compareAndSet(false, true)) return;
            try {
                executor.execute(this);
            } catch (RuntimeException rejected) {
                queued.set(false);
                try { dispose(); }
                catch (Throwable cleanupFailure) { if (cleanupFailure != rejected) rejected.addSuppressed(cleanupFailure); }
                releaseAdmission();
                future.completeExceptionally(rejected);
            }
        }

        @Override
        public void run() {
            if (future.isDone()) {
                queued.set(false);
                dispose();
                return;
            }
            long start = enter();
            boolean again = false, terminal = false;
            T result = null;
            Throwable terminalFailure = null;
            CompletableFuture<?> waiting = null;
            try (var timing = budget.work(budget.phase())) {
                if (work.advance(new Slice(this))) {
                    result = work.result();
                    terminal = true;
                }
                else {
                    waiting = work.waitingFor();
                    again = waiting == null;
                }
            } catch (Throwable failure) {
                terminal = true;
                Throwable cause = failure;
                while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof PlanningBudget.Exhausted limit) {
                    try {
                        result = work.limited(limit);
                    } catch (Throwable limitedFailure) {
                        terminalFailure = limitedFailure;
                    }
                } else terminalFailure = failure;
            } finally {
                // Do not publish a successful/failed request while its own
                // synchronous resources and final cost scopes are still live.
                // External cancellation remains immediate; running child work
                // retains its existing cooperative release contract.
                if (terminalFailure != null) budget.cancel();
                if (terminal || future.isDone()) {
                    try { dispose(); }
                    catch (Throwable cleanupFailure) {
                        terminal = true;
                        if (terminalFailure == null) terminalFailure = cleanupFailure;
                        else if (terminalFailure != cleanupFailure) terminalFailure.addSuppressed(cleanupFailure);
                    }
                }
                leave(start);
                queued.set(false);
                // Cancellation can race the preceding check while this slice
                // still owns the queued flag.
                if (future.isDone()) dispose();
            }
            if (terminal) {
                // User completion callbacks can immediately submit the next
                // order. Do not depend on CompletableFuture callback ordering
                // to free this request's admission slot.
                releaseAdmission();
                if (terminalFailure == null) future.complete(result);
                else future.completeExceptionally(terminalFailure);
                return;
            }
            // Register only after releasing this slice: even immediately-completed
            // dependencies cannot run two coordinator slices concurrently.
            if (again) enqueue();
            // The coordinator owns failure handling too: it may have a verified
            // sibling to retain when one partition exhausts the shared budget.
            else if (waiting != null) waiting.whenComplete((ignored, failure) -> enqueue());
        }

        @Override
        public void abandon() {
            future.cancel(false);
            queued.set(false);
            dispose();
        }

        private void dispose() {
            if (disposed.compareAndSet(false, true)) work.close();
        }

        private void releaseAdmission() {
            if (admissionReleased.compareAndSet(false, true)) {
                jobs.remove(this);
                admitted.decrementAndGet();
            }
        }
    }
}
