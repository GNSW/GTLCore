package org.gtlcore.gtlcore.api.gui;

import com.lowdragmc.lowdraglib.gui.texture.*;

/** Native AE2 textures for the recipe management pages and overview counters. */
public final class RecipePatternUiTextures {

    public static final IGuiTexture BACKGROUND = new ResourceBorderTexture("ae2:textures/guis/background.png", 256, 256, 4, 4);
    public static final IGuiTexture BUTTON = toolbarButton();
    public static final IGuiTexture BUTTON_HOVER = new ColorBorderTexture(1, 0xFFFFFFFF);
    public static final IGuiTexture SLOT = new ResourceTexture("ae2:textures/guis/states.png").getSubTexture(192 / 256.0, 192 / 256.0, 18 / 256.0, 18 / 256.0);
    public static final IGuiTexture ROW = new ResourceBorderTexture("gtlcore:textures/gui/wireless/button_normal.png", 80, 20, 2, 2);
    public static final IGuiTexture ROW_SELECTED = new ResourceBorderTexture("gtlcore:textures/gui/wireless/button_selected.png", 80, 20, 2, 2);
    public static final IGuiTexture ROW_HOVER = new ResourceBorderTexture("gtlcore:textures/gui/wireless/button_hover.png", 80, 20, 2, 2);
    public static final IGuiTexture RULE_BACKGROUND = new ResourceBorderTexture("gtlcore:textures/gui/wireless/inset_panel.png", 120, 52, 4, 4);
    public static final IGuiTexture INPUT_BACKGROUND = new GuiTextureGroup(new ColorRectTexture(0xFF373737), new ColorBorderTexture(1, 0xFF7E7E7E));
    public static final int TEXT_COLOR = 0xFF404040;
    public static final int BUTTON_TEXT_COLOR = 0xFFFFFFFF;
    public static final int MUTED_COLOR = 0xFF7E7E7E;
    public static final int PANEL_COLOR = 0xFFC6C6C6;
    public static final int ROW_COLOR = 0xFFBDBDBD;
    public static final int SELECTED_COLOR = 0xFFAEAEAE;
    public static final int HOVER_COLOR = 0xFFE0E0E0;
    public static final IGuiTexture COUNTER_BACKGROUND = BACKGROUND;
    public static final int COUNTER_TEXT_COLOR = 0xFF404040;
    public static final int COUNTER_MUTED_COLOR = 0xFF7E7E7E;
    public static final int COUNTER_PUBLISHED_COLOR = 0xFF2D8054;
    public static final int COUNTER_ERROR_COLOR = 0xFFCE2401;

    private static ResourceBorderTexture toolbarButton() {
        // AE2 Icon.TOOLBAR_BUTTON_BACKGROUND, sliced within states.png rather than stretched as an atlas.
        ResourceBorderTexture texture = new ResourceBorderTexture("ae2:textures/guis/states.png", 16, 16, 2, 2);
        texture.offsetX = 240 / 256.0f;
        texture.offsetY = 240 / 256.0f;
        texture.imageWidth = 16 / 256.0f;
        texture.imageHeight = 16 / 256.0f;
        return texture;
    }

    private RecipePatternUiTextures() {}
}
