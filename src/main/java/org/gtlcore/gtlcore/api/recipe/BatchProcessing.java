package org.gtlcore.gtlcore.api.recipe;

import org.gtlcore.gtlcore.api.machine.computation.ComputationAmounts;
import org.gtlcore.gtlcore.api.machine.computation.ComputationRecipes;
import org.gtlcore.gtlcore.api.machine.multiblock.ParallelMachine;
import org.gtlcore.gtlcore.api.machine.trait.IBatchMachine;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.common.machine.trait.MultipleRecipesLogic;
import org.gtlcore.gtlcore.config.ConfigHolder;

import com.gregtechceu.gtceu.api.capability.recipe.CWURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;

import net.minecraft.world.item.crafting.Ingredient;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.gtlcore.gtlcore.api.recipe.BatchProcessingDecisionLogger.Eligibility.*;
import static org.gtlcore.gtlcore.api.recipe.BatchProcessingDecisionLogger.Outcome.BATCH_NOT_TRIGGERED;
import static org.gtlcore.gtlcore.api.recipe.BatchProcessingDecisionLogger.Outcome.BATCH_TRIGGERED;
import static org.gtlcore.gtlcore.api.recipe.BatchProcessingDecisionLogger.Reason.*;
import static org.gtlcore.gtlcore.api.recipe.IAdvancedContentModifier.preciseMultiplier;

public final class BatchProcessing {

    private static final ClassValue<MultipleRecipeModeAccess> MULTIPLE_RECIPE_MODE_ACCESS = new ClassValue<>() {

        @Override
        protected MultipleRecipeModeAccess computeValue(Class<?> type) {
            return new MultipleRecipeModeAccess(
                    findMethod(type, List.of("isMultipleMode", "getMultipleMode")),
                    findMethod(type, List.of("setMultipleMode", "setIsMultipleMode"), boolean.class),
                    findMethod(type, List.of("useModes")));
        }
    };

    private BatchProcessing() {}

    private static final Set<String> DISABLED_DEFINITION_IDS = Set.of(
            "gtceu:block_conversion_room",
            "gtceu:large_block_conversion_room",
            "gtceu:slaughterhouse",
            "gtceu:space_elevator",
            "gtceu:weather_control",
            "gtceu:door_of_create",
            "gtceu:create_aggregation");

    public static boolean isBatchDisabledDefinition(MetaMachine machine) {
        var definition = machine.getDefinition();
        return definition != null && DISABLED_DEFINITION_IDS.contains(definition.getId().toString());
    }

    public static boolean isEnabled(MetaMachine machine) {
        return machine instanceof IBatchMachine batchMachine && batchMachine.supportsBatchProcessing() &&
                batchMachine.isBatchEnabled();
    }

    public static boolean isCrossRecipeParallel(IRecipeLogicMachine machine) {
        if (!(machine.getRecipeLogic() instanceof MultipleRecipesLogic)) return false;

        var modeGetter = MULTIPLE_RECIPE_MODE_ACCESS.get(machine.getClass()).modeGetter();
        return modeGetter.isEmpty() || invokeBoolean(modeGetter.get(), machine, true);
    }

    public static boolean canConfigureBatchProcessing(IRecipeLogicMachine machine) {
        if (!(machine.getRecipeLogic() instanceof MultipleRecipesLogic)) return true;

        var access = MULTIPLE_RECIPE_MODE_ACCESS.get(machine.getClass());
        if (access.modeGetter().isEmpty() || access.modeSetter().isEmpty()) return false;
        return access.useModesGetter().isEmpty() || invokeBoolean(access.useModesGetter().get(), machine, true);
    }

    public static GTRecipe apply(MetaMachine machine, GTRecipe recipe, boolean isSubTickParallelized) {
        boolean subTickEligible = isSubTickParallelized || IGTRecipe.of(recipe).isSubTickParallelized();
        int timeLimit = Math.max(1, ConfigHolder.INSTANCE.machines.batchProcessingTimeLimitTicks);
        boolean timeWindowEligible = recipe.duration > 0 && recipe.duration <= timeLimit / 2;
        BatchProcessingDecisionLogger.Eligibility eligibility = subTickEligible ?
                timeWindowEligible ? SUB_TICK_AND_TIME_WINDOW : SUB_TICK :
                timeWindowEligible ? TIME_WINDOW : NONE;
        if (!isEnabled(machine)) {
            BatchProcessingDecisionLogger.log(machine, recipe, BATCH_NOT_TRIGGERED, UNSUPPORTED_MACHINE_MODE,
                    eligibility, timeLimit, 0, 0, 1);
            return recipe;
        }
        if (IGTRecipe.of(recipe).isBatchProcessed()) {
            return recipe;
        }
        if (!(machine instanceof IRecipeLogicMachine recipeMachine)) {
            BatchProcessingDecisionLogger.log(machine, recipe, BATCH_NOT_TRIGGERED, NOT_RECIPE_LOGIC_MACHINE,
                    eligibility, timeLimit, 0, 0, 1);
            return recipe;
        }
        if (eligibility == NONE) {
            int timeLimitedCycles = recipe.duration > 0 ? timeLimit / recipe.duration : 0;
            BatchProcessingDecisionLogger.log(machine, recipe, BATCH_NOT_TRIGGERED, NO_ELIGIBLE_PATH,
                    NONE, timeLimit, timeLimitedCycles, timeLimitedCycles, 1);
            return recipe;
        }

        BatchSizeDecision decision = getBatchSize(recipeMachine, recipe, timeLimit);
        int batchSize = decision.batchSize();
        BatchProcessingDecisionLogger.log(machine, recipe,
                batchSize > 1 ? BATCH_TRIGGERED : BATCH_NOT_TRIGGERED, decision.reason(), eligibility,
                timeLimit, decision.timeLimitedCycles(), decision.amountLimitedCycles(), batchSize);
        if (batchSize == 0) return null;
        GTRecipe result = batchSize > 1 ? scaleRecipe(recipe, batchSize) : recipe;
        result = IParallelLogic.getRecipeOutputChance(recipeMachine, result);
        IGTRecipe.of(result).setBatchSize(batchSize);
        IGTRecipe.of(result).setBatchProcessed(true);
        return result;
    }

    /**
     * Compatibility overload for addons compiled against the original batch-processing API.
     */
    public static GTRecipe apply(MetaMachine machine, GTRecipe recipe) {
        return apply(machine, recipe, isCustomSubTickParallelized(machine, recipe));
    }

    public static boolean applyInPlace(MetaMachine machine, GTRecipe recipe) {
        return applyInPlace(machine, null, recipe);
    }

    public static boolean applyInPlace(MetaMachine machine, GTRecipe originalRecipe, GTRecipe recipe) {
        boolean isSubTickParallelized = isCustomSubTickParallelized(machine, recipe) ||
                originalRecipe != null && isCustomSubTickParallelized(machine, originalRecipe, recipe);
        GTRecipe result = apply(machine, recipe, isSubTickParallelized);
        if (result == null) return false;
        if (result == recipe) return true;

        var inputs = new HashMap<>(result.inputs);
        var outputs = new HashMap<>(result.outputs);
        var tickInputs = new HashMap<>(result.tickInputs);
        var tickOutputs = new HashMap<>(result.tickOutputs);
        recipe.inputs.clear();
        recipe.inputs.putAll(inputs);
        recipe.outputs.clear();
        recipe.outputs.putAll(outputs);
        recipe.tickInputs.clear();
        recipe.tickInputs.putAll(tickInputs);
        recipe.tickOutputs.clear();
        recipe.tickOutputs.putAll(tickOutputs);
        recipe.duration = result.duration;
        recipe.ocTier = result.ocTier;
        recipe.data = result.data.copy();
        RecipeExtensionCopier.copy(result, recipe);
        return true;
    }

    public static boolean isCustomSubTickParallelized(MetaMachine machine, GTRecipe recipe) {
        if (recipe == null) {
            return false;
        }
        if (IGTRecipe.of(recipe).isSubTickParallelized()) return true;
        if (!(machine instanceof ParallelMachine parallelMachine)) return false;

        return IGTRecipe.of(recipe).getRealParallels() > Math.max(1L, parallelMachine.getMaxParallel());
    }

    public static boolean isCustomSubTickParallelized(MetaMachine machine, GTRecipe originalRecipe,
                                                      GTRecipe modifiedRecipe) {
        if (!(machine instanceof IRecipeLogicMachine recipeMachine) || modifiedRecipe == null ||
                modifiedRecipe == originalRecipe) {
            return false;
        }

        long regularParallelLimit = IParallelLogic.getMaxParallel(recipeMachine, originalRecipe, Long.MAX_VALUE);
        if (machine instanceof ParallelMachine parallelMachine) {
            regularParallelLimit = Math.min(regularParallelLimit, parallelMachine.getMaxParallel());
        }
        if (machine instanceof WorkableElectricMultiblockMachine electricMachine) {
            long recipeEUt = RecipeHelper.getInputEUt(originalRecipe);
            if (recipeEUt > 0) {
                regularParallelLimit = Math.min(regularParallelLimit,
                        electricMachine.getOverclockVoltage() / recipeEUt);
            }
        }

        return IGTRecipe.of(modifiedRecipe).getRealParallels() > Math.max(1, regularParallelLimit);
    }

    public static boolean canOverclockBelowOneTick(OCResult result) {
        return ((IAdvancedOCResult) (Object) result).isSubTickOverclockAvailable();
    }

    private static BatchSizeDecision getBatchSize(IRecipeLogicMachine machine, GTRecipe recipe, int timeLimit) {
        if (recipe.duration <= 0) return new BatchSizeDecision(1, NON_POSITIVE_DURATION, 1, 1);

        int maxCycles = timeLimit / recipe.duration;
        long realParallels = Math.max(1, IGTRecipe.of(recipe).getRealParallels());
        maxCycles = (int) Math.min(maxCycles, Long.MAX_VALUE / realParallels);
        if (maxCycles <= 0) {
            // A short batch window must fall back to one normal cycle; rejecting the recipe
            // here would stop a machine solely because batching is enabled.
            return new BatchSizeDecision(1, TIME_LIMIT_FALLBACK_SINGLE_CYCLE, maxCycles, 0);
        }
        if (maxCycles == 1) return new BatchSizeDecision(1, TIME_LIMIT_SINGLE_CYCLE, maxCycles, maxCycles);

        int amountLimitedCycles = limitByLongAmounts(recipe, maxCycles);
        if (amountLimitedCycles <= 0)
            return new BatchSizeDecision(0, AMOUNT_OVERFLOW, maxCycles, amountLimitedCycles);
        if (amountLimitedCycles == 1)
            return new BatchSizeDecision(1, AMOUNT_LIMIT_SINGLE_CYCLE, maxCycles, amountLimitedCycles);

        int batchSize = getParallelAmountWithoutEU(machine, recipe, amountLimitedCycles);
        return new BatchSizeDecision(batchSize,
                batchSize <= 0 ? CAPACITY_REJECTED :
                        batchSize == 1 ? CAPACITY_SINGLE_CYCLE : MULTIPLE_CYCLES_AVAILABLE,
                maxCycles, amountLimitedCycles);
    }

    private static int limitByLongAmounts(GTRecipe recipe, int limit) {
        if (recipe.data.getBoolean("duration_is_total_cwu"))
            limit = limitByMaximumAmount(limit, ComputationAmounts.total(recipe));
        limit = limitByMaximumAmount(limit, ComputationRecipes.required(recipe.getInputContents(CWURecipeCapability.CAP)));
        limit = limitByMaximumAmount(limit, ComputationRecipes.required(recipe.getOutputContents(CWURecipeCapability.CAP)));
        limit = limitByMaximumAmount(limit, getMaximumItemAmount(recipe.getInputContents(ItemRecipeCapability.CAP)));
        limit = limitByMaximumAmount(limit, getMaximumFluidAmount(recipe.getInputContents(FluidRecipeCapability.CAP)));
        limit = limitByMaximumAmount(limit, getMaximumItemAmount(recipe.getOutputContents(ItemRecipeCapability.CAP)));
        return limitByMaximumAmount(limit, getMaximumFluidAmount(recipe.getOutputContents(FluidRecipeCapability.CAP)));
    }

    private static int limitByMaximumAmount(int limit, long maximumAmount) {
        if (maximumAmount < 0) return 0;
        if (maximumAmount == 0) return limit;
        return (int) Math.min(limit, Long.MAX_VALUE / maximumAmount);
    }

    private static long getMaximumItemAmount(List<Content> contents) {
        long maximum = 0;
        for (Content content : contents) {
            Ingredient ingredient = ItemRecipeCapability.CAP.of(content.content);
            long amount = ingredient instanceof LongIngredient longIngredient ?
                    longIngredient.getActualAmount() :
                    ingredient instanceof SizedIngredient sizedIngredient ? sizedIngredient.getAmount() : 1;
            if (amount < 0) return -1;
            maximum = Math.max(maximum, amount);
        }
        return maximum;
    }

    private static long getMaximumFluidAmount(List<Content> contents) {
        long maximum = 0;
        for (Content content : contents) {
            FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.content);
            long amount = ingredient.getAmount();
            if (amount < 0) return -1;
            maximum = Math.max(maximum, amount);
        }
        return maximum;
    }

    private static int getParallelAmountWithoutEU(IRecipeLogicMachine machine, GTRecipe recipe, int limit) {
        long parallel = IParallelLogic.getParallel(machine, recipe, limit);
        if (parallel == 0) return 0;

        for (RecipeCapability<?> capability : recipe.inputs.keySet()) {
            if (capability.doMatchInRecipe() && capability != ItemRecipeCapability.CAP &&
                    capability != FluidRecipeCapability.CAP) {
                parallel = Math.min(parallel, capability.getMaxParallelRatio(machine, recipe, (int) parallel));
            }
        }
        for (RecipeCapability<?> capability : recipe.outputs.keySet()) {
            if (capability.doMatchInRecipe() && capability != ItemRecipeCapability.CAP &&
                    capability != FluidRecipeCapability.CAP && !machine.canVoidRecipeOutputs(capability)) {
                parallel = Math.min(parallel, capability.limitParallel(recipe, machine, (int) parallel));
            }
        }
        return (int) parallel;
    }

    private static GTRecipe scaleRecipe(GTRecipe recipe, int batchSize) {
        // Sequential cycles scale ordinary IO and duration, never the per-tick rates. Scaling
        // everything and restoring tick IO afterwards can overflow a valid long CWU/t first.
        GTRecipe batched = recipe.copy();
        var modifier = preciseMultiplier(batchSize);
        batched.inputs.clear();
        batched.inputs.putAll(recipe.copyContents(recipe.inputs, modifier));
        batched.outputs.clear();
        batched.outputs.putAll(recipe.copyContents(recipe.outputs, modifier));
        RecipeExtensionCopier.copy(recipe, batched);

        batched.tickInputs.clear();
        batched.tickInputs.putAll(recipe.tickInputs);
        batched.tickOutputs.clear();
        batched.tickOutputs.putAll(recipe.tickOutputs);
        batched.duration = recipe.duration * batchSize;
        if (recipe.data.getBoolean("duration_is_total_cwu")) {
            long total = Math.multiplyExact(ComputationAmounts.total(recipe), batchSize);
            batched.data = recipe.data.copy();
            batched.data.putLong(ComputationAmounts.TOTAL_CWU, total);
            batched.duration = ComputationAmounts.duration(total);
        }
        batched.ocTier = recipe.ocTier;
        IGTRecipe.of(batched).setRealParallels(IGTRecipe.of(recipe).getRealParallels() * batchSize);
        return batched;
    }

    private static Optional<Method> findMethod(Class<?> type, List<String> names, Class<?>... parameterTypes) {
        for (String name : names) {
            try {
                return Optional.of(type.getMethod(name, parameterTypes));
            } catch (NoSuchMethodException ignored) {
                // Optional compatibility method is not present on this machine type.
            }
        }
        return Optional.empty();
    }

    private static boolean invokeBoolean(Method method, Object target, boolean fallback) {
        try {
            Object result = method.invoke(target);
            return result instanceof Boolean value ? value : fallback;
        } catch (IllegalAccessException | InvocationTargetException exception) {
            return fallback;
        }
    }

    private record MultipleRecipeModeAccess(Optional<Method> modeGetter, Optional<Method> modeSetter,
                                            Optional<Method> useModesGetter) {}

    private record BatchSizeDecision(int batchSize, BatchProcessingDecisionLogger.Reason reason,
                                     int timeLimitedCycles, int amountLimitedCycles) {}
}
