package org.gtlcore.gtlcore.mixin.mc.worldgen;

import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PerlinNoise.class)
public interface PerlinNoiseAccessor {

    @Accessor("noiseLevels")
    ImprovedNoise[] gtlcore$noiseLevels();

    @Accessor("amplitudes")
    DoubleList gtlcore$amplitudes();

    @Accessor("lowestFreqInputFactor")
    double gtlcore$inputFactor();

    @Accessor("lowestFreqValueFactor")
    double gtlcore$valueFactor();
}
