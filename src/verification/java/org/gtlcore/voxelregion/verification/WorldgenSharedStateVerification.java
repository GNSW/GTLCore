package org.gtlcore.voxelregion.verification;

import org.gtlcore.gtlcore.common.worldgen.WorldgenSharedState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Lightweight publication tests; the real GT/AE bytecode is additionally exercised in the pack. */
public final class WorldgenSharedStateVerification {

    public static void main(String[] args) throws Exception {
        Object level = new Object();
        check(!WorldgenSharedState.isInitialized(level), "Unseen world admitted");
        List<String> empty = List.of();
        check(WorldgenSharedState.cachedBiomeVeins(level, () -> empty) == empty, "Empty identity changed");
        check(!WorldgenSharedState.isInitialized(level), "Empty biome incorrectly freezes initialization");
        try {
            WorldgenSharedState.cachedBiomeVeins(level, () -> { throw new IllegalStateException("expected"); });
            throw new AssertionError("Initialization failure swallowed");
        } catch (IllegalStateException expected) {
            check(!WorldgenSharedState.isInitialized(level), "Failed initialization published");
        }

        // Model the legacy first-nonempty biome cache, including original mutable list identity.
        Map<Object, List<String>> legacy = new WeakHashMap<>();
        List<String> firstBiome = new ArrayList<>(List.of("first-vein", "second-vein"));
        List<String> original = WorldgenSharedState.cachedBiomeVeins(level,
                () -> legacy.computeIfAbsent(level, ignored -> firstBiome));
        check(original == firstBiome && WorldgenSharedState.isInitialized(level), "Original list not published");
        AtomicInteger active = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(3);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int thread = 0; thread < 3; thread++) futures.add(workers.submit(() -> {
                start.await();
                for (int call = 0; call < 2000; call++) {
                    List<String> result = WorldgenSharedState.cachedBiomeVeins(level, () -> {
                        check(active.incrementAndGet() == 1, "Concurrent access to legacy WeakHashMap/list");
                        try {
                            calls.incrementAndGet();
                            Thread.yield();
                            return legacy.computeIfAbsent(level, ignored -> List.of("different-biome"));
                        } finally { active.decrementAndGet(); }
                    });
                    check(result == firstBiome, "First-biome identity/order changed");
                }
                return null;
            }));
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            check(workers.awaitTermination(5, TimeUnit.SECONDS), "Worker did not stop");
        }
        check(calls.get() == 6000, "Cache calls dropped");
        check(firstBiome.equals(List.of("first-vein", "second-vein")), "Cache contents changed");
        check(!WorldgenSharedState.isInitialized(new Object()), "Initialization leaked between worlds");
        System.out.println("PASS: empty/failure admission, exact first-biome identity/order, 6000 concurrent calls");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
