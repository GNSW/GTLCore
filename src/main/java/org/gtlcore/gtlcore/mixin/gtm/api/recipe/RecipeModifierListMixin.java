package org.gtlcore.gtlcore.mixin.gtm.api.recipe;

import org.gtlcore.gtlcore.api.machine.computation.ComputationAmounts;
import org.gtlcore.gtlcore.api.recipe.BatchProcessing;
import org.gtlcore.gtlcore.api.recipe.IAdvancedOCResult;
import org.gtlcore.gtlcore.api.recipe.RecipeMultiplierTracker;
import org.gtlcore.gtlcore.utils.NumberUtils;

import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.chance.logic.ChanceLogic;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.logic.OCParams;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;
import com.gregtechceu.gtceu.api.recipe.modifier.ParallelLogic;
import com.gregtechceu.gtceu.api.recipe.modifier.RecipeModifier;
import com.gregtechceu.gtceu.api.recipe.modifier.RecipeModifierList;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

import javax.annotation.Nullable;

@Mixin(RecipeModifierList.class)
public abstract class RecipeModifierListMixin {

    @Shadow(remap = false)
    @Final
    private RecipeModifier[] modifiers;

    /**
     * @author Dragonator
     * @reason fix OCTier And EU
     */
    @SuppressWarnings("DataFlowIssue")
    @Overwrite(remap = false)
    @Nullable
    public GTRecipe apply(MetaMachine machine, @NotNull GTRecipe recipe, @NotNull OCParams params, @NotNull OCResult result) {
        GTRecipe modifiedRecipe = recipe;
        boolean isSubTickParallelized = false;
        RecipeMultiplierTracker.begin(machine, recipe);

        for (RecipeModifier modifier : this.modifiers) {
            if (modifiedRecipe != null) {
                GTRecipe beforeModifier = modifiedRecipe;
                int resultDurationBefore = result.getDuration();
                modifiedRecipe = modifier.apply(machine, modifiedRecipe, params, result);
                if (resultDurationBefore == 0 && result.getDuration() != 0) {
                    RecipeMultiplierTracker.captureBeforeOverclock(machine, beforeModifier);
                }
            }
        }

        RecipeMultiplierTracker.finish(machine, modifiedRecipe);

        if (modifiedRecipe != null && result.getDuration() != 0) {
            ComputationAmounts.applyOverclock(modifiedRecipe, result);
            modifiedRecipe.duration = result.getDuration();

            if (result.getEut() > 0L) {
                modifiedRecipe.tickInputs.put(EURecipeCapability.CAP, List.of(new Content(result.getEut(), ChanceLogic.getMaxChancedValue(), ChanceLogic.getMaxChancedValue(), 0, null, null)));
            } else if (result.getEut() < 0L) {
                modifiedRecipe.tickOutputs.put(EURecipeCapability.CAP, List.of(new Content(-result.getEut(), ChanceLogic.getMaxChancedValue(), ChanceLogic.getMaxChancedValue(), 0, null, null)));
            }

            final int tryParallelMultiply = result.getParallel();
            final IAdvancedOCResult advancedOCResult = (IAdvancedOCResult) (Object) result;

            if (tryParallelMultiply > 1) {
                var modifyResult = ParallelLogic.applyParallel(machine, modifiedRecipe, tryParallelMultiply, false);

                modifiedRecipe = modifyResult.getFirst();
                modifiedRecipe.ocTier = advancedOCResult.getBaseOCLevel(); // if none of subTick Modifier, baseOCLevel
                                                                           // == ocLevel, else return real base Level
                final int actualAppliedMultiply = modifyResult.getSecond();

                if ((actualAppliedMultiply > 1)) {
                    final double durationFactor = advancedOCResult.getDurationFactor();
                    final double voltageFactor = advancedOCResult.getVoltageFactor();

                    // last was subTick Modifier
                    if (durationFactor != 0 && voltageFactor != 0) {
                        isSubTickParallelized = true;
                        long actualEUt;
                        if (actualAppliedMultiply == tryParallelMultiply) {
                            actualEUt = result.getParallelEUt();
                            modifiedRecipe.ocTier = result.getOcLevel();
                        } else {
                            int additionalTier = NumberUtils.getAdditionalTier(durationFactor, actualAppliedMultiply);
                            actualEUt = Math.round((Math.pow(voltageFactor, additionalTier) * result.getEut() * actualAppliedMultiply / tryParallelMultiply));
                            modifiedRecipe.ocTier += additionalTier;
                        }

                        if (result.getEut() > 0L) {
                            modifiedRecipe.tickInputs.put(EURecipeCapability.CAP, List.of(new Content(actualEUt, ChanceLogic.getMaxChancedValue(), ChanceLogic.getMaxChancedValue(), 0, null, null)));
                        } else if (result.getEut() < 0L) {
                            modifiedRecipe.tickOutputs.put(EURecipeCapability.CAP, List.of(new Content(-actualEUt, ChanceLogic.getMaxChancedValue(), ChanceLogic.getMaxChancedValue(), 0, null, null)));
                        }
                    }
                }
            } else {
                modifiedRecipe.ocTier = advancedOCResult.getBaseOCLevel();
            }
        }

        isSubTickParallelized |= BatchProcessing.canOverclockBelowOneTick(result) ||
                BatchProcessing.isCustomSubTickParallelized(machine, recipe, modifiedRecipe);
        result.reset();
        return modifiedRecipe == null ? null : BatchProcessing.apply(machine, modifiedRecipe, isSubTickParallelized);
    }
}
