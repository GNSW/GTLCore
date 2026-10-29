package org.gtlcore.gtlcore.mixin.mc.worldgen;

import net.minecraft.world.level.levelgen.DensityFunction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$Marker")
public interface DensityMarkerAccessor {

    @Accessor("wrapped")
    DensityFunction gtlcore$wrapped();
}
