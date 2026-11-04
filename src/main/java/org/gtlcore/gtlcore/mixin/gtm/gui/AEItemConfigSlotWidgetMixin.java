package org.gtlcore.gtlcore.mixin.gtm.gui;

import com.gregtechceu.gtceu.integration.ae2.gui.widget.slot.AEItemConfigSlotWidget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(value = AEItemConfigSlotWidget.class, remap = false)
public abstract class AEItemConfigSlotWidgetMixin {

    @ModifyConstant(method = "mouseWheelMove",
                    constant = @Constant(longValue = 2L),
                    remap = false)
    private long ctrlScrollMultiplier(long original) {
        return 1000L;
    }
}
