package org.gtlcore.gtlcore.mixin.gtm.api.recipe;

import org.gtlcore.gtlcore.api.recipe.BatchProcessing;
import org.gtlcore.gtlcore.api.recipe.IAdvancedContentModifier;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.api.recipe.IParallelLogic;

import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.modifier.ParallelLogic;

import com.google.common.primitives.Ints;
import com.mojang.datafixers.util.Pair;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.Objects;
import java.util.function.Predicate;

@Mixin(ParallelLogic.class)
public abstract class ParallelLogicMixin {

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public static int getMaxRecipeMultiplier(@NotNull GTRecipe recipe, @NotNull IRecipeCapabilityHolder holder, int parallelAmount) {
        int minimum = Integer.MAX_VALUE;
        minimum = Ints.saturatedCast(Math.min(minimum, IParallelLogic.getMaxParallel(holder, recipe, parallelAmount)));
        for (RecipeCapability<?> cap : recipe.tickInputs.keySet()) {
            if (cap.doMatchInRecipe()) {
                minimum = Math.min(minimum, cap.getMaxParallelRatio(holder, recipe, parallelAmount));
            }
        }
        return minimum;
    }

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public static int limitByOutputMerging(@NotNull GTRecipe recipe, @NotNull IRecipeCapabilityHolder holder, int parallelAmount, Predicate<RecipeCapability<?>> canVoid) {
        int max = parallelAmount;
        max = Ints.saturatedCast(Math.min(max, IParallelLogic.getMinParallel(holder, recipe, parallelAmount)));
        for (RecipeCapability<?> cap : recipe.tickOutputs.keySet()) {
            if (canVoid.test(cap) || !cap.doMatchInRecipe()) {
                continue;
            }
            if (!recipe.getTickOutputContents(cap).isEmpty()) {
                int limit = cap.limitParallel(recipe, holder, parallelAmount);
                if (limit == 0) {
                    return 0;
                }
                max = Math.min(max, limit);
            }
        }
        return max;
    }

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public static @NotNull Pair<GTRecipe, Integer> doParallelRecipes(@NotNull GTRecipe currentRecipe, @NotNull IRecipeLogicMachine machine, int parallelAmount, boolean modifyDuration) {
        int multiplierByInputs = getMaxRecipeMultiplier(currentRecipe, machine, parallelAmount);
        if (multiplierByInputs == 0) {
            return Pair.of(currentRecipe, 1);
        } else {
            Objects.requireNonNull(machine);
            int limitByOutput = limitByOutputMerging(currentRecipe, machine, multiplierByInputs, machine::canVoidRecipeOutputs);
            if (limitByOutput > 1) {
                GTRecipe multiRecipe = currentRecipe.copy(IAdvancedContentModifier.preciseMultiplier(limitByOutput), modifyDuration);
                ((IGTRecipe) multiRecipe).setRealParallels((long) limitByOutput * ((IGTRecipe) currentRecipe).getRealParallels());
                if (!BatchProcessing.isEnabled(machine.self())) {
                    multiRecipe = IParallelLogic.getRecipeOutputChance(machine, multiRecipe);
                }
                return Pair.of(multiRecipe, limitByOutput);
            } else {
                return Pair.of(currentRecipe, limitByOutput);
            }
        }
    }
}
