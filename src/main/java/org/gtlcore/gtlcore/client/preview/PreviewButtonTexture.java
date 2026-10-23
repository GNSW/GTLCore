package org.gtlcore.gtlcore.client.preview;

import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.gui.editor.Icons;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ResourceBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;

import net.minecraft.client.gui.GuiGraphics;

/** Keeps the small preview's existing expand icon and resource-pack-aware button. */
final class PreviewButtonTexture implements IGuiTexture {

    static final IGuiTexture EXPAND = new PreviewButtonTexture();
    static final IGuiTexture INFO = LDLib.isJeiLoaded() ?
            new ResourceTexture("jei:textures/jei/atlas/gui/icons/info.png") : Icons.INFORMATION;
    static final IGuiTexture PATTERN_MATERIALS = new ResourceTexture(
            "jei:textures/jei/atlas/gui/icons/recipe_transfer.png");
    static final IGuiTexture ROTATION_ORIGIN = org.gtlcore.gtlcore.api.gui.GuiTextures.BUTTON_VISIBLE.getSubTexture(0, 0, 1, 0.5);
    static final IGuiTexture ROTATION_ORIGIN_HIDDEN = org.gtlcore.gtlcore.api.gui.GuiTextures.BUTTON_VISIBLE.getSubTexture(0, 0.5, 1, 0.5);
    private static final IGuiTexture NORMAL = new ResourceBorderTexture(
            "jei:textures/jei/atlas/gui/button_enabled.png", 20, 20, 2, 2);
    private static final IGuiTexture HOVER = new ResourceBorderTexture(
            "jei:textures/jei/atlas/gui/button_highlight.png", 20, 20, 2, 2);
    private static final ResourceTexture ICON = new ResourceTexture("gtlcore:textures/gui/preview/expand.png");
    private static final int ICON_SIZE = 16;

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float x, float y, int width, int height) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        var background = LDLib.isJeiLoaded() ? (hovered ? HOVER : NORMAL) : ResourceBorderTexture.BUTTON_COMMON;
        background.draw(graphics, mouseX, mouseY, x, y, width, height);
        ICON.draw(graphics, mouseX, mouseY, x + (width - ICON_SIZE) / 2f, y + (height - ICON_SIZE) / 2f,
                ICON_SIZE, ICON_SIZE);
    }
}
