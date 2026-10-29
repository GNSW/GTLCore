package org.gtlcore.gtlcore.mixin.gtm.worldgen;

import com.gregtechceu.gtceu.api.data.worldgen.GTLayerPattern;
import com.gregtechceu.gtceu.api.data.worldgen.generator.veins.LayeredVeinGenerator;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin(value = LayeredVeinGenerator.class, remap = false)
public abstract class LayeredVeinGeneratorMixin {

    @WrapMethod(method = "getLayerPatterns")
    private List<GTLayerPattern> gtlcore$publishBakedPatterns(Operation<List<GTLayerPattern>> original) {
        synchronized (this) {
            return original.call();
        }
    }
}
