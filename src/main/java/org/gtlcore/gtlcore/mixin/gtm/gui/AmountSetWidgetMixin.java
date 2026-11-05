package org.gtlcore.gtlcore.mixin.gtm.gui;

import org.gtlcore.gtlcore.client.gui.widget.AmountBoxRenderer;

import com.gregtechceu.gtceu.integration.ae2.gui.widget.AEFluidConfigWidget;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AmountSetWidget;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.ConfigWidget;

import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.utils.Position;

import net.minecraft.client.gui.GuiGraphics;

import appeng.api.stacks.AEFluidKey;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AmountSetWidget.class, remap = false)
public abstract class AmountSetWidgetMixin {

    private static final int WIDGET_WIDTH = AmountBoxRenderer.WIDTH;
    private static final int WIDGET_HEIGHT = AmountBoxRenderer.HEIGHT;
    private static final int GUI_WIDTH = AmountBoxRenderer.GUI_WIDTH;
    private static final int WIDGET_GAP = AmountBoxRenderer.GAP;
    private static final int INPUT_X = AmountBoxRenderer.TEXT_X;
    private static final int INPUT_Y = AmountBoxRenderer.INPUT_Y;
    private static final int INPUT_WIDTH = AmountBoxRenderer.INPUT_WIDTH;
    private static final int INPUT_HEIGHT = AmountBoxRenderer.INPUT_HEIGHT;

    @Shadow(remap = false)
    @Final
    @Mutable
    private TextFieldWidget amountText;

    @Shadow(remap = false)
    @Final
    private ConfigWidget parentWidget;

    @Shadow(remap = false)
    private int index;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void configureAmountBox(int x, int y, ConfigWidget widget, CallbackInfo ci) {
        int boxX = GUI_WIDTH + WIDGET_GAP - parentWidget.getSelfPositionX();
        int boxY = Math.max(0, (parentWidget.getSizeHeight() - WIDGET_HEIGHT) / 2);
        amountText = new TextFieldWidget(
                boxX + INPUT_X,
                boxY + INPUT_Y,
                INPUT_WIDTH,
                INPUT_HEIGHT,
                ((AmountSetWidget) (Object) this)::getAmountStr,
                ((AmountSetWidget) (Object) this)::setNewAmount)
                .setNumbersOnly(0, Long.MAX_VALUE)
                .setMaxStringLength(19)
                .setBordered(true);
        applyLayout();
    }

    private void applyLayout() {
        int boxX = GUI_WIDTH + WIDGET_GAP - parentWidget.getSelfPositionX();
        int boxY = Math.max(0, (parentWidget.getSizeHeight() - WIDGET_HEIGHT) / 2);
        Widget self = (Widget) (Object) this;
        self.setSelfPosition(boxX, boxY);
        self.setSize(WIDGET_WIDTH, WIDGET_HEIGHT);
        amountText.setSelfPosition(boxX + INPUT_X, boxY + INPUT_Y);
        amountText.setParentPosition(parentWidget.getPosition());
        amountText.setSize(INPUT_WIDTH, INPUT_HEIGHT);
    }

    @Inject(method = "drawInBackground", at = @At("HEAD"), cancellable = true, remap = false)
    private void drawEnlargedAmountBox(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks,
                                       CallbackInfo ci) {
        applyLayout();
        Position position = ((Widget) (Object) this).getPosition();
        AmountBoxRenderer.draw(graphics, mouseX, mouseY, position.x, position.y, amountLabel());
        ci.cancel();
    }

    private String amountLabel() {
        if (parentWidget instanceof AEFluidConfigWidget) return AmountBoxRenderer.label(true);
        if (index >= 0) {
            var stack = parentWidget.getConfig(index).getConfig();
            if (stack != null && stack.what() instanceof AEFluidKey) return AmountBoxRenderer.label(true);
        }
        return AmountBoxRenderer.label(false);
    }
}
