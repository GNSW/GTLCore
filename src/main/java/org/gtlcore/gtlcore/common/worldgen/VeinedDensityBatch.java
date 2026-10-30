package org.gtlcore.gtlcore.common.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.concurrent.atomic.AtomicLong;

/** Per-invocation numeric lookahead. GT still owns every random draw and every placement. */
public final class VeinedDensityBatch {

    private static final int TILE_SIZE = 16384;
    private static final AtomicLong CONTEXT_SCOPES = new AtomicLong();
    private static final AtomicLong CONTEXT_ELIGIBLE_SCOPES = new AtomicLong();
    private static final AtomicLong CONTEXT_REJECTED_SCOPES = new AtomicLong();
    private static final AtomicLong CONTEXT_CONSTRUCTIONS = new AtomicLong();
    private static final AtomicLong CONTEXT_REUSED_POINTS = new AtomicLong();
    private final boolean outsideHeight;
    private final boolean sparseRidgeDisabled = Boolean.getBoolean("gtlcore.disableSparseRidgeBatch");
    private final boolean contextReuseDisabled = Boolean.getBoolean("gtlcore.disableVeinedContextReuse");
    private int sizeX, sizeY, sizeZ, total, cursor;
    private int offsetX, offsetY, offsetZ;
    private int baseX, baseY, baseZ, tileStart = -1, tileCount;
    private DensityFunction sampledFunction;
    private double[] samples;
    private boolean disabled;
    private DensityFunction ridgeFunction;
    private VeinedRidgeSampler ridgeSampler;
    private boolean ridgeChecked;
    private long ridgeFallbackPoints;
    private DensityFunction parallelRidgeFunction;
    private double[] parallelRidgeSamples;
    private boolean[] parallelRidgeSelected;
    private int parallelRidgeTileStart = -1;
    private long parallelRidgeEdgeBits, parallelRidgeThresholdBits;
    private boolean parallelRidgeReady;
    private double nativeEdgeRoundoff;
    private float nativeDensity;
    private boolean edgeRoundoffAvailable, densityAvailable;
    private boolean contextReuseApproved, contextEligible;
    private Object reusableContext, contextGenerator;
    private Blender contextBlender;
    private long contextConstructions, contextReusedPoints;

    public VeinedDensityBatch(int originY, int minimumY, int maximumY) {
        outsideHeight = originY < minimumY || originY > maximumY;
    }

    public boolean bounds(BlockPos minimum, BlockPos maximum, DensityFunction toggle) {
        // GT 1.4.4 tests the origin's Y, not each sampled point's Y. It previously evaluated
        // the complete noise cube before rejecting this invariant condition at every point.
        // This hook runs after cluster-size sampling and all three random coordinate offsets.
        if (outsideHeight && ParallelOreDensity.pureKnown(toggle)) return false;
        long x = (long) maximum.getX() - minimum.getX() + 1;
        long y = (long) maximum.getY() - minimum.getY() + 1;
        long z = (long) maximum.getZ() - minimum.getZ() + 1;
        if (x < 1 || y < 1 || z < 1 || x > 4096 || y > 4096 || z > 4096 || x * y * z > Integer.MAX_VALUE) {
            disabled = true;
            return true;
        }
        sizeX = (int) x;
        sizeY = (int) y;
        sizeZ = (int) z;
        total = (int) (x * y * z);
        return true;
    }

    /** Called once before GT enters the point loop, with both actual native density graphs. */
    public void allowContextReuse(DensityFunction toggle, DensityFunction ridge) {
        contextReuseApproved = !contextReuseDisabled &&
                ParallelOreDensity.pureKnown(toggle) && ParallelOreDensity.pureKnown(ridge);
    }

    /** The cached object is the actual private GT class; constructor wrappers can retain its cast. */
    public Object reuseContext(Object generator, Blender blender, int x, int y, int z) {
        if (!contextReuseApproved || blender != Blender.empty() || reusableContext == null ||
                contextGenerator != generator || contextBlender != blender)
            return null;
        ((ReusableVeinedContext) reusableContext).gtlcore$reuseCoordinates(x, y, z);
        contextReusedPoints++;
        return reusableContext;
    }

    /** Counts original construction locally; only the first approved native object can be retained. */
    public void constructedContext(Object generator, Blender blender, Object context) {
        contextConstructions++;
        if (contextReuseApproved && blender == Blender.empty() && reusableContext == null &&
                context instanceof ReusableVeinedContext) {
            reusableContext = context;
            contextGenerator = generator;
            contextBlender = blender;
            contextEligible = true;
        }
    }

    public static ContextStatistics contextStatistics() {
        return new ContextStatistics(CONTEXT_SCOPES.get(), CONTEXT_ELIGIBLE_SCOPES.get(), CONTEXT_REJECTED_SCOPES.get(),
                CONTEXT_CONSTRUCTIONS.get(), CONTEXT_REUSED_POINTS.get());
    }

    public record ContextStatistics(long scopes, long eligibleScopes, long rejectedScopes,
                                    long originalConstructions, long reusedPoints) {}

    public boolean prepare(DensityFunction function, DensityFunction.FunctionContext context) {
        // These values belong to the current native point. Missing expression hooks must
        // retain scalar sampling rather than borrow a previous point's captured inputs.
        edgeRoundoffAvailable = false;
        densityAvailable = false;
        if (disabled || total < TILE_SIZE || cursor >= total || context.getBlender() != Blender.empty()) return false;
        if (sampledFunction == null) {
            if (!ParallelOreDensity.pureKnown(function)) {
                disabled = true;
                return false;
            }
            sampledFunction = function;
            // The first point includes GT's three random offsets; later points use the same offsets.
            baseX = context.blockX();
            baseY = context.blockY();
            baseZ = context.blockZ();
            samples = new double[TILE_SIZE];
        }
        if (sampledFunction != function || context.blockX() != baseX + offsetX ||
                context.blockY() != baseY + offsetY || context.blockZ() != baseZ + offsetZ) {
            disabled = true;
            return false;
        }
        if (cursor < tileStart || cursor >= tileStart + tileCount) {
            tileStart = cursor;
            tileCount = Math.min(TILE_SIZE, total - cursor);
            ParallelOreDensity.sample(function, baseX, baseY, baseZ, sizeX, sizeY, cursor, tileCount, samples);
        }
        return true;
    }

    public double next() {
        double value = samples[cursor++ - tileStart];
        // Track the same X/Y/Z enumeration without dividing the point index three times per voxel.
        if (++offsetX == sizeX) {
            offsetX = 0;
            if (++offsetY == sizeY) {
                offsetY = 0;
                offsetZ++;
            }
        }
        return value;
    }

    public VeinedRidgeSampler prepareRidge(DensityFunction function, DensityFunction.FunctionContext context) {
        if (context.getBlender() != Blender.empty()) {
            ridgeFallbackPoints++;
            return null;
        }
        if (!ridgeChecked) {
            ridgeChecked = true;
            ridgeFunction = function;
            ridgeSampler = VeinedRidgeSampler.create(function);
        }
        if (function == ridgeFunction && ridgeSampler != null) return ridgeSampler;
        ridgeFallbackPoints++;
        return null;
    }

    /** Retains the native expression result without another computation or local-reference box. */
    public void captureEdgeRoundoff(double value) {
        nativeEdgeRoundoff = value;
        edgeRoundoffAvailable = true;
    }

    /** Captured after GT's original random draw, at the original density getter. */
    public void captureDensity(float value) {
        nativeDensity = value;
        densityAvailable = true;
    }

    public boolean prepareParallelRidge(DensityFunction function, DensityFunction.FunctionContext context,
                                        float threshold) {
        return edgeRoundoffAvailable && densityAvailable &&
                prepareParallelRidge(function, context, nativeDensity, nativeEdgeRoundoff, threshold);
    }

    public boolean prepareParallelRidge(DensityFunction function, DensityFunction.FunctionContext context,
                                        float density, double edgeRoundoff, float threshold) {
        // At density 1 every point passing the native toggle test also passes the density test.
        // Only detached noise is evaluated ahead: GT still performs every original random draw.
        if (sparseRidgeDisabled || density != 1.0F || disabled || samples == null ||
                context.getBlender() != Blender.empty() || !matchesCurrentPoint(context))
            return false;
        int current = cursor - 1 - tileStart;
        if (current < 0 || current >= tileCount) return false;
        long edgeBits = Double.doubleToRawLongBits(edgeRoundoff);
        long thresholdBits = Double.doubleToRawLongBits((double) threshold);
        if (parallelRidgeTileStart != tileStart || parallelRidgeFunction != function ||
                parallelRidgeEdgeBits != edgeBits || parallelRidgeThresholdBits != thresholdBits) {
            parallelRidgeReady = false;
            parallelRidgeTileStart = tileStart;
            parallelRidgeFunction = function;
            parallelRidgeEdgeBits = edgeBits;
            parallelRidgeThresholdBits = thresholdBits;
            if (parallelRidgeSamples == null) {
                parallelRidgeSamples = new double[TILE_SIZE];
                parallelRidgeSelected = new boolean[TILE_SIZE];
            }
            for (int i = 0; i < tileCount; i++) {
                // Preserve the original '< ... continue' predicate, including its NaN behavior.
                // The consumed prefix is excluded if the first eligible point is late in a tile.
                parallelRidgeSelected[i] = i >= current &&
                        !(Math.abs(samples[i]) + edgeRoundoff < (double) threshold);
            }
            // False attempts are remembered for this tile too; sparse tiles retain scalar sampling.
            parallelRidgeReady = ParallelOreDensity.sampleMasked(function, baseX, baseY, baseZ,
                    sizeX, sizeY, tileStart, tileCount, parallelRidgeSelected, parallelRidgeSamples);
        }
        return parallelRidgeReady && parallelRidgeSelected[current];
    }

    /** Actual cached value returned to GT, exposed separately for the independent numeric probe. */
    public double sampleRidge(DensityFunction original, DensityFunction.FunctionContext context) {
        int current = cursor - 1 - tileStart;
        if (!parallelRidgeReady || original != parallelRidgeFunction || parallelRidgeTileStart != tileStart ||
                current < 0 || current >= tileCount || !parallelRidgeSelected[current] || !matchesCurrentPoint(context))
            throw new IllegalStateException("Ridge tile was not prepared for this point");
        return parallelRidgeSamples[current];
    }

    private boolean matchesCurrentPoint(DensityFunction.FunctionContext context) {
        if (cursor <= 0 || cursor > total) return false;
        // next() has already advanced to cursor. Borrow one X/Y/Z position to validate cursor-1
        // without retaining GT's FunctionContext or performing three divisions per ridge sample.
        int x = offsetX - 1, y = offsetY, z = offsetZ;
        if (x < 0) {
            x = sizeX - 1;
            if (--y < 0) {
                y = sizeY - 1;
                z--;
            }
        }
        return context.blockX() == baseX + x && context.blockY() == baseY + y && context.blockZ() == baseZ + z;
    }

    /** Publish diagnostics once per generate invocation, including native exceptional exits. */
    public void close() {
        if (ridgeSampler != null) ridgeSampler.close();
        VeinedRidgeSampler.recordFallbackPoints(ridgeFallbackPoints);
        ridgeFallbackPoints = 0;
        CONTEXT_SCOPES.incrementAndGet();
        if (contextEligible) CONTEXT_ELIGIBLE_SCOPES.incrementAndGet();
        else if (contextConstructions != 0) CONTEXT_REJECTED_SCOPES.incrementAndGet();
        CONTEXT_CONSTRUCTIONS.addAndGet(contextConstructions);
        CONTEXT_REUSED_POINTS.addAndGet(contextReusedPoints);
        // Do not retain a native generator or its captured context beyond this invocation.
        reusableContext = null;
        contextGenerator = null;
        contextBlender = null;
    }
}
