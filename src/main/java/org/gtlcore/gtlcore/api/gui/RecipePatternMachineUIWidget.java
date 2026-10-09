package org.gtlcore.gtlcore.api.gui;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;

import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

/** Keeps GT navigation and side tabs while styling only the two recipe management pages. */
public class RecipePatternMachineUIWidget extends FancyMachineUIWidget {

    private final IGuiTexture machineBackground;
    private final WidgetGroup recipeTitleBar;

    public RecipePatternMachineUIWidget(IFancyUIProvider mainPage, int width, int height) {
        super(mainPage, width, height);
        machineBackground = getBackgroundTexture();
        recipeTitleBar = new WidgetGroup(26, -16, width - 52, 16);
        addWidget(recipeTitleBar);
    }

    @Override
    protected void setupFancyUI(IFancyUIProvider page, boolean hasPlayerInventory) {
        super.setupFancyUI(page, hasPlayerInventory);
        boolean managementPage = page instanceof RecipePatternManagementPage;
        setBackground(managementPage ? RecipePatternUiTextures.BACKGROUND : machineBackground);
        // Own the complete header layout so GT navigation insets cannot shift the icon on either style.
        titleBar.setVisible(false).setActive(false);
        recipeTitleBar.clearAllWidgets();
        int titleWidth = getSize().width - 52;
        recipeTitleBar.setSize(titleWidth, 16);
        recipeTitleBar.setBackground(managementPage ? RecipePatternUiTextures.BACKGROUND : GuiTextures.TITLE_BAR_BACKGROUND);
        recipeTitleBar.addWidget(new ImageWidget(4, 2, 11, 11, currentHomePage.getTabIcon()));
        recipeTitleBar.addWidget(new RecipePatternLabelWidget(19, 1, titleWidth - 22, 13,
                () -> currentHomePage.getTitle().getString(), 0xFF000000, true));
    }
}
