package org.gtlcore.gtlcore.api.machine.trait;

import com.gregtechceu.gtceu.api.capability.IDataAccessHatch;
import com.gregtechceu.gtceu.api.capability.IParallelHatch;
import com.gregtechceu.gtceu.api.capability.recipe.*;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMaintenanceMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import it.unimi.dsi.fastutil.objects.ReferenceSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public interface IRecipeCapabilityMachine {

    @NotNull
    List<RecipeHandlePart> getNormalRecipeHandlePart(IO io);

    @Nullable
    RecipeHandlePart getSharedRecipeHandlePart();

    boolean emptyRecipeHandlePart();

    boolean emptyHandlePart();

    // ==================== ME ====================

    List<MEPatternRecipeHandlePart> getMEPatternRecipeHandleParts();

    List<MEIORecipeHandlePart<?>> getMEOutputRecipeHandleParts();

    void tryAddAndActiveMERhp(MEPatternRecipeHandlePart part, GTRecipe recipe, int slot);

    void sortMEOutput();

    // ==================== Cache ====================

    @Nullable
    IRecipeHandlePart getActiveRecipeHandle(GTRecipe recipe);

    @NotNull
    Iterator<@NotNull IRecipeHandlePart> getAllCachedRecipeHandlesIter(GTRecipe recipe);

    @NotNull
    ReferenceSet<@NotNull IRecipeHandlePart> getAllCachedRecipeHandles(GTRecipe recipe);

    void tryAddAndActiveRhp(GTRecipe recipe, IRecipeHandlePart part);

    // ==================== Structure Form ====================

    void upDate();

    boolean isDistinct();

    void setDistinct(boolean isDistinct);

    default boolean itemOutPutAlwaysMatch() {
        return false;
    }

    default boolean fluidOutPutAlwaysMatch() {
        return false;
    }

    default boolean isRecipeOutputAlwaysMatch(GTRecipe recipe) {
        return false;
    }

    default IParallelHatch getParallelHatch() {
        return null;
    }

    default IMaintenanceMachine getMaintenanceMachine() {
        return null;
    }

    default IDataAccessHatch getDataAccessHatch() {
        return null;
    }

    static void attachConfigurators(ConfiguratorPanel configuratorPanel, WorkableElectricMultiblockMachine machine) {
        if (machine instanceof IRecipeCapabilityMachine distinctMachine) {
            configuratorPanel.attachConfigurators(new IFancyConfiguratorButton.Toggle(
                    GuiTextures.BUTTON_DISTINCT_BUSES.getSubTexture(0, 0.5, 1, 0.5),
                    GuiTextures.BUTTON_DISTINCT_BUSES.getSubTexture(0, 0, 1, 0.5),
                    distinctMachine::isDistinct, (clickData, pressed) -> {
                        distinctMachine.setDistinct(pressed);
                        distinctMachine.upDate();
                    })
                    .setTooltipsSupplier(pressed -> List.of(Component.translatable("gtceu.multiblock.universal.distinct.all").setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW))
                            .append(Component.translatable(pressed ? "gtceu.multiblock.universal.distinct.yes" : "gtceu.multiblock.universal.distinct.no")))));
        }
    }
}
