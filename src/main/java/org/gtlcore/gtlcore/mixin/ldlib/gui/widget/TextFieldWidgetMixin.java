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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

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
    protected String currentString;

    @Shadow(remap = false)
    public abstract String getCurrentString();

    @Shadow(remap = false)
    protected abstract void onTextChanged(String newTextString);

    @Unique
    private static final Pattern GTLCORE$PLAIN_NUMBER = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)");

    @Unique
    private boolean gtlcore$numericExpressionInput;

    /**
     * Set while we feed the evaluated number back into the vanilla handler.
     */
    @Unique
    private boolean gtlcore$applyingValue;

    /**
     * Raw text the player typed that must stay visible instead of being replaced by its numeric value.
     */
    @Unique
    private String gtlcore$displayExpression;

    /**
     * Numeric value represented by {@link #gtlcore$displayExpression}, if it is a complete expression.
     */
    @Unique
    private BigDecimal gtlcore$expressionValue;

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

    /**
     * Replaces the vanilla numeric text handler for numeric fields.
     * <p>
     * The edit box keeps exactly what the player typed. Only the stored {@code currentString}
     * (and therefore the value sent to the server) is replaced with the evaluated number.
     */
    @Inject(method = "onTextChanged", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$handleNumericExpression(String rawText, CallbackInfo ci) {
        if (!this.gtlcore$numericExpressionInput || this.gtlcore$applyingValue) {
            return;
        }
        // Dedicated servers have no EditBox; let the vanilla handler do its normal work there.
        if (this.textField == null) {
            return;
        }

        ci.cancel();

        String raw = rawText == null ? "" : rawText;
        if (!raw.equals(this.textField.getValue())) {
            // Programmatic change (mouse wheel, drag): put the text into the box first.
            this.textField.setValue(raw);
        }

        String applied = null;
        BigDecimal value = null;

        if (!raw.isEmpty() && !gtlcore$endsWithExponentMarker(raw)) {
            Optional<BigDecimal> parsed = this.gtlcore$parseExpression(raw);
            if (parsed.isPresent()) {
                String normalized = parsed.get().stripTrailingZeros().toPlainString();
                if (normalized.equals(this.textValidator.apply(normalized))) {
                    applied = normalized;
                    value = parsed.get();
                }
            }
        }

        if (applied == null && !raw.isEmpty() && GTLCORE$PLAIN_NUMBER.matcher(raw.trim()).matches()) {
            try {
                BigDecimal plain = new BigDecimal(raw.trim());
                String normalized = plain.stripTrailingZeros().toPlainString();
                if (raw.equals(this.textValidator.apply(raw))) {
                    applied = raw;
                    value = plain;
                } else if (normalized.equals(this.textValidator.apply(normalized))) {
                    applied = normalized;
                    value = plain;
                }
            } catch (NumberFormatException ignored) {
                // Not a valid plain number after all.
            }
        }

        if (applied == null) {
            // Keep the player's text (including an empty box or a partial expression) and mark it invalid.
            this.gtlcore$displayExpression = raw;
            this.gtlcore$expressionValue = null;
            this.textField.setTextColor(0xFFDF0000);
            return;
        }

        boolean keepRaw = !raw.equals(applied);
        this.gtlcore$displayExpression = keepRaw ? raw : null;
        this.gtlcore$expressionValue = keepRaw ? value : null;

        // Feed the evaluated number through the vanilla path so colour handling, text responder and
        // server synchronization all keep working, while setCurrentString is prevented from rewriting
        // the edit box below.
        this.gtlcore$applyingValue = true;
        try {
            this.onTextChanged(applied);
        } finally {
            this.gtlcore$applyingValue = false;
        }
    }

    /**
     * The vanilla {@code setCurrentString} is used by suppliers and server synchronization.
     * While the player is editing, it may update the stored number but must never replace the typed text.
     */
    @Inject(method = "setCurrentString", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$protectTypedText(Object newValue, CallbackInfoReturnable<TextFieldWidget> cir) {
        if (this.textField == null) {
            return;
        }

        String newText = newValue == null ? null : newValue.toString();
        if (((Widget) (Object) this).isFocus()) {
            this.currentString = newText;
            cir.setReturnValue((TextFieldWidget) (Object) this);
            return;
        }

        String expression = this.gtlcore$displayExpression;
        if (expression == null) {
            return;
        }
        if (this.gtlcore$expressionValue != null &&
                this.gtlcore$sameValue(newText, this.gtlcore$expressionValue)) {
            this.currentString = newText;
            cir.setReturnValue((TextFieldWidget) (Object) this);
            return;
        }

        // The value was changed from outside; let the numeric display through.
        this.gtlcore$displayExpression = null;
        this.gtlcore$expressionValue = null;
    }

    @Unique
    private static boolean gtlcore$endsWithExponentMarker(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char last = text.charAt(text.length() - 1);
        return last == 'e' || last == 'E';
    }

    @Unique
    private Optional<BigDecimal> gtlcore$parseExpression(String expression) {
        try {
            return MathExpressionParser.parse(expression, gtlcore$getExpressionFormat());
        } catch (ArithmeticException | NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    @Unique
    private boolean gtlcore$sameValue(String text, BigDecimal value) {
        if (text == null) {
            return false;
        }
        try {
            return new BigDecimal(text.trim()).compareTo(value) == 0;
        } catch (NumberFormatException ignored) {
            Optional<BigDecimal> parsed = this.gtlcore$parseExpression(text);
            return parsed.isPresent() && parsed.get().compareTo(value) == 0;
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
