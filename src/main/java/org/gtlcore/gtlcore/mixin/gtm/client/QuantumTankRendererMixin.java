package org.gtlcore.gtlcore.mixin.gtm.client;

import org.gtlcore.gtlcore.integration.ae2.MEFluidUnits;

import com.gregtechceu.gtceu.client.renderer.machine.QuantumTankRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = QuantumTankRenderer.class, remap = false)
public abstract class QuantumTankRendererMixin {

    @Redirect(method = "renderTank",
              at = @At(value = "INVOKE",
                       target = "Lcom/lowdragmc/lowdraglib/gui/util/TextFormattingUtil;formatLongToCompactString(JI)Ljava/lang/String;"))
    private String gtlcore$formatFluidAmount(long value, int precision) {
        return MEFluidUnits.formatDisplayAmount(value);
    }
}
