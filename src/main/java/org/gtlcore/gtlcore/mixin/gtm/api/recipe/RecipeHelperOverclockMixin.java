package org.gtlcore.gtlcore.mixin.gtm.api.recipe;

import org.gtlcore.gtlcore.utils.StandardOverclockBudget;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.OverclockingLogic;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.logic.OCParams;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RecipeHelper.class)
public abstract class RecipeHelperOverclockMixin {

    @Redirect(
              method = "performOverclocking(Lcom/gregtechceu/gtceu/api/recipe/OverclockingLogic;Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;JJLcom/gregtechceu/gtceu/api/recipe/logic/OCParams;Lcom/gregtechceu/gtceu/api/recipe/logic/OCResult;)V",
              at = @At(value = "INVOKE", target = "Lcom/gregtechceu/gtceu/api/recipe/logic/OCParams;initialize(JII)V"),
              remap = false,
              require = 1)
    private static void gtlcore$extendStandardOverclockBudget(
                                                              OCParams receiver, long eut, int duration, int originalBudget,
                                                              OverclockingLogic logic, GTRecipe recipe,
                                                              long recipeEUt, long maxVoltage,
                                                              OCParams params, OCResult result) {
        boolean standardFourfold = logic == OverclockingLogic.NON_PERFECT_OVERCLOCK ||
                logic == OverclockingLogic.PERFECT_OVERCLOCK ||
                logic == OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK ||
                logic == OverclockingLogic.PERFECT_OVERCLOCK_SUBTICK;
        int budget = standardFourfold ?
                StandardOverclockBudget.extend(originalBudget, eut, maxVoltage) : originalBudget;
        receiver.initialize(eut, duration, budget);
    }
}
