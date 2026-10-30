package org.gtlcore.gtlcore.common.worldgen;

import org.gtlcore.gtlcore.mixin.mc.worldgen.DensityMarkerAccessor;
import org.gtlcore.gtlcore.mixin.mc.worldgen.DensityNoiseAccessor;
import org.gtlcore.gtlcore.mixin.mc.worldgen.ImprovedNoiseAccessor;
import org.gtlcore.gtlcore.mixin.mc.worldgen.NormalNoiseAccessor;
import org.gtlcore.gtlcore.mixin.mc.worldgen.PerlinNoiseAccessor;

import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Slice-local gradient lookup reuse for the native Marker(Noise) graph used by GT ore toggles.
 * This never hooks normal world noise calls: the original function remains the scalar oracle.
 */
public final class OreDensityCellCache {

    private static final String MARKER = "net.minecraft.world.level.levelgen.DensityFunctions$Marker";
    private static final String NOISE = "net.minecraft.world.level.levelgen.DensityFunctions$Noise";
    private static final AtomicLong SLICES = new AtomicLong();
    private static final AtomicLong POINTS = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();

    /** Called only after ParallelOreDensity approves the original immutable graph. */
    static Sampler create(DensityFunction function) {
        if (Boolean.getBoolean("gtlcore.disableOreDensityCellCache") ||
                !function.getClass().getName().equals(MARKER) || !(function instanceof DensityMarkerAccessor marker))
            return null;
        DensityFunction wrapped = unwrapDirect(marker.gtlcore$wrapped());
        if (wrapped == null || !wrapped.getClass().getName().equals(NOISE) || !(wrapped instanceof DensityNoiseAccessor node)) return null;
        DensityFunction.NoiseHolder holder = node.gtlcore$noise();
        NormalNoise normal = holder == null ? null : holder.noise();
        if (normal == null || normal.getClass() != NormalNoise.class || !(normal instanceof NormalNoiseAccessor access)) return null;
        double xzScale = node.gtlcore$xzScale(), yScale = node.gtlcore$yScale(), valueFactor = access.gtlcore$valueFactor();
        if (!Double.isFinite(xzScale) || !Double.isFinite(yScale) || !Double.isFinite(valueFactor)) return null;
        SingleOctave first = SingleOctave.capture(access.gtlcore$first());
        SingleOctave second = SingleOctave.capture(access.gtlcore$second());
        return first == null || second == null ? null : new Sampler(normal, xzScale, yScale, valueFactor, first, second);
    }

    private static DensityFunction unwrapDirect(DensityFunction function) {
        // JSON's HOLDER_HELPER_CODEC wraps even inline arguments. GT's mapAll preserves that
        // wrapper with a Direct holder; both simply delegate compute without numeric changes.
        for (int depth = 0; depth < 16 && function instanceof DensityFunctions.HolderHolder wrapper; depth++) {
            if (function.getClass() != DensityFunctions.HolderHolder.class ||
                    !(wrapper.function() instanceof Holder.Direct<?> direct) || !(direct.value() instanceof DensityFunction child))
                return null;
            function = child;
        }
        return function;
    }

    public static Statistics statistics() {
        return new Statistics(SLICES.get(), POINTS.get(), HITS.get(), MISSES.get());
    }

    public record Statistics(long slices, long sampledPoints, long cellHits, long cellMisses) {}

    static final class Sampler implements NumericOreTileCache.Computer {

        private final double xzScale;
        private final double yScale;
        private final double valueFactor;
        private final SingleOctave first;
        private final SingleOctave second;
        private final NumericOreTileCache.Cursor tileCache;
        private long points;

        Sampler(NormalNoise noise, double xzScale, double yScale, double valueFactor, SingleOctave first, SingleOctave second) {
            this.xzScale = xzScale;
            this.yScale = yScale;
            this.valueFactor = valueFactor;
            this.first = first;
            this.second = second;
            this.tileCache = NumericOreTileCache.create(noise, xzScale, yScale);
        }

        double sample(int blockX, int blockY, int blockZ) {
            points++;
            return tileCache == null ? compute(blockX, blockY, blockZ) : tileCache.sample(blockX, blockY, blockZ, this);
        }

        @Override
        public double compute(int blockX, int blockY, int blockZ) {
            // Preserve DensityFunctions.Noise then NormalNoise's separate multiplications.
            double x = (double) blockX * xzScale;
            double y = (double) blockY * yScale;
            double z = (double) blockZ * xzScale;
            double secondX = x * 1.0181268882175227D;
            double secondY = y * 1.0181268882175227D;
            double secondZ = z * 1.0181268882175227D;
            return (first.sample(x, y, z) + second.sample(secondX, secondY, secondZ)) * valueFactor;
        }

        void recordStatistics() {
            // One publication per completed/failed slice; the point loop has no atomic counters.
            SLICES.incrementAndGet();
            POINTS.addAndGet(points);
            HITS.addAndGet(first.hits + second.hits);
            MISSES.addAndGet(first.misses + second.misses);
            if (tileCache != null) tileCache.recordStatistics();
        }
    }

    private static final class SingleOctave {

        private final double amplitude;
        private final double inputFactor;
        private final double valueFactor;
        private final double xo;
        private final double yo;
        private final double zo;
        private final byte[] permutation;
        private final int[] gradients = new int[8];
        private int cellX, cellY, cellZ;
        private boolean valid;
        private long hits, misses;
        private long lastYBits, lastZBits;
        private boolean yValid, zValid;
        private int axisY, axisZ;
        private double dy, dz, sampleY, fadeY, fadeZ;

        private SingleOctave(ImprovedNoise noise, byte[] permutation, double amplitude, double inputFactor, double valueFactor) {
            this.xo = noise.xo;
            this.yo = noise.yo;
            this.zo = noise.zo;
            this.permutation = permutation;
            this.amplitude = amplitude;
            this.inputFactor = inputFactor;
            this.valueFactor = valueFactor;
        }

        static SingleOctave capture(PerlinNoise noise) {
            if (noise == null || noise.getClass() != PerlinNoise.class || !(noise instanceof PerlinNoiseAccessor access)) return null;
            ImprovedNoise[] levels = access.gtlcore$noiseLevels();
            if (levels == null || levels.length != 1 || levels[0] == null || levels[0].getClass() != ImprovedNoise.class || access.gtlcore$amplitudes().size() != 1 ||
                    !((Object) levels[0] instanceof ImprovedNoiseAccessor improved))
                return null;
            byte[] permutation = improved.gtlcore$permutation();
            double amplitude = access.gtlcore$amplitudes().getDouble(0);
            double inputFactor = access.gtlcore$inputFactor(), valueFactor = access.gtlcore$valueFactor();
            if (permutation == null || permutation.length != 256 || !Double.isFinite(amplitude) ||
                    !Double.isFinite(inputFactor) || !Double.isFinite(valueFactor))
                return null;
            return new SingleOctave(levels[0], permutation, amplitude, inputFactor, valueFactor);
        }

        double sample(double x, double y, double z) {
            // Do not fuse the scale, wrap or offset operations, nor remove the initial +0 sum.
            double shiftedX = PerlinNoise.wrap(x * inputFactor) + xo;
            int ix = Mth.floor(shiftedX);
            double dx = shiftedX - (double) ix;
            // X-fast enumeration repeats Y across a row and Z across a plane. Reuse only
            // identical input bits; never advance floating-point coordinates incrementally.
            long yBits = Double.doubleToRawLongBits(y), zBits = Double.doubleToRawLongBits(z);
            boolean updateY = !yValid || yBits != lastYBits;
            boolean updateZ = !zValid || zBits != lastZBits;
            if (updateY) {
                yValid = false;
                double shiftedY = PerlinNoise.wrap(y * inputFactor) + yo;
                axisY = Mth.floor(shiftedY);
                dy = shiftedY - (double) axisY;
                sampleY = dy - 0.0D;
                lastYBits = yBits;
            }
            if (updateZ) {
                zValid = false;
                double shiftedZ = PerlinNoise.wrap(z * inputFactor) + zo;
                axisZ = Mth.floor(shiftedZ);
                dz = shiftedZ - (double) axisZ;
                lastZBits = zBits;
            }
            int iy = axisY, iz = axisZ;
            if (!valid || ix != cellX || iy != cellY || iz != cellZ) {
                loadCell(ix, iy, iz);
                misses++;
            } else hits++;
            double d0 = ImprovedNoiseAccessor.gtlcore$gradDot(gradients[0], dx, sampleY, dz);
            double d1 = ImprovedNoiseAccessor.gtlcore$gradDot(gradients[1], dx - 1.0D, sampleY, dz);
            double d2 = ImprovedNoiseAccessor.gtlcore$gradDot(gradients[2], dx, sampleY - 1.0D, dz);
            double d3 = ImprovedNoiseAccessor.gtlcore$gradDot(gradients[3], dx - 1.0D, sampleY - 1.0D, dz);
            double d4 = ImprovedNoiseAccessor.gtlcore$gradDot(gradients[4], dx, sampleY, dz - 1.0D);
            double d5 = ImprovedNoiseAccessor.gtlcore$gradDot(gradients[5], dx - 1.0D, sampleY, dz - 1.0D);
            double d6 = ImprovedNoiseAccessor.gtlcore$gradDot(gradients[6], dx, sampleY - 1.0D, dz - 1.0D);
            double d7 = ImprovedNoiseAccessor.gtlcore$gradDot(gradients[7], dx - 1.0D, sampleY - 1.0D, dz - 1.0D);
            double fadeX = Mth.smoothstep(dx);
            if (updateY) {
                fadeY = Mth.smoothstep(dy);
                yValid = Double.isFinite(y) && Double.isFinite(dy);
            }
            if (updateZ) {
                fadeZ = Mth.smoothstep(dz);
                zValid = Double.isFinite(z) && Double.isFinite(dz);
            }
            double value = Mth.lerp3(fadeX, fadeY, fadeZ, d0, d1, d2, d3, d4, d5, d6, d7);
            double sum = 0.0D;
            sum += amplitude * value * valueFactor;
            return sum;
        }

        private void loadCell(int x, int y, int z) {
            int px0 = permute(x), px1 = permute(x + 1);
            int pxy00 = permute(px0 + y), pxy01 = permute(px0 + y + 1);
            int pxy10 = permute(px1 + y), pxy11 = permute(px1 + y + 1);
            gradients[0] = permute(pxy00 + z);
            gradients[1] = permute(pxy10 + z);
            gradients[2] = permute(pxy01 + z);
            gradients[3] = permute(pxy11 + z);
            gradients[4] = permute(pxy00 + z + 1);
            gradients[5] = permute(pxy10 + z + 1);
            gradients[6] = permute(pxy01 + z + 1);
            gradients[7] = permute(pxy11 + z + 1);
            cellX = x;
            cellY = y;
            cellZ = z;
            valid = true;
        }

        private int permute(int value) {
            return permutation[value & 255] & 255;
        }
    }

    private OreDensityCellCache() {}
}
