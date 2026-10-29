package org.gtlcore.gtlcore.common.worldgen;

import org.gtlcore.gtlcore.GTLCore;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.data.worldgen.GTLayerPattern;
import com.gregtechceu.gtceu.api.data.worldgen.generator.veins.LayeredVeinGenerator;
import com.gregtechceu.gtceu.api.data.worldgen.generator.veins.StandardVeinGenerator;
import com.gregtechceu.gtceu.api.data.worldgen.generator.veins.VeinedVeinGenerator;
import com.gregtechceu.gtceu.api.data.worldgen.ores.GeneratedVein;
import com.gregtechceu.gtceu.api.data.worldgen.ores.GeneratedVeinMetadata;
import com.gregtechceu.gtceu.api.data.worldgen.ores.OreGenerator;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.google.common.cache.Cache;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/** Overlaps detached GT ore geometry while retaining native metadata, cache and placement order. */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ParallelOrePlanning {

    public static final String DISABLE_PROPERTY = "gtlcore.disableParallelOrePlanning";
    public static final String ENABLE_PROPERTY = "gtlcore.parallelOrePlanning";
    private static final int WINDOW = 3;
    private static final int MAX_LOOKAHEAD = 32;
    private static final Semaphore RETAINED_PLANS = new Semaphore(WINDOW);
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong PLANS = new AtomicLong();
    private static final AtomicLong BARRIERS = new AtomicLong();
    private static final AtomicLong CACHE_HITS = new AtomicLong();
    private static final AtomicLong PUBLICATIONS = new AtomicLong();
    private static final AtomicLong OWNER_FALLBACKS = new AtomicLong();
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final AtomicInteger PEAK = new AtomicInteger();
    private static ThreadPoolExecutor pool;

    private ParallelOrePlanning() {}

    public static boolean enabled(WorldGenLevel level) {
        // The paired real-pack four-CPU run showed no additional throughput benefit over region
        // scheduling. Keep this bounded, verified path opt-in rather than adding work by default.
        // An explicit disable override also supports the paired original/candidate diagnostic.
        String override = System.getProperty(DISABLE_PROPERTY);
        boolean requested = override == null ? Boolean.getBoolean(ENABLE_PROPERTY) : !Boolean.parseBoolean(override);
        return requested && Runtime.getRuntime().availableProcessors() > 1 &&
                CURRENT.get() == null && WorldgenSharedState.isInitialized(level.getLevel());
    }

    /** Only the audited Veined getter uses this context; it is never retained by a placer. */
    public static RandomState capturedRandomState() {
        Context context = CURRENT.get();
        return context == null ? null : context.randomState;
    }

    public static List<GeneratedVein> consume(WorldGenLevel level, OreGenerator generator,
                                              List<ChunkPos> origins,
                                              Function<ChunkPos, List<GeneratedVeinMetadata>> metadata,
                                              Cache<ChunkPos, List<GeneratedVein>> cache,
                                              Function<ChunkPos, List<GeneratedVein>> originalOrigin) {
        Context context = Context.capture(level);
        if (context == null) return nativeOrigins(origins, originalOrigin);
        CALLS.incrementAndGet();
        List<GeneratedVein> result = new ArrayList<>();
        List<Job> pending = new ArrayList<>(WINDOW);
        try {
            for (ChunkPos origin : origins) {
                // containsKey does not record Guava access recency. A hit is a barrier: retain the
                // original get/load behavior even if a soft value is evicted immediately afterwards.
                if (cache.asMap().containsKey(origin)) {
                    flush(pending, cache, result);
                    CACHE_HITS.incrementAndGet();
                    result.addAll(originalOrigin.apply(origin));
                    continue;
                }
                List<GeneratedVeinMetadata> entries;
                boolean eligible;
                try {
                    entries = metadata.apply(origin);
                    eligible = pure(entries);
                } catch (Throwable preparationFailure) {
                    flush(pending, cache, result);
                    // In GT these calls execute inside the generated-vein cache's loader. Route
                    // the captured failure through that same cache contract, without a second
                    // metadata lookup or changing checked/unchecked/error wrapping behavior.
                    result.addAll(load(cache, origin, () -> rethrow(preparationFailure)));
                    continue;
                }
                boolean retained = !entries.isEmpty();
                if (!eligible || (retained && !RETAINED_PLANS.tryAcquire())) {
                    flush(pending, cache, result);
                    BARRIERS.incrementAndGet();
                    // Geodes and unreviewed generators may mutate terrain during generate(). No
                    // later metadata/height/biome lookup is allowed to cross this call.
                    // Metadata was already selected at the original encounter position. Reusing
                    // it here avoids another cache access/recency update before native geometry.
                    result.addAll(load(cache, origin, () -> generator.generateOres(level, entries, origin)));
                    continue;
                }
                Job job = new Job(context, level, generator, origin, List.copyOf(entries), retained);
                pending.add(job);
                if (retained) {
                    PLANS.incrementAndGet();
                    try {
                        executor().execute(job);
                    } catch (RejectedExecutionException rejected) {
                        job.run();
                    }
                } else {
                    // Empty metadata has no geometry or RNG work. Keep its native empty result
                    // in publication order without consuming a retained-map permit or worker.
                    job.run();
                }
                if (pending.size() == MAX_LOOKAHEAD || pending.stream().filter(plan -> plan.retained).count() == WINDOW)
                    flush(pending, cache, result);
            }
            flush(pending, cache, result);
            return List.copyOf(result);
        } finally {
            // Cache races or an earlier failure must not leave workers retaining the generation
            // context or their large maps after the caller releases its native terrain ownership.
            for (Job job : pending) job.drain();
            pending.clear();
        }
    }

    private static List<GeneratedVein> nativeOrigins(List<ChunkPos> origins,
                                                     Function<ChunkPos, List<GeneratedVein>> original) {
        List<GeneratedVein> result = new ArrayList<>();
        for (ChunkPos origin : origins) result.addAll(original.apply(origin));
        return List.copyOf(result);
    }

    private static boolean pure(List<GeneratedVeinMetadata> entries) {
        for (GeneratedVeinMetadata entry : entries) {
            Class<?> provider = entry.definition().clusterSize().getClass();
            // Compound/custom IntProviders can invoke callbacks. Only these native leaf samplers
            // operate exclusively on the task's private RNG; everything else remains native.
            if (provider != ConstantInt.class && provider != UniformInt.class) return false;
            Class<?> type = entry.definition().veinGenerator().getClass();
            if (type != StandardVeinGenerator.class && type != LayeredVeinGenerator.class && type != VeinedVeinGenerator.class)
                return false;
            if (type == LayeredVeinGenerator.class) {
                var layered = (LayeredVeinGenerator) entry.definition().veinGenerator();
                synchronized (layered) {
                    // The native lazy initializer can run KubeJS suppliers. Leave its first/empty
                    // invocation on the original owner; a worker may only observe published data.
                    if (layered.layerPatterns == null || layered.layerPatterns.isEmpty()) return false;
                    for (GTLayerPattern pattern : layered.layerPatterns) {
                        if (pattern.getClass() != GTLayerPattern.class) return false;
                        for (GTLayerPattern.Layer layer : pattern.layers) {
                            if (layer.getClass() != GTLayerPattern.Layer.class) return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private static void flush(List<Job> pending, Cache<ChunkPos, List<GeneratedVein>> cache,
                              List<GeneratedVein> result) {
        for (Job job : pending) {
            try {
                // This is the original cache operation and encounter order. Concurrent existing
                // entries still win; only the loader's detached calculation was moved earlier.
                result.addAll(load(cache, job.origin, job::get));
                PUBLICATIONS.incrementAndGet();
            } finally {
                job.drain();
            }
        }
        pending.clear();
    }

    private static List<GeneratedVein> load(Cache<ChunkPos, List<GeneratedVein>> cache, ChunkPos origin,
                                            Callable<List<GeneratedVein>> loader) {
        try {
            return cache.get(origin, loader);
        } catch (ExecutionException failure) {
            GTCEu.LOGGER.error("Cannot create vein in chunk {}", origin, failure);
            return List.of();
        }
    }

    private static <T> T rethrow(Throwable failure) throws Exception {
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) throw exception;
        throw new IllegalStateException(failure);
    }

    private static synchronized ThreadPoolExecutor executor() {
        if (pool == null || pool.isShutdown()) {
            int workers = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 1));
            pool = new ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(WINDOW), task -> {
                        Thread thread = new Thread(task, "GTL-Ore-Plan-" + THREAD_IDS.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            pool.allowCoreThreadTimeOut(true);
        }
        return pool;
    }

    private static final class Job implements Runnable {

        final Context context;
        final WorldGenLevel ownerLevel;
        final OreGenerator generator;
        final ChunkPos origin;
        final List<GeneratedVeinMetadata> entries;
        final boolean retained;
        final AtomicBoolean claimed = new AtomicBoolean();
        final AtomicBoolean released = new AtomicBoolean();
        final CountDownLatch done = new CountDownLatch(1);
        List<GeneratedVein> value;
        Throwable failure;

        Job(Context context, WorldGenLevel ownerLevel, OreGenerator generator, ChunkPos origin,
            List<GeneratedVeinMetadata> entries, boolean retained) {
            this.context = context;
            this.ownerLevel = ownerLevel;
            this.generator = generator;
            this.origin = origin;
            this.entries = entries;
            this.retained = retained;
        }

        @Override
        public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            Context previous = CURRENT.get();
            CURRENT.set(context);
            int active = ACTIVE.incrementAndGet();
            PEAK.accumulateAndGet(active, Math::max);
            try {
                // Native GT creates a separate seed^origin RNG for EACH metadata entry, then
                // newRandom() forks from its first nextLong. Do not replace this with one shared RNG.
                value = generator.generateOres(context.view, entries, origin);
            } catch (Throwable error) {
                failure = error;
            } finally {
                ACTIVE.decrementAndGet();
                if (previous == null) CURRENT.remove();
                else CURRENT.set(previous);
                done.countDown();
            }
        }

        List<GeneratedVein> get() throws Exception {
            await();
            if (failure instanceof DetachedWorldAccess) {
                // The proxy failed before any world operation could happen. Native generateOres
                // creates fresh per-entry RNGs, so retrying on the owner preserves the original
                // result instead of turning a newly introduced world read into missing ore.
                OWNER_FALLBACKS.incrementAndGet();
                return generator.generateOres(ownerLevel, entries, origin);
            }
            if (failure instanceof Error error) throw error;
            if (failure instanceof Exception exception) throw exception;
            if (failure != null) throw new IllegalStateException(failure);
            return value;
        }

        void await() {
            // Participate when the worker has not claimed this job; never wait while owning a
            // shared pool permit needed by a nested density slice.
            run();
            boolean interrupted = false;
            while (true) {
                try {
                    done.await();
                    break;
                } catch (InterruptedException interruption) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        void drain() {
            await();
            if (retained && released.compareAndSet(false, true)) RETAINED_PLANS.release();
        }
    }

    private static final class Context {

        private static final Method SEED = method(WorldGenLevel::getSeed);
        private static final Method MIN = method(WorldGenLevel::getMinBuildHeight);
        private static final Method MAX = method(WorldGenLevel::getMaxBuildHeight);
        private static final Method HEIGHT = method(WorldGenLevel::getHeight);
        private static final Method OUTSIDE = method(level -> level.isOutsideBuildHeight(0));
        private static final Method LEVEL = method(WorldGenLevel::getLevel);
        final RandomState randomState;
        final WorldGenLevel view;

        static Context capture(WorldGenLevel original) {
            if (original instanceof WorldGenRegion region && Blender.of(region) != Blender.empty()) return null;
            ServerLevel level = original.getLevel();
            return new Context(level, original.getSeed(), original.getMinBuildHeight(), original.getMaxBuildHeight(),
                    level.getChunkSource().randomState());
        }

        Context(ServerLevel owner, long seed, int minimum, int maximum, RandomState randomState) {
            this.randomState = randomState;
            this.view = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                    new Class<?>[] { WorldGenLevel.class }, (proxy, method, arguments) -> {
                        if (method.equals(SEED)) return seed;
                        if (method.equals(MIN)) return minimum;
                        if (method.equals(MAX)) return maximum;
                        if (method.equals(HEIGHT)) return maximum - minimum;
                        if (method.equals(OUTSIDE)) {
                            int y = (int) arguments[0];
                            return y < minimum || y >= maximum;
                        }
                        // The exact native Veined class only follows this with the stable
                        // getChunkSource getter; the next randomState call uses our captured value.
                        if (method.equals(LEVEL)) return owner;
                        throw new DetachedWorldAccess(method);
                    });
        }

        private static Method method(Consumer<WorldGenLevel> call) {
            AtomicReference<Method> captured = new AtomicReference<>();
            WorldGenLevel probe = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                    new Class<?>[] { WorldGenLevel.class }, (proxy, method, arguments) -> {
                        captured.set(method);
                        if (method.getReturnType() == long.class) return 0L;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == boolean.class) return false;
                        return null;
                    });
            call.accept(probe);
            return captured.get();
        }
    }

    private static final class DetachedWorldAccess extends RuntimeException {

        DetachedWorldAccess(Method method) {
            super("Detached GT ore geometry attempted world access: " + method);
        }
    }

    public record Statistics(long calls, long plannedOrigins, long barriers, long cacheHits,
                             long publications, long ownerFallbacks, int activeWorkers, int peakWorkers) {}

    public static Statistics statistics() {
        return new Statistics(CALLS.get(), PLANS.get(), BARRIERS.get(), CACHE_HITS.get(), PUBLICATIONS.get(),
                OWNER_FALLBACKS.get(), ACTIVE.get(), PEAK.get());
    }

    @SubscribeEvent
    public static synchronized void stopped(ServerStoppedEvent event) {
        // Do not interrupt queued/accepted plans: callers release their own retained-map permits.
        if (pool != null) pool.shutdown();
        pool = null;
    }
}
