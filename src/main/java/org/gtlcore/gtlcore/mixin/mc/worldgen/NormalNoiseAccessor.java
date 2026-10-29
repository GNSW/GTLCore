package org.gtlcore.gtlcore.mixin.mc.worldgen;

import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NormalNoise.class)
public interface NormalNoiseAccessor {

    @Accessor("first")
    PerlinNoise gtlcore$first();

    @Accessor("second")
    PerlinNoise gtlcore$second();

    @Accessor("valueFactor")
    double gtlcore$valueFactor();
}
