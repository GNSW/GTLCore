package org.gtlcore.gtlcore.api.machine.trait;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import net.minecraft.network.chat.Component;

import java.util.*;

public interface ILockRecipe {

    default boolean isLock() {
        return false;
    }

    default void setLock(boolean lockRecipe) {}

    default GTRecipe getLockRecipe() {
        return null;
    }

    default void setLockRecipe(GTRecipe lockRecipe) {}

    static void attachRecipeLockable(ConfiguratorPanel configuratorPanel, RecipeLogic logic) {
        if (logic instanceof ILockRecipe iLockRecipe) {
            configuratorPanel.attachConfigurators(new IFancyConfiguratorButton.Toggle(
                    GuiTextures.BUTTON_PUBLIC_PRIVATE.getSubTexture(0, 0, 1, 0.5),
                    GuiTextures.BUTTON_PUBLIC_PRIVATE.getSubTexture(0, 0.5, 1, 0.5),
                    iLockRecipe::isLock, (clickData, pressed) -> iLockRecipe.setLock(pressed))
                    .setTooltipsSupplier(pressed -> List.of(Component.translatable("config.gtceu.option.recipes")
                            .append("[").append(Component.translatable(pressed ? "theoneprobe.ae2.locked" : "theoneprobe.ae2.unlocked").append("]")))));
        }
    }
}
