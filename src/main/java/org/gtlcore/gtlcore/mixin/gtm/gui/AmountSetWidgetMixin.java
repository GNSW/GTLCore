package org.gtlcore.gtlcore.mixin.gtm.gui;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AmountSetWidget;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.ConfigWidget;

import com.lowdragmc.lowdraglib.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.utils.Position;

import net.minecraft.client.gui.GuiGraphics;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AmountSetWidget.class, remap = false)
public abstract class AmountSetWidgetMixin {

    private static final int WIDGET_WIDTH = 96;
    private static final int WIDGET_HEIGHT = 36;
    private static final int INPUT_X = 4;
    private static final int INPUT_Y = 12;
    private static final int INPUT_WIDTH = 88;
    private static final int INPUT_HEIGHT = 18;

    @Shadow(remap = false)
    @Final
    private TextFieldWidget amountText;

    @Shadow(remap = false)
    @Final
    private ConfigWidget parentWidget;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void configureAmountBox(int x, int y, ConfigWidget widget, CallbackInfo ci) {
        int boxX = (parentWidget.getSizeWidth() - WIDGET_WIDTH) / 2;
        int boxY = parentWidget.getSizeHeight() + 4;
        Widget self = (Widget) (Object) this;
        self.setSelfPosition(boxX, boxY);
        self.setSize(WIDGET_WIDTH, WIDGET_HEIGHT);
        amountText.setSelfPosition(boxX + INPUT_X, boxY + INPUT_Y);
        amountText.setSize(INPUT_WIDTH, INPUT_HEIGHT);
        amountText.setNumbersOnly(0, Long.MAX_VALUE).setMaxStringLength(19);
    }

    @Inject(method = "drawInBackground", at = @At("HEAD"), cancellable = true, remap = false)
    private void drawEnlargedAmountBox(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks,
                                       CallbackInfo ci) {
        Position position = ((Widget) (Object) this).getPosition();
        GuiTextures.BACKGROUND.draw(graphics, mouseX, mouseY,
                position.x, position.y, WIDGET_WIDTH, WIDGET_HEIGHT);
        DrawerHelper.drawStringSized(graphics, "Amount",
                position.x + INPUT_X, position.y + 4, 0x404040, false, 1.0F, false);
        GuiTextures.DISPLAY.draw(graphics, mouseX, mouseY,
                position.x + INPUT_X, position.y + INPUT_Y, INPUT_WIDTH, INPUT_HEIGHT);
        ci.cancel();
    }
}
