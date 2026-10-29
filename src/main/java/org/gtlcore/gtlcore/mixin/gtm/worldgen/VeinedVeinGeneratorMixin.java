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

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

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
    @Unique
    private static final ThreadLocal<VeinedDensityBatch> gtlcore$densityBatch = new ThreadLocal<>();

    @WrapMethod(method = "generate")
    private Map<BlockPos, OreBlockPlacer> gtlcore$sampleScope(WorldGenLevel level, RandomSource random,
                                                              GTOreDefinition definition, BlockPos origin,
                                                              Operation<Map<BlockPos, OreBlockPlacer>> original) {
        // Diagnostic baseline switch, evaluated once per call. No random state is transferred to workers.
        VeinedDensityBatch previous = gtlcore$densityBatch.get();
        VeinedDensityBatch current = Boolean.getBoolean("gtlcore.disableParallelOreDensity") ? null :
                new VeinedDensityBatch(origin.getY(), minYLevel, maxYLevel);
        if (current == null) gtlcore$densityBatch.remove();
        else gtlcore$densityBatch.set(current);
        try {
            return original.call(level, random, definition, origin);
        } finally {
            if (previous == null) gtlcore$densityBatch.remove();
            else gtlcore$densityBatch.set(previous);
            if (current != null) current.close();
        }
    }

    @WrapOperation(method = "generate",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/core/BlockPos;betweenClosed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Ljava/lang/Iterable;",
                            remap = true),
                   require = 1)
    private Iterable<BlockPos> gtlcore$captureBounds(BlockPos minimum, BlockPos maximum, Operation<Iterable<BlockPos>> original,
                                                     @Local(ordinal = 0) DensityFunction toggle) {
        VeinedDensityBatch batch = gtlcore$densityBatch.get();
        if (batch != null && !batch.bounds(minimum, maximum, toggle)) return List.of();
        return original.call(minimum, maximum);
    }

    @WrapOperation(method = "generate",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D",
                            ordinal = 0,
                            remap = true),
                   require = 1)
    private double gtlcore$sampleToggle(DensityFunction function, DensityFunction.FunctionContext context,
                                        Operation<Double> original) {
        VeinedDensityBatch batch = gtlcore$densityBatch.get();
        return batch != null && batch.prepare(function, context) ? batch.next() : original.call(function, context);
    }

    @WrapOperation(method = "generate",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D",
                            ordinal = 1,
                            remap = true),
                   require = 1)
    private double gtlcore$sampleRidge(DensityFunction function, DensityFunction.FunctionContext context,
                                       Operation<Double> original,
                                       @Local(argsOnly = true) GTOreDefinition entry,
                                       @Local(ordinal = 2) double edgeRoundoff) {
        VeinedDensityBatch batch = gtlcore$densityBatch.get();
        // GT 1.4.4's live double locals here are toggleNoise, absToggleNoise, edgeRoundoff.
        // Capture the already computed edge value rather than repeating its native arithmetic.
        if (batch != null && batch.prepareParallelRidge(function, context, entry.density(), edgeRoundoff, veininessThreshold))
            return batch.sampleRidge(function, context);
        VeinedRidgeSampler sampler = batch == null ? null : batch.prepareRidge(function, context);
        return sampler == null ? original.call(function, context) : sampler.sample(context);
    }
}
