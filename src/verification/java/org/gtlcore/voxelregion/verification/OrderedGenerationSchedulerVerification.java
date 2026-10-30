package org.gtlcore.voxelregion.verification;

import org.gtlcore.gtlcore.common.worldgen.OrderedGenerationScheduler;
import org.gtlcore.gtlcore.common.worldgen.OrderedGenerationScheduler.ChunkArea;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone JDK-only concurrency checks; no Minecraft launch or external test dependency. */
public final class OrderedGenerationSchedulerVerification {

    private static final Executor INLINE = Runnable::run;
    private static final ChunkArea AREA = new ChunkArea(-8, -8, 8, 8);
    private static int checks;

    public static void main(String[] arguments) throws Exception {
        workerCountBoundsAreEnforced();
        passiveWorkKeepsItsNativeBehavior();
        preexistingAsyncAndFollowingNativeWorkAreFenced();
        disjointWorkAndWorldIdentitiesRunIndependently();
        overlapIsInclusiveAtIntegerLimits();
        cancellationCannotReleaseARegion();
        failuresReleaseOnlyTheirOwnResult();
        closingDrainsWaitingWorkOnNativeExecutor();
        overflowKeepsOrdering();
        blockedFeaturesLeaveCapacityForReadyWork();
        readyOverflowUsesOriginalExecutorAndKeepsFence();
        failedWorkerSubmissionCannotRaceNativeFallback();
        rejectedAndUnusualExecutorsCannotLeakLeases();
        resultCallbacksCanSubmitInlineWork();
        frontierRetainsIndependentPredecessors();
        inlineCompletionChainsDoNotRecurse();
        concurrentSubmittersKeepOverlappingAsyncWorkExclusive();
        System.out.println("OrderedGenerationScheduler verification PASS: " + checks + " checks");
    }

    private static void workerCountBoundsAreEnforced() {
        for (int workers : new int[] { 1, 2, 3, 8 }) {
            try (var scheduler = new OrderedGenerationScheduler(workers, 1)) {
                check(scheduler.statistics().reservedOffloads() == 0, "empty scheduler reserved capacity");
            }
        }
        for (int workers : new int[] { 0, 9 }) {
            try (var ignored = new OrderedGenerationScheduler(workers, 1)) {
                throw new AssertionError("invalid worker count accepted: " + workers);
            } catch (IllegalArgumentException expected) {
                checks++;
            }
        }
    }

    private static void passiveWorkKeepsItsNativeBehavior() throws Exception {
        try (var scheduler = new OrderedGenerationScheduler(2, 2)) {
            Object world = new Object();
            Thread caller = Thread.currentThread();
            var first = new CompletableFuture<Integer>();
            var second = new CompletableFuture<Integer>();
            var a = scheduler.submit(world, AREA, failExecutor(), false, () -> {
                check(Thread.currentThread() == caller, "passive caller thread changed");
                return first;
            });
            var b = scheduler.submit(world, AREA, failExecutor(), false, () -> second);
            check(scheduler.statistics().running() == 2, "ordinary async work unexpectedly serialized");
            check(!a.isDone() && !b.isDone(), "ordinary result completed early");
            second.complete(2);
            first.complete(1);
            check(await(a) == 1 && await(b) == 2, "ordinary values changed");
            drained(scheduler, 2);
        }
    }

    private static void preexistingAsyncAndFollowingNativeWorkAreFenced() throws Exception {
        try (var scheduler = new OrderedGenerationScheduler(2, 4)) {
            Object world = new Object();
            var old = new CompletableFuture<Integer>();
            var feature = new CompletableFuture<Integer>();
            var nativeExecutor = new ManualExecutor();
            CountDownLatch featureStarted = new CountDownLatch(1);
            var first = scheduler.submit(world, AREA, INLINE, false, () -> old);
            var second = scheduler.submit(world, AREA, nativeExecutor, true, () -> {
                featureStarted.countDown();
                return feature;
            });
            AtomicInteger lastStarted = new AtomicInteger();
            var third = scheduler.submit(world, AREA, nativeExecutor, false, () -> {
                lastStarted.incrementAndGet();
                return CompletableFuture.completedFuture(3);
            });
            check(featureStarted.getCount() == 1 && lastStarted.get() == 0, "overlap overtook old native future");
            old.complete(1);
            await(featureStarted);
            check(!second.isDone() && lastStarted.get() == 0, "lease ended at dispatch instead of future completion");
            feature.complete(2);
            // The worker may still be attaching its callback when the latch wakes the caller.
            // Await completion before inspecting the dependent executor's publication.
            check(await(second) == 2, "feature value changed");
            check(lastStarted.get() == 0, "dependent native work bypassed native executor");
            nativeExecutor.runAll();
            check(await(first) == 1 && await(second) == 2 && await(third) == 3, "overlap results changed");
            check(scheduler.statistics().delayed() == 2, "delay accounting incorrect");
            drained(scheduler, 3);
        }
    }

    private static void disjointWorkAndWorldIdentitiesRunIndependently() throws Exception {
        try (var scheduler = new OrderedGenerationScheduler(2, 4)) {
            Object world = new Object();
            var finish = new CompletableFuture<Integer>();
            CountDownLatch started = new CountDownLatch(2);
            var a = scheduler.submit(world, AREA, INLINE, true, () -> {
                started.countDown();
                return finish;
            });
            var b = scheduler.submit(world, new ChunkArea(9, -8, 25, 8), INLINE, true, () -> {
                started.countDown();
                return finish;
            });
            await(started);
            check(scheduler.statistics().activeOffloads() == 2, "disjoint regions failed to run together");
            finish.complete(7);
            check(await(a) == 7 && await(b) == 7, "disjoint results changed");
            Object equalWorldA = new String("dimension");
            Object equalWorldB = new String("dimension");
            var pending = new CompletableFuture<Integer>();
            CountDownLatch firstStarted = new CountDownLatch(1);
            var c = scheduler.submit(equalWorldA, AREA, INLINE, true, () -> {
                firstStarted.countDown();
                return pending;
            });
            await(firstStarted);
            var d = scheduler.submit(equalWorldB, AREA, failExecutor(), false,
                    () -> CompletableFuture.completedFuture(4));
            check(await(d) == 4, "distinct equal world identities interfered");
            pending.complete(3);
            check(await(c) == 3, "world result changed");
            drained(scheduler, 4);
        }
    }

    private static void overlapIsInclusiveAtIntegerLimits() {
        check(AREA.overlaps(new ChunkArea(8, 8, 20, 20)), "touching footprint cells did not overlap");
        check(!AREA.overlaps(new ChunkArea(9, 8, 20, 20)), "adjacent footprints overlapped");
        check(!new ChunkArea(Integer.MIN_VALUE, 0, -1, 0)
                .overlaps(new ChunkArea(0, 0, Integer.MAX_VALUE, 0)), "chunk comparison overflowed");
        try {
            new ChunkArea(1, 0, 0, 0);
            throw new AssertionError("inverted area accepted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void cancellationCannotReleaseARegion() throws Exception {
        try (var scheduler = new OrderedGenerationScheduler(1, 1)) {
            Object world = new Object();
            var real = new CompletableFuture<Integer>();
            CountDownLatch started = new CountDownLatch(1);
            var result = scheduler.submit(world, AREA, INLINE, true, () -> {
                started.countDown();
                return real;
            });
            await(started);
            var next = scheduler.submit(world, AREA, INLINE, false,
                    () -> CompletableFuture.completedFuture(2));
            check(!result.cancel(true), "caller cancelled live world mutation");
            // Even a caller misusing CompletableFuture.complete cannot alter the private fence.
            result.complete(99);
            check(!next.isDone(), "externally completed result released region early");
            real.complete(1);
            check(await(next) == 2, "region was not released after native completion");
            drained(scheduler, 2);
        }
    }

    private static void failuresReleaseOnlyTheirOwnResult() throws Exception {
        try (var scheduler = new OrderedGenerationScheduler(1, 2)) {
            Object world = new Object();
            var old = new CompletableFuture<Integer>();
            var first = scheduler.submit(world, AREA, INLINE, false, () -> old);
            var second = scheduler.submit(world, AREA, INLINE, true,
                    () -> CompletableFuture.completedFuture(2));
            old.completeExceptionally(new IllegalStateException("native failure"));
            expectFailure(first);
            check(await(second) == 2, "spatial predecessor failure poisoned unrelated generation");
            var third = scheduler.submit(world, AREA, INLINE, false, () -> {
                throw new IllegalArgumentException("supplier failure");
            });
            expectFailure(third);
            var fourth = scheduler.submit(world, AREA, INLINE, false, () -> null);
            expectFailure(fourth);
            check(scheduler.statistics().failed() == 3, "failure accounting incorrect");
            drained(scheduler, 4);
        }
    }

    private static void closingDrainsWaitingWorkOnNativeExecutor() throws Exception {
        var scheduler = new OrderedGenerationScheduler(1, 2);
        Object world = new Object();
        var nativeExecutor = new ManualExecutor();
        var old = new CompletableFuture<Integer>();
        var feature = new CompletableFuture<Integer>();
        var a = scheduler.submit(world, AREA, INLINE, false, () -> old);
        var b = scheduler.submit(world, AREA, nativeExecutor, true, () -> feature);
        scheduler.close();
        var c = scheduler.submit(world, AREA, nativeExecutor, true, () -> CompletableFuture.completedFuture(3));
        old.complete(1);
        check(!b.isDone() && !c.isDone(), "close bypassed pending generation");
        nativeExecutor.runAll();
        check(scheduler.statistics().running() == 1, "closed worker fallback lost operation");
        feature.complete(2);
        nativeExecutor.runAll();
        check(await(a) == 1 && await(b) == 2 && await(c) == 3, "closed drain values changed");
        check(scheduler.statistics().nativeFallbacks() >= 1, "worker shutdown did not use native fallback");
        drained(scheduler, 3);
    }

    private static void overflowKeepsOrdering() throws Exception {
        try (var scheduler = new OrderedGenerationScheduler(1, 1)) {
            Object world = new Object();
            var old = new CompletableFuture<Integer>();
            var a = scheduler.submit(world, AREA, INLINE, false, () -> old);
            List<Integer> order = new ArrayList<>();
            List<CompletableFuture<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                int value = i;
                results.add(scheduler.submit(world, AREA, INLINE, true, () -> {
                    order.add(value);
                    return CompletableFuture.completedFuture(value);
                }));
            }
            check(scheduler.statistics().reservedOffloads() == 0, "dependency waiters reserved worker capacity");
            check(scheduler.statistics().nativeFallbacks() == 0, "dependency waiters fell back before dispatch");
            old.complete(-1);
            check(await(a) == -1, "overflow predecessor value changed");
            for (int i = 0; i < results.size(); i++) check(await(results.get(i)) == i, "overflow result changed");
            check(order.equals(List.of(0, 1, 2, 3, 4, 5)), "overflow reordered overlapping features");
            drained(scheduler, 7);
        }
    }

    private static void blockedFeaturesLeaveCapacityForReadyWork() throws Exception {
        try (var scheduler = new OrderedGenerationScheduler(1, 1)) {
            Object world = new Object();
            var predecessor = new CompletableFuture<Integer>();
            var old = scheduler.submit(world, AREA, INLINE, false, () -> predecessor);
            var blocked = new ArrayList<CompletableFuture<Integer>>();
            for (int i = 0; i < 8; i++) {
                int value = i;
                blocked.add(scheduler.submit(world, AREA, INLINE, true,
                        () -> CompletableFuture.completedFuture(value)));
            }
            check(scheduler.statistics().reservedOffloads() == 0, "blocked wave consumed offload slots");
            Thread caller = Thread.currentThread();
            var actualThread = new AtomicReference<Thread>();
            var independent = scheduler.submit(world, new ChunkArea(32, 32, 32, 32), failExecutor(), true, () -> {
                actualThread.set(Thread.currentThread());
                return CompletableFuture.completedFuture(9);
            });
            check(await(independent) == 9 && actualThread.get() != caller,
                    "ready disjoint feature failed to use idle worker behind blocked wave");
            check(!old.isDone() && blocked.stream().noneMatch(CompletableFuture::isDone),
                    "ready work released an unrelated dependency fence");
            predecessor.complete(-1);
            check(await(old) == -1, "blocked predecessor changed");
            for (int i = 0; i < blocked.size(); i++) check(await(blocked.get(i)) == i, "blocked result changed");
            drained(scheduler, 10);
        }
    }

    private static void readyOverflowUsesOriginalExecutorAndKeepsFence() throws Exception {
        try (var scheduler = new OrderedGenerationScheduler(1, 1)) {
            Object world = new Object();
            var firstNative = new CompletableFuture<Integer>();
            var secondNative = new CompletableFuture<Integer>();
            CountDownLatch started = new CountDownLatch(2);
            var first = scheduler.submit(world, AREA, INLINE, true, () -> {
                started.countDown();
                return firstNative;
            });
            var second = scheduler.submit(world, new ChunkArea(32, 32, 32, 32), INLINE, true, () -> {
                started.countDown();
                return secondNative;
            });
            await(started);
            check(scheduler.statistics().reservedOffloads() == 2, "ready offload reservations not bounded");
            var owner = new ManualExecutor();
            var real = new CompletableFuture<Integer>();
            var calls = new AtomicInteger();
            ChunkArea overflowArea = new ChunkArea(64, 64, 64, 64);
            var overflow = scheduler.submit(world, overflowArea, owner, true, () -> {
                calls.incrementAndGet();
                return real;
            });
            var following = scheduler.submit(world, overflowArea, owner, false,
                    () -> CompletableFuture.completedFuture(4));
            check(calls.get() == 0 && owner.queue.size() == 1, "ready overflow bypassed original owner executor");
            check(scheduler.statistics().nativeFallbacks() == 1, "ready overflow fallback count incorrect");
            check(!overflow.cancel(true), "overflow cancellation released pending native work");
            owner.runAll();
            check(calls.get() == 1 && !overflow.isDone() && !following.isDone(),
                    "overflow completed or released its fence before its native future");
            real.complete(3);
            check(await(overflow) == 3 && !following.isDone(), "dependent overflow bypassed owner executor");
            owner.runAll();
            check(await(following) == 4, "overflow follower changed");
            check(scheduler.statistics().reservedOffloads() == 2, "native fallback released another job's permit");
            firstNative.complete(1);
            secondNative.complete(2);
            check(await(first) == 1 && await(second) == 2, "reserved native results changed");
            drained(scheduler, 4);
        }
    }

    private static void failedWorkerSubmissionCannotRaceNativeFallback() throws Exception {
        try (var scheduler = new OrderedGenerationScheduler(1, 1)) {
            ThreadPoolExecutor pool = workerPool(scheduler);
            AtomicInteger factories = new AtomicInteger();
            pool.setThreadFactory(action -> {
                // First addWorker fails without starting. execute then queues the action and
                // retries worker creation; that failure leaves a queued but unclaimed action.
                if (factories.incrementAndGet() == 1) return null;
                throw new IllegalStateException("thread factory failed after enqueue");
            });
            var owner = new ManualExecutor();
            var real = new CompletableFuture<Integer>();
            var calls = new AtomicInteger();
            Object world = new Object();
            var result = scheduler.submit(world, AREA, owner, true, () -> {
                calls.incrementAndGet();
                return real;
            });
            check(scheduler.statistics().reservedOffloads() == 0 && owner.queue.size() == 1,
                    "failed worker submission retained permit or lost native fallback");
            Runnable staleWorker = pool.getQueue().poll();
            check(staleWorker != null, "failed factory fixture did not leave its queued action");
            staleWorker.run();
            check(calls.get() == 0, "withdrawn worker action stole native fallback after permit release");
            owner.runAll();
            check(calls.get() == 1 && !result.isDone(), "native fallback ran twice or completed early");
            var following = scheduler.submit(world, AREA, owner, false,
                    () -> CompletableFuture.completedFuture(2));
            check(!result.cancel(true) && !following.isDone(), "failed dispatch lost native completion fence");
            real.complete(1);
            owner.runAll();
            check(await(result) == 1 && await(following) == 2, "failed-dispatch native values changed");
            drained(scheduler, 2);
        }
        try (var scheduler = new OrderedGenerationScheduler(1, 1)) {
            // Exercise the shutdown/execute race without also marking the scheduler closed.
            workerPool(scheduler).shutdown();
            var result = scheduler.submit(new Object(), AREA, failExecutor(), true,
                    () -> CompletableFuture.completedFuture(1));
            expectFailure(result);
            check(scheduler.statistics().nativeFallbacks() == 1, "rejected worker did not try native executor once");
            drained(scheduler, 1);
        }
    }

    private static ThreadPoolExecutor workerPool(OrderedGenerationScheduler scheduler) throws Exception {
        var field = OrderedGenerationScheduler.class.getDeclaredField("workers");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(scheduler);
    }

    private static void rejectedAndUnusualExecutorsCannotLeakLeases() throws Exception {
        var scheduler = new OrderedGenerationScheduler(1, 1);
        Object world = new Object();
        var gate = new CompletableFuture<Integer>();
        var a = scheduler.submit(world, AREA, INLINE, false, () -> gate);
        var b = scheduler.submit(world, AREA, failExecutor(), true, () -> CompletableFuture.completedFuture(2));
        var c = scheduler.submit(world, AREA, INLINE, false, () -> CompletableFuture.completedFuture(3));
        scheduler.close();
        gate.complete(1);
        check(await(a) == 1, "rejection predecessor changed");
        expectFailure(b);
        check(await(c) == 3, "rejection retained leaked guard");
        drained(scheduler, 3);
        try (var other = new OrderedGenerationScheduler(1, 1)) {
            var block = new CompletableFuture<Integer>();
            var real = new CompletableFuture<Integer>();
            var first = other.submit(world, AREA, INLINE, false, () -> block);
            var second = other.submit(world, AREA, action -> {
                action.run();
                throw new RejectedExecutionException("executor ran before throwing");
            }, true, () -> real);
            other.close();
            block.complete(1);
            var third = other.submit(world, AREA, INLINE, false, () -> CompletableFuture.completedFuture(3));
            check(!second.isDone() && !third.isDone(), "throw-after-run prematurely released native future");
            real.complete(2);
            check(await(first) == 1 && await(second) == 2 && await(third) == 3, "throw-after-run values changed");
            drained(other, 3);
        }
    }

    private static void resultCallbacksCanSubmitInlineWork() throws Exception {
        var scheduler = new OrderedGenerationScheduler(1, 1);
        Object world = new Object();
        var gate = new CompletableFuture<Integer>();
        CountDownLatch started = new CountDownLatch(1);
        var first = scheduler.submit(world, AREA, INLINE, true, () -> {
            started.countDown();
            return gate;
        });
        await(started);
        var second = scheduler.submit(world, AREA, INLINE, false, () -> CompletableFuture.completedFuture(2));
        scheduler.close();
        AtomicReference<Integer> observed = new AtomicReference<>();
        var callback = first.thenRun(() -> {
            check(second.isDone(), "result callback observed unreleased private fence");
            // Inline completed work must not wait on the already-finished parent lease.
            observed.set(scheduler.submit(world, AREA, INLINE, false,
                    () -> CompletableFuture.completedFuture(3)).join());
        });
        gate.complete(1);
        await(callback);
        check(observed.get() == 3, "nested native callback did not complete");
        drained(scheduler, 3);
    }

    private static void inlineCompletionChainsDoNotRecurse() throws Exception {
        var scheduler = new OrderedGenerationScheduler(1, 1);
        Object world = new Object();
        var gate = new CompletableFuture<Integer>();
        scheduler.submit(world, AREA, INLINE, false, () -> gate);
        List<CompletableFuture<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 512; i++) {
            int value = i;
            results.add(scheduler.submit(world, AREA, INLINE, true, () -> CompletableFuture.completedFuture(value)));
        }
        scheduler.close();
        check(scheduler.statistics().dependencyEdges() == 512, "dense chain retained quadratic dependency graph");
        gate.complete(-1);
        for (int i = 0; i < results.size(); i++) check(await(results.get(i)) == i, "inline chain value changed");
        drained(scheduler, 513);
    }

    private static void frontierRetainsIndependentPredecessors() throws Exception {
        try (var scheduler = new OrderedGenerationScheduler(2, 2)) {
            Object world = new Object();
            var left = new CompletableFuture<Integer>();
            var right = new CompletableFuture<Integer>();
            CountDownLatch started = new CountDownLatch(2);
            var a = scheduler.submit(world, new ChunkArea(-16, -8, -1, 8), INLINE, true, () -> {
                started.countDown();
                return left;
            });
            var b = scheduler.submit(world, new ChunkArea(1, -8, 16, 8), INLINE, true, () -> {
                started.countDown();
                return right;
            });
            await(started);
            var c = scheduler.submit(world, AREA, INLINE, false, () -> CompletableFuture.completedFuture(3));
            check(scheduler.statistics().dependencyEdges() == 2, "independent frontier predecessor discarded");
            right.complete(2);
            await(b);
            check(!c.isDone(), "wide region began before both predecessor footprints ended");
            left.complete(1);
            check(await(a) == 1 && await(c) == 3, "frontier values changed");
            drained(scheduler, 3);
        }
    }

    private static void concurrentSubmittersKeepOverlappingAsyncWorkExclusive() throws Exception {
        var completionThread = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "scheduler verification completion");
            thread.setDaemon(true);
            return thread;
        });
        try (var scheduler = new OrderedGenerationScheduler(2, 8)) {
            Object world = new Object();
            AtomicInteger live = new AtomicInteger();
            AtomicInteger violations = new AtomicInteger();
            var results = new ConcurrentLinkedQueue<CompletableFuture<Integer>>();
            var producerFailures = new ConcurrentLinkedQueue<Throwable>();
            CountDownLatch begin = new CountDownLatch(1);
            List<Thread> producers = new ArrayList<>();
            for (int producer = 0; producer < 4; producer++) {
                int producerId = producer;
                Thread thread = new Thread(() -> {
                    try {
                        begin.await();
                        for (int index = 0; index < 64; index++) {
                            int value = producerId * 64 + index;
                            ChunkArea footprint = new ChunkArea(-1 - value % 8, -8, 8, 1 + value % 8);
                            results.add(scheduler.submit(world, footprint, INLINE, true, () -> {
                                if (live.incrementAndGet() != 1) violations.incrementAndGet();
                                var completed = new CompletableFuture<Integer>();
                                completionThread.schedule(() -> {
                                    live.decrementAndGet();
                                    completed.complete(value);
                                }, 1, TimeUnit.MILLISECONDS);
                                return completed;
                            }));
                        }
                    } catch (Throwable failure) {
                        producerFailures.add(failure);
                    }
                }, "scheduler verification producer " + producer);
                thread.setDaemon(true);
                producers.add(thread);
                thread.start();
            }
            begin.countDown();
            for (Thread producer : producers) {
                producer.join(5000);
                check(!producer.isAlive(), "concurrent submitter blocked");
            }
            check(producerFailures.isEmpty(), "concurrent producer failed: " + producerFailures);
            check(results.size() == 256, "concurrent producer lost jobs");
            boolean[] seen = new boolean[256];
            for (var result : results) {
                int value = await(result);
                check(!seen[value], "concurrent result repeated");
                seen[value] = true;
            }
            check(violations.get() == 0 && live.get() == 0, "overlapping asynchronous mutations ran concurrently");
            drained(scheduler, 256);
        } finally {
            completionThread.shutdown();
        }
    }

    private static Executor failExecutor() {
        return action -> { throw new RejectedExecutionException("native executor rejected"); };
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) throws Exception {
        check(latch.await(5, TimeUnit.SECONDS), "worker did not start");
    }

    private static void expectFailure(CompletableFuture<?> future) throws Exception {
        try {
            await(future);
            throw new AssertionError("exceptional operation succeeded");
        } catch (java.util.concurrent.ExecutionException expected) {
            checks++;
        }
    }

    private static void drained(OrderedGenerationScheduler scheduler, long expected) {
        var stats = scheduler.statistics();
        check(stats.submitted() == expected && stats.completed() == expected, "incomplete operation accounting: " + stats);
        check(stats.waiting() == 0 && stats.running() == 0 && stats.activeOffloads() == 0 &&
                stats.reservedOffloads() == 0 && stats.activeWorlds() == 0, "generation lease retained: " + stats);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static final class ManualExecutor implements Executor {

        private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        private void runAll() {
            Runnable action;
            while ((action = queue.poll()) != null) action.run();
        }
    }
}
