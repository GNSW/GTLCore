package org.gtlcore.gtlcore.mixin.gtm.worldgen;

import com.gregtechceu.gtceu.api.data.worldgen.generator.veins.StandardVeinGenerator;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.apache.commons.lang3.mutable.MutableInt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = StandardVeinGenerator.class, remap = false)
public abstract class StandardVeinGeneratorMixin {

    @WrapOperation(method = { "lambda$placeBlock$15", "lambda$placeBlock$16" },
                   at = @At(value = "INVOKE", target = "Lorg/apache/commons/lang3/mutable/MutableInt;increment()V"),
                   require = 2,
                   remap = false)
    private static void gtlcore$omitUnusedSharedCounter(MutableInt counter, Operation<Void> original) {
        // GT 1.4.4 captures this counter in cached placers shared by neighboring chunks. It is
        // incremented here but never read, including after generate(), so removing these two
        // writes changes neither placement, return values nor the random-number sequence.
    }
}
