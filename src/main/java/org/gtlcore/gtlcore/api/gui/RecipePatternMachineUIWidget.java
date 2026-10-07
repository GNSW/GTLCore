package org.gtlcore.gtlcore.api.gui;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;

import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

/** Keeps GT navigation and side tabs while styling only the two recipe management pages. */
public class RecipePatternMachineUIWidget extends FancyMachineUIWidget {

    private final IGuiTexture machineBackground;

    public RecipePatternMachineUIWidget(IFancyUIProvider mainPage, int width, int height) {
        super(mainPage, width, height);
        machineBackground = getBackgroundTexture();
    }

    @Override
    protected void setupFancyUI(IFancyUIProvider page, boolean hasPlayerInventory) {
        super.setupFancyUI(page, hasPlayerInventory);
        // Search and list management already have side tabs, so neither header navigation button is needed.
        titleBar.updateState(currentHomePage, false, false);
        boolean managementPage = page instanceof RecipePatternManagementPage;
        setBackground(managementPage ? RecipePatternUiTextures.BACKGROUND : machineBackground);
        for (Widget section : titleBar.widgets) {
            if (section instanceof WidgetGroup) {
                section.setBackground(managementPage ? RecipePatternUiTextures.BACKGROUND : GuiTextures.TITLE_BAR_BACKGROUND);
            }
        }
    }
}
