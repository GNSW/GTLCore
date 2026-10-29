package org.gtlcore.gtlcore.common.worldgen;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Parallel sampling only: the caller retains the original random, enumeration and placement order. */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ParallelOreDensity {

    public static final int MAX_TILE_POINTS = 16_384;
    private static final int PARALLEL_THRESHOLD = 8_192;
    private static final int MIN_SLICE_POINTS = 2_048;
    private static final int MAX_GRAPH_NODES = 4_096;
    private static final String NODE_PREFIX = "net.minecraft.world.level.levelgen.DensityFunctions$";
    private static final Set<String> KNOWN_NODES = Set.of(
            "Ap2", "BeardifierMarker", "BlendAlpha", "BlendDensity", "BlendOffset", "Clamp", "Constant",
            "HolderHolder", "Mapped", "Marker", "MulOrAdd", "Noise", "RangeChoice", "Shift", "ShiftA", "ShiftB",
            "ShiftedNoise", "WeirdScaledSampler", "YClampedGradient");
    private static final Map<IdentityKey, Boolean> VALIDATED = new HashMap<>();
    private static final ReferenceQueue<DensityFunction> COLLECTED = new ReferenceQueue<>();
    private static final Object POOL_LOCK = new Object();
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final AtomicLong SAMPLE_CALLS = new AtomicLong();
    private static final AtomicLong PARALLEL_TILES = new AtomicLong();
    private static final AtomicLong SAMPLED_POINTS = new AtomicLong();
    private static final AtomicLong REJECTED_GRAPHS = new AtomicLong();
    private static final AtomicInteger ACTIVE_WORKERS = new AtomicInteger();
    private static final AtomicInteger PEAK_WORKERS = new AtomicInteger();
    private static final AtomicLong MASKED_CALLS = new AtomicLong();
    private static final AtomicLong MASKED_POINTS = new AtomicLong();
    private static final AtomicLong MASKED_PARALLEL_BATCHES = new AtomicLong();
    private static final AtomicLong MASKED_WORKER_SLICES = new AtomicLong();
    private static ThreadPoolExecutor pool;

    private static final ClassValue<Descriptor> DESCRIPTORS = new ClassValue<>() {

        @Override
        protected Descriptor computeValue(Class<?> type) {
            String name = type.getName();
            if (type.getClassLoader() != DensityFunction.class.getClassLoader() || !name.startsWith(NODE_PREFIX) || !KNOWN_NODES.contains(name.substring(NODE_PREFIX.length()))) return new Descriptor(false, new Field[0]);
            var children = new ArrayList<Field>();
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                // A mixin can add a mutable numeric cache to an otherwise known vanilla node.
                // Such a node must keep the serial path, even when its class name still matches.
                if (!Modifier.isFinal(field.getModifiers())) return new Descriptor(false, new Field[0]);
                Class<?> fieldType = field.getType();
                if (fieldType.isPrimitive() || fieldType.isEnum()) continue;
                if (fieldType != DensityFunction.class && fieldType != Holder.class && fieldType != DensityFunction.NoiseHolder.class)
                    return new Descriptor(false, new Field[0]);
                if (!field.trySetAccessible()) return new Descriptor(false, new Field[0]);
                children.add(field);
            }
            return new Descriptor(true, children.toArray(Field[]::new));
        }
    };

    /**
     * Strictly checks the original graph before using mapAll. NoiseChunk caches can map themselves
     * into ordinary markers, so trusting only the visitor's reconstructed nodes would be unsafe.
     * Registry references, splines and unknown/modded nodes retain the caller's serial path.
     */
    public static boolean pureKnown(DensityFunction function) {
        if (function == null || !DESCRIPTORS.get(function.getClass()).allowed()) {
            REJECTED_GRAPHS.incrementAndGet();
            return false;
        }
        synchronized (VALIDATED) {
            while (true) {
                var stale = COLLECTED.poll();
                if (stale == null) break;
                VALIDATED.remove(stale);
            }
            Boolean cached = VALIDATED.get(new IdentityKey(function, false));
            if (cached != null) return cached;
        }
        boolean accepted;
        try {
            accepted = inspect(function, new IdentityHashMap<>(), 0, new int[1]);
            if (accepted) {
                int[] visits = { 0 };
                function.mapAll(new DensityFunction.Visitor() {

                    @Override
                    public DensityFunction apply(DensityFunction node) {
                        if (++visits[0] > MAX_GRAPH_NODES || !DESCRIPTORS.get(node.getClass()).allowed()) throw RejectedGraph.INSTANCE;
                        return node;
                    }

                    @Override
                    public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noise) {
                        if (!knownNoise(noise)) throw RejectedGraph.INSTANCE;
                        return noise;
                    }
                });
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            accepted = false;
        }
        synchronized (VALIDATED) {
            if (VALIDATED.size() >= 256) VALIDATED.clear();
            VALIDATED.put(new IdentityKey(function, true), accepted);
        }
        if (!accepted) REJECTED_GRAPHS.incrementAndGet();
        return accepted;
    }

    private static boolean inspect(DensityFunction function, IdentityHashMap<DensityFunction, Boolean> seen,
                                   int depth, int[] nodes) throws IllegalAccessException {
        Boolean complete = seen.get(function);
        if (complete != null) return complete;
        Descriptor descriptor = DESCRIPTORS.get(function.getClass());
        if (!descriptor.allowed() || depth > 128 || ++nodes[0] > MAX_GRAPH_NODES) return false;
        seen.put(function, false);
        for (Field field : descriptor.children()) {
            Object child = field.get(function);
            if (child instanceof DensityFunction density) {
                if (!inspect(density, seen, depth + 1, nodes)) return false;
            } else if (child instanceof Holder.Direct<?> direct) {
                if (!(direct.value() instanceof DensityFunction density) || !inspect(density, seen, depth + 1, nodes)) return false;
            } else if (child instanceof DensityFunction.NoiseHolder noise) {
                if (!knownNoise(noise)) return false;
            } else return false;
        }
        seen.put(function, true);
        return true;
    }

    private static boolean knownNoise(DensityFunction.NoiseHolder holder) {
        return holder != null && (holder.noise() == null || holder.noise().getClass() == NormalNoise.class);
    }

    /**
     * Fills output[0..count) using the unchanged compute method and Blender.empty(). Enumeration
     * matches BlockPos.betweenClosed: X first, then Y, then Z. No world, random source or chunk
     * context may enter this API; callers must first confirm their original blender is empty.
     */
    public static void sample(DensityFunction function, int baseX, int baseY, int baseZ,
                              int sizeX, int sizeY, int startIndex, int count, double[] output) {
        Objects.requireNonNull(output, "output");
        if (sizeX < 1 || sizeY < 1 || startIndex < 0 || count < 0 || count > MAX_TILE_POINTS || output.length < count)
            throw new IllegalArgumentException("Invalid density tile");
        long plane = (long) sizeX * sizeY, last = (long) startIndex + Math.max(0, count - 1);
        if ((long) baseX + sizeX - 1 > Integer.MAX_VALUE || (long) baseY + sizeY - 1 > Integer.MAX_VALUE || (long) baseZ + last / plane > Integer.MAX_VALUE) throw new IllegalArgumentException("Density coordinates overflow");
        if (!pureKnown(function)) throw new IllegalArgumentException("Density graph is not approved for detached sampling");
        if (count == 0) return;
        SAMPLE_CALLS.incrementAndGet();
        SAMPLED_POINTS.addAndGet(count);
        var failure = new AtomicReference<Throwable>();
        int workers = Math.min(8, Math.max(0, Runtime.getRuntime().availableProcessors() - 1));
        int slices = count < PARALLEL_THRESHOLD ? 1 : Math.min(workers + 1, count / MIN_SLICE_POINTS);
        ThreadPoolExecutor executor = slices > 1 ? executor(workers) : null;
        if (slices > 1) PARALLEL_TILES.incrementAndGet();
        var done = new CountDownLatch(slices - 1);
        int firstEnd = count / slices;
        for (int slice = 1; slice < slices; slice++) {
            int from = count * slice / slices, to = count * (slice + 1) / slices;
            var claimed = new AtomicBoolean();
            Thread caller = Thread.currentThread();
            Runnable work = () -> {
                if (!claimed.compareAndSet(false, true)) return;
                boolean onWorker = Thread.currentThread() != caller;
                if (onWorker) PEAK_WORKERS.accumulateAndGet(ACTIVE_WORKERS.incrementAndGet(), Math::max);
                try {
                    compute(function, baseX, baseY, baseZ, sizeX, sizeY, startIndex, from, to, output, failure);
                } finally {
                    if (onWorker) ACTIVE_WORKERS.decrementAndGet();
                    done.countDown();
                }
            };
            try {
                executor.execute(work);
            } catch (RejectedExecutionException saturatedOrStopping) {
                // The bounded queue never grows with the cube size. Rejection cannot drop work.
                work.run();
            } catch (Throwable dispatchFailure) {
                failure.compareAndSet(null, dispatchFailure);
                // Idempotent claim also handles a thread factory failing after enqueueing.
                work.run();
            }
        }
        compute(function, baseX, baseY, baseZ, sizeX, sizeY, startIndex, 0, firstEnd, output, failure);
        boolean interrupted = false;
        while (true) {
            try {
                done.await();
                break;
            } catch (InterruptedException interruption) {
                interrupted = true;
                failure.compareAndSet(null, interruption);
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        Throwable problem = failure.get();
        if (problem instanceof Error error) throw error;
        if (problem instanceof RuntimeException runtime) throw runtime;
        if (problem != null) throw new IllegalStateException("Density tile interrupted", problem);
    }

    /**
     * Samples only selected points of a previously validated tile. Mask and output indexes are
     * relative to this tile, while startIndex addresses the original X/Y/Z cube. The caller must
     * leave the mask unchanged until this method returns. Only true means every selected output
     * is ready; false requests the existing on-demand path and does not write output.
     */
    public static boolean sampleMasked(DensityFunction function, int baseX, int baseY, int baseZ,
                                       int sizeX, int sizeY, int startIndex, int count,
                                       boolean[] selected, double[] output) {
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(output, "output");
        if (sizeX < 1 || sizeY < 1 || startIndex < 0 || count < 0 || count > MAX_TILE_POINTS ||
                selected.length < count || output.length < count)
            throw new IllegalArgumentException("Invalid masked density tile");
        long plane = (long) sizeX * sizeY, last = (long) startIndex + Math.max(0, count - 1);
        if ((long) baseX + sizeX - 1 > Integer.MAX_VALUE || (long) baseY + sizeY - 1 > Integer.MAX_VALUE ||
                (long) baseZ + last / plane > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Density coordinates overflow");
        int selectedCount = 0;
        for (int i = 0; i < count; i++) if (selected[i]) selectedCount++;
        int workers = Math.min(8, Math.max(0, Runtime.getRuntime().availableProcessors() - 1));
        if (selectedCount < PARALLEL_THRESHOLD || workers == 0 || !pureKnown(function)) return false;
        int slices = Math.min(workers + 1, selectedCount / MIN_SLICE_POINTS);
        var samplers = new VeinedRidgeSampler[slices];
        // Construct independent mutable leaf caches before dispatch. No captured random source,
        // world or FunctionContext is shared with a worker, and unsupported graphs stay native.
        try {
            for (int i = 0; i < slices; i++) {
                samplers[i] = VeinedRidgeSampler.create(function);
                if (samplers[i] == null) {
                    for (int j = 0; j < i; j++) samplers[j].close();
                    return false;
                }
            }
        } catch (Throwable failure) {
            for (VeinedRidgeSampler sampler : samplers) if (sampler != null) sampler.close();
            throw failure;
        }
        // Partition by selected work, retaining contiguous X-fast intervals for the axis caches.
        int[] boundaries = new int[slices + 1];
        boundaries[slices] = count;
        int seen = 0, nextSlice = 1;
        for (int i = 0; i < count && nextSlice < slices; i++) {
            if (selected[i]) seen++;
            if (seen == selectedCount * nextSlice / slices) boundaries[nextSlice++] = i + 1;
        }
        var failure = new AtomicReference<Throwable>();
        var done = new CountDownLatch(slices - 1);
        ThreadPoolExecutor executor;
        try {
            executor = executor(workers);
        } catch (Throwable unavailable) {
            for (VeinedRidgeSampler sampler : samplers) sampler.close();
            throw unavailable;
        }
        MASKED_CALLS.incrementAndGet();
        for (int slice = 1; slice < slices; slice++) {
            int from = boundaries[slice], to = boundaries[slice + 1];
            VeinedRidgeSampler sampler = samplers[slice];
            var claimed = new AtomicBoolean();
            Thread caller = Thread.currentThread();
            Runnable work = () -> {
                if (!claimed.compareAndSet(false, true)) return;
                boolean onWorker = Thread.currentThread() != caller;
                if (onWorker) {
                    MASKED_WORKER_SLICES.incrementAndGet();
                    PEAK_WORKERS.accumulateAndGet(ACTIVE_WORKERS.incrementAndGet(), Math::max);
                }
                try {
                    computeMasked(sampler, baseX, baseY, baseZ, sizeX, sizeY, startIndex,
                            from, to, selected, output, failure);
                } finally {
                    if (onWorker) ACTIVE_WORKERS.decrementAndGet();
                    done.countDown();
                }
            };
            try {
                executor.execute(work);
            } catch (RejectedExecutionException saturatedOrStopping) {
                work.run();
            } catch (Throwable dispatchFailure) {
                failure.compareAndSet(null, dispatchFailure);
                work.run();
            }
        }
        computeMasked(samplers[0], baseX, baseY, baseZ, sizeX, sizeY, startIndex,
                0, boundaries[1], selected, output, failure);
        boolean interrupted = false;
        while (true) {
            try {
                done.await();
                break;
            } catch (InterruptedException interruption) {
                interrupted = true;
                failure.compareAndSet(null, interruption);
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        Throwable problem = failure.get();
        if (problem instanceof Error error) throw error;
        if (problem instanceof RuntimeException runtime) throw runtime;
        if (problem != null) throw new IllegalStateException("Masked density tile interrupted", problem);
        MASKED_POINTS.addAndGet(selectedCount);
        MASKED_PARALLEL_BATCHES.incrementAndGet();
        return true;
    }

    private static void computeMasked(VeinedRidgeSampler sampler, int baseX, int baseY, int baseZ,
                                      int sizeX, int sizeY, int startIndex, int from, int to,
                                      boolean[] selected, double[] output, AtomicReference<Throwable> failure) {
        try {
            long index = (long) startIndex + from;
            int x = (int) (index % sizeX), y = (int) (index / sizeX % sizeY), z = (int) (index / ((long) sizeX * sizeY));
            var context = new Point();
            for (int i = from; i < to; i++) {
                if ((i & 63) == 0 && failure.get() != null) return;
                if (selected[i]) {
                    context.x = baseX + x;
                    context.y = baseY + y;
                    context.z = baseZ + z;
                    output[i] = sampler.sample(context);
                }
                if (++x == sizeX) {
                    x = 0;
                    if (++y == sizeY) {
                        y = 0;
                        z++;
                    }
                }
            }
        } catch (Throwable problem) {
            failure.compareAndSet(null, problem);
        } finally {
            try {
                sampler.close();
            } catch (Throwable closeFailure) {
                failure.compareAndSet(null, closeFailure);
            }
        }
    }

    private static void compute(DensityFunction function, int baseX, int baseY, int baseZ, int sizeX, int sizeY,
                                int startIndex, int from, int to, double[] output, AtomicReference<Throwable> failure) {
        OreDensityCellCache.Sampler cached = null;
        try {
            cached = OreDensityCellCache.create(function);
            long index = (long) startIndex + from;
            int x = (int) (index % sizeX), y = (int) (index / sizeX % sizeY), z = (int) (index / ((long) sizeX * sizeY));
            var context = new Point();
            for (int i = from; i < to; i++) {
                if ((i & 63) == 0 && failure.get() != null) return;
                context.x = baseX + x;
                context.y = baseY + y;
                context.z = baseZ + z;
                output[i] = cached == null ? function.compute(context) : cached.sample(context.x, context.y, context.z);
                if (++x == sizeX) {
                    x = 0;
                    if (++y == sizeY) {
                        y = 0;
                        z++;
                    }
                }
            }
        } catch (Throwable problem) {
            failure.compareAndSet(null, problem);
        } finally {
            if (cached != null) cached.recordStatistics();
        }
    }

    private static ThreadPoolExecutor executor(int workers) {
        synchronized (POOL_LOCK) {
            if (pool == null || pool.isShutdown()) {
                pool = new ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(workers * 2), task -> {
                            Thread thread = new Thread(task, "gtl-ore-density-" + THREAD_IDS.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        }, new ThreadPoolExecutor.AbortPolicy());
                pool.allowCoreThreadTimeOut(true);
            }
            return pool;
        }
    }

    /** Accepted tasks drain normally: shutdownNow would strand callers waiting for queued slices. */
    public static void shutdown() {
        synchronized (POOL_LOCK) {
            if (pool != null) pool.shutdown();
            pool = null;
        }
        synchronized (VALIDATED) {
            VALIDATED.clear();
        }
    }

    @SubscribeEvent
    public static void serverStopped(ServerStoppedEvent event) {
        shutdown();
    }

    /** Attempted tiles/points; a caller must still observe successful completion before using output. */
    public static Statistics statistics() {
        return new Statistics(SAMPLE_CALLS.get(), PARALLEL_TILES.get(), SAMPLED_POINTS.get(),
                REJECTED_GRAPHS.get(), ACTIVE_WORKERS.get(), PEAK_WORKERS.get());
    }

    public record Statistics(long sampleCalls, long parallelTiles, long sampledPoints, long rejectedGraphs,
                             int activeWorkers, int peakWorkers) {}

    /** Calls/worker slices are attempted work; points and batches count only successful returns. */
    public static MaskedStatistics maskedStatistics() {
        return new MaskedStatistics(MASKED_CALLS.get(), MASKED_POINTS.get(),
                MASKED_PARALLEL_BATCHES.get(), MASKED_WORKER_SLICES.get());
    }

    public record MaskedStatistics(long maskedCalls, long maskedPoints, long parallelBatches, long workerSlices) {}

    private static final class Point implements DensityFunction.FunctionContext {

        int x;
        int y;
        int z;

        @Override
        public int blockX() {
            return x;
        }

        @Override
        public int blockY() {
            return y;
        }

        @Override
        public int blockZ() {
            return z;
        }

        @Override
        public Blender getBlender() {
            return Blender.empty();
        }
    }

    private record Descriptor(boolean allowed, Field[] children) {}

    private static final class IdentityKey extends WeakReference<DensityFunction> {

        private final int hash;

        IdentityKey(DensityFunction function, boolean queued) {
            super(function, queued ? COLLECTED : null);
            hash = System.identityHashCode(function);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof IdentityKey key && get() != null && get() == key.get();
        }
    }

    private static final class RejectedGraph extends RuntimeException {

        static final RejectedGraph INSTANCE = new RejectedGraph();

        private RejectedGraph() {
            super(null, null, false, false);
        }
    }

    private ParallelOreDensity() {}
}
