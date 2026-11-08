package org.gtlcore.gtlcore.mixin.ldlib.gui.widget;

import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;

import net.minecraft.client.gui.components.EditBox;

import appeng.client.gui.MathExpressionParser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Optional;
import java.util.function.Function;

@Mixin(TextFieldWidget.class)
public abstract class TextFieldWidgetMixin {

    @Shadow(remap = false)
    protected NumberFormat numberInstance;

    @Shadow(remap = false)
    protected float wheelDur;

    @Shadow(remap = false)
    private boolean isDragging;

    @Shadow(remap = false)
    protected Function<String, String> textValidator;

    @Shadow(remap = false)
    protected EditBox textField;

    @Shadow(remap = false)
    public abstract String getCurrentString();

    @Shadow(remap = false)
    protected abstract void onTextChanged(String newTextString);

    @Unique
    private boolean gtlcore$numericExpressionInput;

    @Unique
    private String gtlcore$rawExpression;

    @Unique
    private DecimalFormat gtlcore$expressionFormat;

    @Inject(method = "setNumbersOnly(JJ)Lcom/lowdragmc/lowdraglib/gui/widget/TextFieldWidget;", at = @At("TAIL"), remap = false)
    private void gtlcore$enableLongExpressionInput(long minValue, long maxValue, CallbackInfoReturnable<TextFieldWidget> cir) {
        this.gtlcore$numericExpressionInput = true;
    }

    @Inject(method = "setNumbersOnly(II)Lcom/lowdragmc/lowdraglib/gui/widget/TextFieldWidget;", at = @At("TAIL"), remap = false)
    private void gtlcore$enableIntExpressionInput(int minValue, int maxValue, CallbackInfoReturnable<TextFieldWidget> cir) {
        this.gtlcore$numericExpressionInput = true;
    }

    @Inject(method = "setNumbersOnly(FF)Lcom/lowdragmc/lowdraglib/gui/widget/TextFieldWidget;", at = @At("TAIL"), remap = false)
    private void gtlcore$enableFloatExpressionInput(float minValue, float maxValue, CallbackInfoReturnable<TextFieldWidget> cir) {
        this.gtlcore$numericExpressionInput = true;
    }

    @ModifyVariable(method = "onTextChanged", at = @At("HEAD"), argsOnly = true, index = 1, remap = false)
    private String gtlcore$parseNumericExpression(String newTextString) {
        this.gtlcore$rawExpression = null;
        if (!this.gtlcore$numericExpressionInput || newTextString == null || newTextString.isEmpty()) {
            return newTextString;
        }
        if (newTextString.equals(this.textValidator.apply(newTextString))) {
            return newTextString;
        }

        try {
            Optional<BigDecimal> parsed = MathExpressionParser.parse(newTextString, gtlcore$getExpressionFormat());
            if (parsed.isEmpty()) {
                return newTextString;
            }

            String normalized = parsed.get().stripTrailingZeros().toPlainString();
            if (!normalized.equals(this.textValidator.apply(normalized))) {
                return newTextString;
            }
            if (!normalized.equals(newTextString)) {
                this.gtlcore$rawExpression = newTextString;
            }
            return normalized;
        } catch (ArithmeticException | NumberFormatException ignored) {
            return newTextString;
        }
    }

    @Inject(method = "onTextChanged", at = @At("RETURN"), remap = false)
    private void gtlcore$restoreExpressionDisplay(String newTextString, CallbackInfo ci) {
        String rawExpression = this.gtlcore$rawExpression;
        this.gtlcore$rawExpression = null;
        if (rawExpression != null && this.textField != null && !rawExpression.equals(this.textField.getValue())) {
            this.textField.setValue(rawExpression);
        }
    }

    @Unique
    private DecimalFormat gtlcore$getExpressionFormat() {
        if (this.gtlcore$expressionFormat == null) {
            this.gtlcore$expressionFormat = new DecimalFormat("#.######", new DecimalFormatSymbols());
            this.gtlcore$expressionFormat.setParseBigDecimal(true);
            this.gtlcore$expressionFormat.setNegativePrefix("-");
        }
        return this.gtlcore$expressionFormat;
    }

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
