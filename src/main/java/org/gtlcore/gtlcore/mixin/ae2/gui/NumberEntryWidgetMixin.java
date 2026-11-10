package org.gtlcore.gtlcore.mixin.ae2.gui;

import org.gtlcore.gtlcore.client.gui.widget.IShiftAmountOperations;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;

import appeng.client.Point;
import appeng.client.gui.MathExpressionParser;
import appeng.client.gui.widgets.ConfirmableTextField;
import appeng.client.gui.widgets.NumberEntryWidget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;

@Mixin(NumberEntryWidget.class)
public abstract class NumberEntryWidgetMixin implements IShiftAmountOperations {

    @Unique
    private static final Component[] GTLCORE$SHIFT_BUTTON_LABELS = {
            Component.literal("*2"),
            Component.literal("*5"),
            Component.literal("+int"),
            Component.literal("=long"),
            Component.literal("/2"),
            Component.literal("/5"),
            Component.literal("-int"),
            Component.literal("=0")
    };

    @Unique
    private static final long GTLCORE$MIN_AMOUNT = 0;

    @Unique
    private static final long GTLCORE$TWO = 2;

    @Unique
    private static final long GTLCORE$FIVE = 5;

    @Unique
    private static final long GTLCORE$INT_MAX = Integer.MAX_VALUE;

    @Unique
    private static final long GTLCORE$LONG_MAX = Long.MAX_VALUE;

    @Shadow(remap = false)
    @Final
    private static long[] STEPS;

    @Shadow(remap = false)
    private List<Button> buttons;

    @Shadow(remap = false)
    @Final
    private ConfirmableTextField textField;

    @Shadow(remap = false)
    @Final
    private DecimalFormat decimalFormat;

    @Unique
    private static final Pattern GTLCORE$PLAIN_NUMBER = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)");

    @Unique
    private String gtlcore$pendingExpression;

    @Unique
    private BigDecimal gtlcore$pendingExpressionValue;

    @Shadow(remap = false)
    private long maxValue;

    @Unique
    private boolean gtlcore$shiftAmountOperationsEnabled;

    @Unique
    private boolean gtlcore$showingShiftButtonLabels;

    @Unique
    private Component[] gtlcore$normalButtonLabels;

    @Shadow(remap = false)
    public abstract OptionalLong getLongValue();

    @Shadow(remap = false)
    public abstract void setMaxValue(long value);

    @Shadow(remap = false)
    public abstract void setMinValue(long value);

    @Shadow(remap = false)
    public abstract void setLongValue(long value);

    @Override
    public void gtlcore$enableShiftAmountOperations() {
        this.gtlcore$shiftAmountOperationsEnabled = true;
        this.setMinValue(GTLCORE$MIN_AMOUNT);
        this.setMaxValue(GTLCORE$LONG_MAX);
    }

    @Inject(method = "drawBackgroundLayer", at = @At("HEAD"), remap = false)
    private void gtlcore$updateShiftButtonLabels(GuiGraphics guiGraphics, Rect2i bounds, Point mouse,
                                                 CallbackInfo ci) {
        if (!this.gtlcore$shiftAmountOperationsEnabled || this.buttons == null ||
                this.buttons.size() < GTLCORE$SHIFT_BUTTON_LABELS.length) {
            return;
        }

        boolean showShiftLabels = Screen.hasShiftDown();
        if (this.gtlcore$normalButtonLabels == null) {
            this.gtlcore$normalButtonLabels = new Component[GTLCORE$SHIFT_BUTTON_LABELS.length];
            for (int i = 0; i < this.gtlcore$normalButtonLabels.length; i++) {
                this.gtlcore$normalButtonLabels[i] = this.buttons.get(i).getMessage();
            }
        }
        if (showShiftLabels == this.gtlcore$showingShiftButtonLabels) {
            return;
        }

        Component[] labels = showShiftLabels ? GTLCORE$SHIFT_BUTTON_LABELS : this.gtlcore$normalButtonLabels;
        for (int i = 0; i < labels.length; i++) {
            this.buttons.get(i).setMessage(labels[i]);
        }
        this.gtlcore$showingShiftButtonLabels = showShiftLabels;
    }

    @Inject(method = "addQty", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$applyShiftAmountOperation(long delta, CallbackInfo ci) {
        if (!this.gtlcore$shiftAmountOperationsEnabled || !Screen.hasShiftDown()) {
            return;
        }

        long currentValue = this.getLongValue().orElse(0);
        if (delta == STEPS[0]) {
            this.gtlcore$multiply(currentValue, GTLCORE$TWO);
        } else if (delta == STEPS[1]) {
            this.gtlcore$multiply(currentValue, GTLCORE$FIVE);
        } else if (delta == STEPS[2]) {
            this.gtlcore$add(currentValue, GTLCORE$INT_MAX);
        } else if (delta == STEPS[3]) {
            this.setLongValue(GTLCORE$LONG_MAX);
        } else if (delta == -STEPS[0]) {
            this.setLongValue(currentValue / GTLCORE$TWO);
        } else if (delta == -STEPS[1]) {
            this.setLongValue(currentValue / GTLCORE$FIVE);
        } else if (delta == -STEPS[2]) {
            this.setLongValue(Math.max(GTLCORE$MIN_AMOUNT, currentValue - GTLCORE$INT_MAX));
        } else if (delta == -STEPS[3]) {
            this.setLongValue(GTLCORE$MIN_AMOUNT);
        } else {
            return;
        }
        ci.cancel();
    }

    @Unique
    private void gtlcore$multiply(long currentValue, long factor) {
        long result = currentValue > this.maxValue / factor ? this.maxValue : currentValue * factor;
        this.setLongValue(result);
    }

    @Unique
    private void gtlcore$add(long currentValue, long increment) {
        long result = currentValue > this.maxValue - increment ? this.maxValue : currentValue + increment;
        this.setLongValue(result);
    }

    /**
     * Some AE2 screens push a value back into the text field (for example after confirming an amount).
     * When that value is exactly what the player's expression already evaluates to, keep the expression
     * visible instead of replacing it with the expanded number.
     */
    @Inject(method = "setLongValue", at = @At("HEAD"), remap = false)
    private void gtlcore$captureAmountExpression(long value, CallbackInfo ci) {
        this.gtlcore$pendingExpression = null;
        this.gtlcore$pendingExpressionValue = null;
        if (this.textField == null) {
            return;
        }
        String text = this.textField.getValue();
        if (text == null || text.isEmpty() || GTLCORE$PLAIN_NUMBER.matcher(text.trim()).matches()) {
            return;
        }
        try {
            Optional<BigDecimal> parsed = MathExpressionParser.parse(text, this.decimalFormat);
            if (parsed.isPresent()) {
                this.gtlcore$pendingExpression = text;
                this.gtlcore$pendingExpressionValue = parsed.get();
            }
        } catch (ArithmeticException | NumberFormatException ignored) {
            // Keep the normal numeric display.
        }
    }

    @Inject(method = "setLongValue", at = @At("TAIL"), remap = false)
    private void gtlcore$restoreAmountExpression(long value, CallbackInfo ci) {
        String expression = this.gtlcore$pendingExpression;
        BigDecimal expected = this.gtlcore$pendingExpressionValue;
        this.gtlcore$pendingExpression = null;
        this.gtlcore$pendingExpressionValue = null;
        if (expression == null || expected == null || this.textField == null) {
            return;
        }
        try {
            BigDecimal actual = new BigDecimal(this.textField.getValue().trim());
            if (actual.compareTo(expected) == 0) {
                this.textField.setValue(expression);
                this.textField.setCursorPosition(expression.length());
            }
        } catch (NumberFormatException ignored) {
            // The screen wrote a different text; leave it alone.
        }
    }
}
