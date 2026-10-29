package org.gtlcore.gtlcore.mixin.gtm.worldgen;

import com.gregtechceu.gtceu.api.data.worldgen.ores.OreVeinUtil;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = OreVeinUtil.class, remap = false)
public interface OreVeinUtilAccessor {

    @Invoker("getMaxVeinSearchDistance")
    static int gtlcore$maximumSearchDistance() {
        throw new AssertionError();
    }
}
