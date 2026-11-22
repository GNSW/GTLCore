package org.gtlcore.gtlcore.api.recipe;

import org.gtlcore.gtlcore.api.machine.trait.IRecipeStatus;

import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.electric.research.DataBankMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.electric.research.HPCAMachine;

import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.Nullable;

public record RecipeResult(boolean isSuccess, @Nullable Component reason) {

    public static void of(IRecipeLogicMachine machine, RecipeResult result) {
        if (machine.getRecipeLogic() instanceof IRecipeStatus status) {
            if (machine instanceof HPCAMachine || machine instanceof DataBankMachine) result = null;
            status.setRecipeStatus(result);
        }
    }

    public static void ofWorking(IRecipeLogicMachine machine, RecipeResult result) {
        if (machine.getRecipeLogic() instanceof IRecipeStatus status) status.setWorkingStatus(result);
    }

    public static final RecipeResult SUCCESS = new RecipeResult(true, null);
    public static final RecipeResult FAIL_FIND = fail(Component.translatable("gtceu.recipe.fail.find"));
    public static final RecipeResult FAIL_INPUT = fail(Component.translatable("gtceu.recipe.fail.Input"));
    public static final RecipeResult FAIL_OUTPUT = fail(Component.translatable("gtceu.recipe.fail.Output"));
    public static final RecipeResult FAIL_VOLTAGE_TIER = fail(Component.translatable("gtceu.recipe.fail.voltage.tier"));
    public static final RecipeResult FAIL_NO_ENOUGH_EU_IN = fail(Component.translatable("gtceu.recipe.fail.no.enough.eu.in"));
    public static final RecipeResult FAIL_NO_ENOUGH_EU_OUT = fail(Component.translatable("gtceu.recipe.fail.no.enough.eu.out"));
    public static final RecipeResult FAIL_NO_ENOUGH_CWU_IN = fail(Component.translatable("gtceu.recipe.fail.no.enough.cwu.in"));
    public static final RecipeResult FAIL_NO_SKYLIGHT = fail(Component.translatable("gtceu.recipe.fail.no.skylight"));
    public static final RecipeResult FAIL_PROCESSING_PLANT_NO_INPUT = fail(Component.translatable("gtceu.recipe.fail.processing.plant.no.input"));
    public static final RecipeResult FAIL_PROCESSING_PLANT_WRONG_INPUT = fail(Component.translatable("gtceu.recipe.fail.processing.plant.wrong.input"));
    public static final RecipeResult FAIL_NO_FIND_RESEARCHED = fail(Component.translatable("gtceu.recipe.fail.no.find.researched"));
    public static final RecipeResult FAIL_LACK_FLUID = fail(Component.translatable("recipe.condition.rock_breaker.tooltip"));
    public static final RecipeResult FAIL_NO_ENOUGH_TEMPERATURE = fail(Component.translatable("gtceu.recipe.fail.no.enough.temperature"));
    public static final RecipeResult FAIL_NO_ENOUGH_TIER = fail(Component.translatable("gtceu.recipe.fail.no.enough.recipe.tier"));

    public static RecipeResult fail(@Nullable Component reason) {
        return new RecipeResult(false, reason);
    }
}
