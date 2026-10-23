package org.gtlcore.gtlcore.common.machine.trait;

import org.gtlcore.gtlcore.api.machine.ISuspendableMachine;
import org.gtlcore.gtlcore.api.machine.multiblock.ParallelMachine;
import org.gtlcore.gtlcore.api.machine.trait.ILockRecipe;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeStatus;
import org.gtlcore.gtlcore.api.recipe.IAdvancedContentModifier;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;

import com.gregtechceu.gtceu.api.capability.recipe.*;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder;

import net.minecraft.nbt.CompoundTag;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.Getter;

import java.util.*;
import java.util.function.BiPredicate;

import static org.gtlcore.gtlcore.api.recipe.IParallelLogic.*;
import static org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper.*;

@Getter
public class MultipleRecipesLogic extends RecipeLogic implements ILockRecipe, IRecipeStatus {

    private final ParallelMachine parallel;

    private final BiPredicate<CompoundTag, IRecipeLogicMachine> dataCheck;

    private static final int MAX_THREADS = 64;

    private double reductionEUt;

    private double reductionDuration;

    public MultipleRecipesLogic(ParallelMachine machine) {
        this(machine, null);
    }

    public MultipleRecipesLogic(ParallelMachine machine, BiPredicate<CompoundTag, IRecipeLogicMachine> dataCheck) {
        this(machine, dataCheck, 1.0, 1.0);
    }

    public MultipleRecipesLogic(ParallelMachine machine, BiPredicate<CompoundTag, IRecipeLogicMachine> dataCheck, double reductionEUt, double reductionDuration) {
        super((IRecipeLogicMachine) machine);
        this.parallel = machine;
        this.dataCheck = dataCheck;
        this.reductionEUt = reductionEUt;
        this.reductionDuration = reductionDuration;
    }

    public void setReduction(double reductionEUt, double reductionDuration) {
        this.reductionEUt = reductionEUt;
        this.reductionDuration = reductionDuration;
    }

    @Override
    public WorkableElectricMultiblockMachine getMachine() {
        return (WorkableElectricMultiblockMachine) super.getMachine();
    }

    @Override
    public void findAndHandleRecipe() {
        lastRecipe = null;
        setRecipeStatus(null);
        var match = getRecipe();
        if (match != null) {
            RecipeResult.of(machine, RecipeResult.SUCCESS);
            if (matchRecipeOutput(machine, match)) {
                setupRecipe(match);
            }
        }
    }

    protected double getTotalEuOfRecipe(GTRecipe recipe) {
        return ((double) RecipeHelper.getInputEUt(recipe)) * recipe.duration;
    }

    protected double getEuMultiplier() {
        var maintenanceMachine = ((IRecipeCapabilityMachine) parallel).getMaintenanceMachine();
        return maintenanceMachine != null ?
                maintenanceMachine.getDurationMultiplier() * this.reductionEUt * this.reductionDuration :
                this.reductionEUt * this.reductionDuration;
    }

    private GTRecipe getRecipe() {
        if (!machine.hasProxies()) return null;
        long maxEUt = getMachine().getOverclockVoltage();
        if (maxEUt <= 0) return null;
        var iterator = lookupRecipeIterator();
        GTRecipe output = GTRecipeBuilder.ofRaw().buildRawRecipe();
        output.outputs.put(ItemRecipeCapability.CAP, new ObjectArrayList<>());
        output.outputs.put(FluidRecipeCapability.CAP, new ObjectArrayList<>());
        double totalEu = 0;
        long remain = (long) this.parallel.getMaxParallel() * MAX_THREADS;
        double euMultiplier = getEuMultiplier();

        while (remain > 0 && iterator.hasNext()) {
            var match = iterator.next();
            if (match == null) continue;
            var p = getMaxParallel(machine, match, remain);
            if (p <= 0) continue;
            else if (p > 1) match = match.copy(IAdvancedContentModifier.preciseMultiplier(p), false);
            ((IGTRecipe) match).setRealParallels(p);
            match = getRecipeOutputChance(machine, match);
            if (handleRecipeInput(machine, match)) {
                remain -= p;
                totalEu += getTotalEuOfRecipe(match) * euMultiplier;
                var item = match.outputs.get(ItemRecipeCapability.CAP);
                if (item != null) output.outputs.get(ItemRecipeCapability.CAP).addAll(item);
                var fluid = match.outputs.get(FluidRecipeCapability.CAP);
                if (fluid != null) output.outputs.get(FluidRecipeCapability.CAP).addAll(fluid);
            }
            if (totalEu / maxEUt > 20 * 500) break;
        }
        if (output.outputs.get(ItemRecipeCapability.CAP).isEmpty() &&
                output.outputs.get(FluidRecipeCapability.CAP).isEmpty()) {
            if (getRecipeStatus() == null || getRecipeStatus().isSuccess()) RecipeResult.of(this.machine, RecipeResult.FAIL_FIND);
            return null;
        }
        var d = totalEu / maxEUt;
        long eut = d > 20 ? maxEUt : (long) (maxEUt * d / 20);
        output.tickInputs.put(EURecipeCapability.CAP,
                List.of(new Content(eut, 10000, 10000, 0, null, null)));
        output.duration = (int) Math.max(d, 20);
        IGTRecipe.of(output).setHasTick(true);
        return output;
    }

    private Iterator<GTRecipe> lookupRecipeIterator() {
        if (this.isLock()) {
            if (this.getLockRecipe() == null) {
                this.setLockRecipe(machine.getRecipeType().getLookup()
                        .find(machine, this::checkRecipe));
            } else if (!checkRecipe(this.getLockRecipe())) return Collections.emptyIterator();
            return Collections.singleton(this.getLockRecipe()).iterator();
        } else return machine.getRecipeType().getLookup().getRecipeIterator(machine, this::checkRecipe);
    }

    private boolean checkRecipe(GTRecipe recipe) {
        return matchRecipe(machine, recipe) &&
                IGTRecipe.of(recipe).getEuTier() <= getMachine().getTier() &&
                recipe.checkConditions(this).isSuccess() &&
                (dataCheck == null || dataCheck.test(recipe.data, machine));
    }

    @Override
    public void onRecipeFinish() {
        machine.afterWorking();
        if (lastRecipe != null) {
            handleRecipeOutput(this.machine, lastRecipe);
        }
        if (this.machine instanceof ISuspendableMachine suspendableMachine && suspendableMachine.gtlcore$isSuspendAfterFinish()) {
            this.setStatus(RecipeLogic.Status.SUSPEND);
            suspendableMachine.gtlcore$setSuspendAfterFinish(false);
        } else {
            var match = getRecipe();
            if (match != null) {
                if (matchRecipeOutput(machine, match)) {
                    setupRecipe(match);
                    return;
                }
            }
            setStatus(Status.IDLE);
        }
        progress = 0;
        duration = 0;
    }
}
