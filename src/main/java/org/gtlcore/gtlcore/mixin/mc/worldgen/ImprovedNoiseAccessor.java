package org.gtlcore.gtlcore.mixin.mc.worldgen;

import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ImprovedNoise.class)
public interface ImprovedNoiseAccessor {

    @Accessor("p")
    byte[] gtlcore$permutation();

    @Invoker("gradDot")
    static double gtlcore$gradDot(int gradient, double x, double y, double z) {
        throw new AssertionError("Mixin invoker was not applied");
    }
}
