package org.gtlcore.gtlcore.common.machine.trait;

import org.gtlcore.gtlcore.api.recipe.RecipeExtensionCopier;
import org.gtlcore.gtlcore.common.machine.multiblock.generator.GeneratorArrayMachine;

import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

/** Restores the selected energy route before checking or committing any tick output. */
public class GeneratorArrayRecipeLogic extends RecipeLogic {

    private final GeneratorArrayMachine generator;
    private GTRecipe routedRecipe;
    private boolean routedWireless;

    public GeneratorArrayRecipeLogic(GeneratorArrayMachine generator) {
        super(generator);
        this.generator = generator;
    }

    @Override
    public GTRecipe.ActionResult handleTickRecipe(GTRecipe recipe) {
        if (recipe != routedRecipe || routedWireless != generator.isWirelessMode()) {
            // Legacy saves may refer to a registered recipe. Never edit that shared object.
            GTRecipe routed = RecipeExtensionCopier.copy(recipe, recipe.copy());
            routed.parallels = recipe.parallels;
            routed.ocTier = recipe.ocTier;
            generator.prepareRecipeOutput(routed);
            if (recipe == lastRecipe) {
                lastRecipe = routed;
            }
            routedRecipe = routed;
            routedWireless = generator.isWirelessMode();
            recipe = routed;
        }
        return super.handleTickRecipe(recipe);
    }
}
