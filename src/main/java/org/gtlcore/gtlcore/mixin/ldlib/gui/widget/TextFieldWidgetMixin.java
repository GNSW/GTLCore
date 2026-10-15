package org.gtlcore.gtlcore.mixin.ldlib.gui.widget;

import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.text.NumberFormat;

@Mixin(TextFieldWidget.class)
public abstract class TextFieldWidgetMixin {

    @Shadow(remap = false)
    protected NumberFormat numberInstance;

    @Shadow(remap = false)
    protected float wheelDur;

    @Shadow(remap = false)
    private boolean isDragging;

    @Shadow(remap = false)
    public abstract String getCurrentString();

    @Shadow(remap = false)
    protected abstract void onTextChanged(String newTextString);

    @Inject(method = "mouseWheelMove", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$preciseWheelMove(double mouseX, double mouseY, double wheelDelta,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (this.wheelDur <= 0f || this.numberInstance == null || !((Widget) (Object) this).isMouseOverElement(mouseX, mouseY) || !((Widget) (Object) this).isFocus()) {
            return;
        }
        try {
            this.numberInstance.setGroupingUsed(false);
            double next = Double.parseDouble(getCurrentString()) + (wheelDelta > 0 ? 1 : -1) * (double) this.wheelDur;
            onTextChanged(this.numberInstance.format(next));
        } catch (NumberFormatException ignored) {}
        ((Widget) (Object) this).setFocus(true);
        cir.setReturnValue(true);
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$preciseDrag(double mouseX, double mouseY, int button, double dragX, double dragY,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (!this.isDragging || this.numberInstance == null || !((Widget) (Object) this).isFocus()) {
            return;
        }
        try {
            this.numberInstance.setGroupingUsed(false);
            double next = Double.parseDouble(getCurrentString()) + dragX * (double) this.wheelDur;
            onTextChanged(this.numberInstance.format(next));
        } catch (NumberFormatException ignored) {}
        ((Widget) (Object) this).setFocus(true);
        cir.setReturnValue(true);
    }
}
