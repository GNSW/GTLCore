package org.gtlcore.gtlcore.mixin.gtm.worldgen;

import org.gtlcore.gtlcore.common.worldgen.VeinedDensityBatch;
import org.gtlcore.gtlcore.common.worldgen.VeinedRidgeSampler;

import com.gregtechceu.gtceu.api.data.worldgen.GTOreDefinition;
import com.gregtechceu.gtceu.api.data.worldgen.generator.veins.VeinedVeinGenerator;
import com.gregtechceu.gtceu.api.data.worldgen.ores.OreBlockPlacer;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.blending.Blender;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.List;
import java.util.Map;

/** Accelerates only pure numeric samples; the original GT loop retains random and placement order. */
@Mixin(value = VeinedVeinGenerator.class, remap = false)
public abstract class VeinedVeinGeneratorMixin {

    @Shadow
    public int minYLevel;
    @Shadow
    public int maxYLevel;
    @Shadow
    public float veininessThreshold;

    @WrapMethod(method = "generate")
    private Map<BlockPos, OreBlockPlacer> gtlcore$sampleScope(WorldGenLevel level, RandomSource random,
                                                              GTOreDefinition definition, BlockPos origin,
                                                              Operation<Map<BlockPos, OreBlockPlacer>> original,
                                                              @Share("densityBatch") LocalRef<VeinedDensityBatch> shared) {
        // Diagnostic baseline switch, evaluated once per call. No random state is transferred to workers.
        VeinedDensityBatch current = Boolean.getBoolean("gtlcore.disableParallelOreDensity") ? null :
                new VeinedDensityBatch(origin.getY(), minYLevel, maxYLevel);
        // MixinExtras creates this reference once per generate invocation, including recursion.
        // The native loop receives the same local reference without per-point ThreadLocal lookups.
        shared.set(current);
        try {
            return original.call(level, random, definition, origin);
        } finally {
            if (current != null) current.close();
        }
    }

    @WrapOperation(method = "generate",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/core/BlockPos;betweenClosed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Ljava/lang/Iterable;",
                            remap = true),
                   require = 1)
    private Iterable<BlockPos> gtlcore$captureBounds(BlockPos minimum, BlockPos maximum, Operation<Iterable<BlockPos>> original,
                                                     @Local(ordinal = 0) DensityFunction toggle,
                                                     @Local(ordinal = 1) DensityFunction ridge,
                                                     @Share("densityBatch") LocalRef<VeinedDensityBatch> shared) {
        VeinedDensityBatch batch = shared.get();
        if (batch != null) {
            if (!batch.bounds(minimum, maximum, toggle)) return List.of();
            batch.allowContextReuse(toggle, ridge);
        }
        return original.call(minimum, maximum);
    }

    @WrapOperation(method = "generate",
                   at = @At(value = "NEW",
                            target = "(Lcom/gregtechceu/gtceu/api/data/worldgen/generator/veins/VeinedVeinGenerator;IIIIIILnet/minecraft/world/level/levelgen/blending/Blender;)Lcom/gregtechceu/gtceu/api/data/worldgen/generator/veins/VeinedVeinGenerator$1;"),
                   require = 1)
    @Coerce
    private Object gtlcore$reuseNativeContext(VeinedVeinGenerator generator,
                                              int x, int offsetX, int y, int offsetY, int z, int offsetZ,
                                              Blender blender, Operation<Object> original,
                                              @Share("densityBatch") LocalRef<VeinedDensityBatch> shared) {
        VeinedDensityBatch batch = shared.get();
        if (batch != null) {
            // Match the anonymous class's original int additions, including overflow behavior.
            Object reused = batch.reuseContext(generator, blender, x + offsetX, y + offsetY, z + offsetZ);
            if (reused != null) return reused;
        }
        Object context = original.call(generator, x, offsetX, y, offsetY, z, offsetZ, blender);
        if (batch != null) batch.constructedContext(generator, blender, context);
        return context;
    }

    @WrapOperation(method = "generate",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D",
                            ordinal = 0,
                            remap = true),
                   require = 1)
    private double gtlcore$sampleToggle(DensityFunction function, DensityFunction.FunctionContext context,
                                        Operation<Double> original,
                                        @Share("densityBatch") LocalRef<VeinedDensityBatch> shared) {
        VeinedDensityBatch batch = shared.get();
        return batch != null && batch.prepare(function, context) ? batch.next() : original.call(function, context);
    }

    @ModifyExpressionValue(method = "generate",
                           at = @At(value = "INVOKE",
                                    target = "Lnet/minecraft/util/Mth;clampedMap(DDDDD)D",
                                    ordinal = 0,
                                    remap = true),
                           require = 1)
    private double gtlcore$captureEdgeRoundoff(double value,
                                               @Share("densityBatch") LocalRef<VeinedDensityBatch> shared) {
        VeinedDensityBatch batch = shared.get();
        if (batch != null) batch.captureEdgeRoundoff(value);
        return value;
    }

    @ModifyExpressionValue(method = "generate",
                           at = @At(value = "INVOKE",
                                    target = "Lcom/gregtechceu/gtceu/api/data/worldgen/GTOreDefinition;density()F"),
                           require = 1)
    private float gtlcore$captureDensity(float value,
                                         @Share("densityBatch") LocalRef<VeinedDensityBatch> shared) {
        VeinedDensityBatch batch = shared.get();
        if (batch != null) batch.captureDensity(value);
        return value;
    }

    @WrapOperation(method = "generate",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D",
                            ordinal = 1,
                            remap = true),
                   require = 1)
    private double gtlcore$sampleRidge(DensityFunction function, DensityFunction.FunctionContext context,
                                       Operation<Double> original,
                                       @Share("densityBatch") LocalRef<VeinedDensityBatch> shared) {
        VeinedDensityBatch batch = shared.get();
        // Capture the native primitive expressions at their original call sites. Capturing
        // mutable method locals here makes MixinExtras allocate reference boxes per voxel.
        if (batch != null && batch.prepareParallelRidge(function, context, veininessThreshold))
            return batch.sampleRidge(function, context);
        VeinedRidgeSampler sampler = batch == null ? null : batch.prepareRidge(function, context);
        return sampler == null ? original.call(function, context) : sampler.sample(context);
    }
}
