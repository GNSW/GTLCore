package org.gtlcore.gtlcore.client.gui.widget;

import com.gregtechceu.gtceu.api.gui.GuiTextures;

import com.lowdragmc.lowdraglib.gui.util.DrawerHelper;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class AmountBoxRenderer {

    public static final int WIDTH = 96;
    public static final int HEIGHT = 36;
    public static final int GUI_WIDTH = 176;
    public static final int GAP = -8;
    public static final int TEXT_X = 4;
    public static final int TEXT_Y = 3;
    public static final int INPUT_Y = 12;
    public static final int INPUT_WIDTH = 88;
    public static final int INPUT_HEIGHT = 18;

    private AmountBoxRenderer() {}

    public static String label(boolean fluid) {
        return Component.translatable(fluid ? "gui.gtlcore.amount_fluid" : "gui.gtlcore.amount").getString();
    }

    public static void draw(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, String label) {
        GuiTextures.BACKGROUND.draw(graphics, mouseX, mouseY, x, y, WIDTH, HEIGHT);
        DrawerHelper.drawStringSized(graphics, label, x + TEXT_X, y + TEXT_Y, 0x404040, false, 1.0F, false);
    }
}
