package org.gtlcore.gtlcore.common.worldgen;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Invocation-local noise leaf caches; native density nodes retain their arithmetic and short circuits. */
public final class VeinedRidgeSampler implements AutoCloseable {

    public static final String DISABLE_PROPERTY = "gtlcore.disableVeinedRidgeCache";
    private static final AtomicLong SCOPES = new AtomicLong();
    private static final AtomicLong POINTS = new AtomicLong();
    private static final AtomicLong FALLBACK_POINTS = new AtomicLong();

    private final DensityFunction original;
    private final DensityFunction cached;
    private final List<OreDensityCellCache.Sampler> leaves;
    private long sampledPoints;
    private boolean closed;

    private VeinedRidgeSampler(DensityFunction original, DensityFunction cached,
                               List<OreDensityCellCache.Sampler> leaves) {
        this.original = original;
        this.cached = cached;
        this.leaves = leaves;
    }

    /**
     * Compiles the immutable graph already mapped by GT's native mapToNoise visitor.
     * Returns null when the graph or noise leaves do not support this optimization.
     */
    public static VeinedRidgeSampler create(DensityFunction original) {
        if (Boolean.getBoolean(DISABLE_PROPERTY) || !ParallelOreDensity.pureKnown(original)) return null;
        List<OreDensityCellCache.Sampler> leaves = new ArrayList<>();
        DensityFunction cached = original.mapAll(function -> {
            OreDensityCellCache.Sampler sampler = OreDensityCellCache.create(function);
            if (sampler == null) return function;
            leaves.add(sampler);
            return new CachedNoise(function, sampler);
        });
        // Native mapAll rebuilds the native operators with unchanged leaf bounds. In particular,
        // Ap2.MAX still evaluates argument 1 first and skips argument 2 only above its maxValue.
        // Do not replace the ridge with an algebraically simplified expression or a full tile:
        // GT reaches it only after the toggle threshold and the original density random draw.
        return leaves.isEmpty() ? null : new VeinedRidgeSampler(original, cached, List.copyOf(leaves));
    }

    /** The untouched native function, also used by the independent numeric verification probe. */
    public DensityFunction originalFunction() {
        return original;
    }

    /** Samples only contexts approved by the calling scope; the cache is never shared between threads. */
    public double sample(DensityFunction.FunctionContext context) {
        if (closed) throw new IllegalStateException("Ridge sample scope is closed");
        if (context.getBlender() != Blender.empty())
            throw new IllegalArgumentException("Ridge samples require an empty blender");
        sampledPoints++;
        return cached.compute(context);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        SCOPES.incrementAndGet();
        POINTS.addAndGet(sampledPoints);
        for (OreDensityCellCache.Sampler sampler : leaves) sampler.recordStatistics();
    }

    static void recordFallbackPoints(long points) {
        if (points != 0) FALLBACK_POINTS.addAndGet(points);
    }

    public static Statistics statistics() {
        return new Statistics(SCOPES.get(), POINTS.get(), FALLBACK_POINTS.get());
    }

    public record Statistics(long scopes, long sampledPoints, long fallbackPoints) {}

    private record CachedNoise(DensityFunction original, OreDensityCellCache.Sampler sampler)
            implements DensityFunction.SimpleFunction {

        @Override
        public double compute(DensityFunction.FunctionContext context) {
            return sampler.sample(context.blockX(), context.blockY(), context.blockZ());
        }

        @Override
        public double minValue() {
            return original.minValue();
        }

        @Override
        public double maxValue() {
            return original.maxValue();
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("Invocation-local noise caches are not serializable");
        }
    }
}
