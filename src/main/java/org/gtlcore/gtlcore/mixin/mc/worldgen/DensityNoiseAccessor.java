package org.gtlcore.gtlcore.mixin.mc.worldgen;

import net.minecraft.world.level.levelgen.DensityFunction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$Noise")
public interface DensityNoiseAccessor {

    @Accessor("noise")
    DensityFunction.NoiseHolder gtlcore$noise();

    @Accessor("xzScale")
    double gtlcore$xzScale();

    @Accessor("yScale")
    double gtlcore$yScale();
}
