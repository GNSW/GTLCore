package org.gtlcore.gtlcore.mixin.gtm.api.gui.widget;

import com.gregtechceu.gtceu.api.gui.widget.NumberInputWidget;

import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NumberInputWidget.class)
public abstract class NumberInputWidgetMixin {

    @Shadow(remap = false)
    private TextFieldWidget textField;

    @Shadow(remap = false)
    private Number min;

    @Shadow(remap = false)
    private Number max;

    @Inject(method = "updateTextFieldRange", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$skipUninitializedRange(CallbackInfo ci) {
        if (this.textField == null || this.min == null || this.max == null) {
            ci.cancel();
        }
    }
}
