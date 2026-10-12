package org.gtlcore.gtlcore.mixin.gtm.api.machine;

import org.gtlcore.gtlcore.api.machine.ISuspendableMachine;
import org.gtlcore.gtlcore.api.machine.computation.ComputationRecipes;
import org.gtlcore.gtlcore.api.machine.trait.ILockRecipe;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeStatus;
import org.gtlcore.gtlcore.api.recipe.BatchProcessing;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.ITieredMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.logic.OCParams;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;
import com.gregtechceu.gtceu.common.machine.multiblock.electric.research.ResearchStationMachine;

import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;

import net.minecraft.network.chat.Component;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;

import java.util.*;
import java.util.function.Predicate;

import static org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper.*;

@Mixin(RecipeLogic.class)
public abstract class RecipeLogicMixin implements ILockRecipe, IRecipeStatus {

    @Persisted
    @Getter
    private boolean isLock = false;
    @Persisted
    @Getter
    @Setter
    private GTRecipe lockRecipe;
    @Getter
    @Setter
    private RecipeResult recipeStatus;
    @Getter
    @Setter
    private RecipeResult workingStatus;

    @Shadow(remap = false)
    @Final
    public IRecipeLogicMachine machine;
    @Shadow(remap = false)
    @Final
    protected Map<RecipeCapability<?>, Object2IntMap<?>> chanceCaches;
    @Shadow(remap = false)
    public List<GTRecipe> lastFailedMatches;
    @Shadow(remap = false)
    protected @Nullable GTRecipe lastRecipe;
    @Shadow(remap = false)
    protected @Nullable GTRecipe lastOriginRecipe;
    @Shadow(remap = false)
    protected OCParams ocParams;
    @Shadow(remap = false)
    protected OCResult ocResult;
    @Shadow(remap = false)
    protected boolean recipeDirty;
    @Shadow(remap = false)
    protected int progress;
    @Shadow(remap = false)
    protected int duration;
    @Shadow(remap = false)
    private boolean isActive;
    @Shadow(remap = false)
    private RecipeLogic.Status status;
    @Shadow(remap = false)
    protected long totalContinuousRunningTime;

    @Shadow(remap = false)
    public abstract void markLastRecipeDirty();

    @Shadow(remap = false)
    public abstract void setStatus(RecipeLogic.Status status);

    @Shadow(remap = false)
    public abstract RecipeLogic.Status getStatus();

    @Shadow(remap = false)
    public abstract void updateTickSubscription();

    @Shadow(remap = false)
    public abstract GTRecipe.ActionResult handleTickRecipe(GTRecipe recipe);

    @Shadow(remap = false)
    public abstract void interruptRecipe();

    @Shadow(remap = false)
    public abstract void setWaiting(@Nullable Component reason);

    @Shadow(remap = false)
    protected abstract void doDamping();

    public void setLock(boolean look) {
        isLock = look;
        lockRecipe = null;
        updateTickSubscription();
    }

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    protected boolean handleRecipeIO(GTRecipe recipe, IO io) {
        if (!(this.machine.hasProxies() && io != IO.BOTH)) return false;
        if (io == IO.IN && !BatchProcessing.applyInPlace(this.machine.self(), this.lastOriginRecipe, recipe))
            return false;
        return handleRecipeInput(this.machine, recipe);
    }

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public void handleRecipeWorking() {
        assert this.lastRecipe != null;
        var result = this.handleTickRecipe(this.lastRecipe);
        if (result.isSuccess()) {
            this.setStatus(RecipeLogic.Status.WORKING);
            if (!this.machine.onWorking()) {
                this.interruptRecipe();
                return;
            }
            ++this.progress;
            ++this.totalContinuousRunningTime;
        } else {
            this.setWaiting(result.reason().get());
        }
        if (this.status == RecipeLogic.Status.WAITING) {
            this.doDamping();
        }
    }

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public void setupRecipe(GTRecipe recipe) {
        if (!this.machine.beforeWorking(recipe)) {
            this.setStatus(RecipeLogic.Status.IDLE);
            this.progress = 0;
            this.duration = 0;
            this.isActive = false;
            return;
        }
        if (this.handleRecipeIO(recipe, IO.IN)) {
            if (this.lastRecipe != null && !recipe.equals(this.lastRecipe)) {
                this.chanceCaches.clear();
            }
            this.recipeDirty = false;
            this.lastRecipe = recipe;
            this.setStatus(RecipeLogic.Status.WORKING);
            this.progress = 0;
            this.duration = recipe.duration;
            this.isActive = true;
        }
    }

    /**
     * @author Dragons
     * @reason 删除lastFailedMatches操作
     */
    @Overwrite(remap = false)
    private void handleSearchingRecipes(Iterator<GTRecipe> matches) {
        while (matches != null && matches.hasNext()) {
            var match = matches.next();
            if (match != null) {
                if (this.checkMatchedRecipeAvailable(match)) {
                    return;
                }
            }
        }
    }

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public void findAndHandleRecipe() {
        GTRecipe previousOriginRecipe = this.lastOriginRecipe;
        this.lastRecipe = null;
        this.recipeStatus = null;
        this.workingStatus = null;
        if (this.isLock && lockRecipe != null) {
            this.lastOriginRecipe = lockRecipe;
            var modified = machine.fullModifyRecipe(lastOriginRecipe.copy(), this.ocParams, this.ocResult);
            if (modified != null && this.gtlcore$checkLastRecipe(modified)) {
                setupRecipe(modified);
            }
        } else {
            this.lastOriginRecipe = null;
            if (!this.recipeDirty && previousOriginRecipe != null && this.gtlcore$trySetupLastOriginRecipe(previousOriginRecipe)) {
                this.recipeDirty = false;
                return;
            }
            this.handleSearchingRecipes(gtlcore$searchRecipe(this.machine, this.machine instanceof ResearchStationMachine ?
                    (r) -> {
                        if (!this.machine.hasProxies()) return false;
                        if (((IGTRecipe) r).getEuTier() > ((ITieredMachine) machine).getTier()) {
                            RecipeResult.of(machine, RecipeResult.FAIL_VOLTAGE_TIER);
                            return false;
                        }
                        var result = r.matchRecipeContents(IO.IN, this.machine, r.inputs, false);
                        if (!result.isSuccess()) {
                            RecipeResult.of(this.machine, RecipeResult.FAIL_FIND);
                            return false;
                        } else if (r.hasTick()) {
                            result = r.matchRecipeContents(IO.IN, this.machine, r.tickInputs, true);
                            if (!result.isSuccess() && result.reason() != null) {
                                var s = result.reason().get().toString();
                                if (s.contains("cwu")) RecipeResult.of(this.machine, RecipeResult.FAIL_NO_ENOUGH_CWU_IN);
                                else if (s.contains("eu.name")) RecipeResult.of(this.machine, RecipeResult.FAIL_NO_ENOUGH_EU_IN);
                            }
                            return result.isSuccess();
                        } else return true;
                    } : this::gtlcore$checkLastRecipe));
        }
        this.recipeDirty = false;
    }

    @Unique
    private boolean gtlcore$trySetupLastOriginRecipe(@NotNull GTRecipe originRecipe) {
        var modified = this.machine.fullModifyRecipe(originRecipe.copy(), this.ocParams, this.ocResult);
        if (modified != null && this.gtlcore$checkLastRecipe(modified)) {
            this.setupRecipe(modified);
            if (this.lastRecipe != null && this.getStatus() == RecipeLogic.Status.WORKING) {
                this.lastOriginRecipe = originRecipe;
                return true;
            }
        }
        return false;
    }

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public boolean checkMatchedRecipeAvailable(GTRecipe match) {
        var modified = this.machine.fullModifyRecipe(match.copy(), this.ocParams, this.ocResult);
        if (modified != null) {
            if (gtlcore$checkLastRecipe(modified)) {
                this.setupRecipe(modified);
            }
            if (this.lastRecipe != null && this.getStatus() == RecipeLogic.Status.WORKING) {
                this.lastOriginRecipe = match;
                if (this.isLock) this.lockRecipe = match;
                return true;
            }
        }
        return false;
    }

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public void onRecipeFinish() {
        if (this.lastRecipe != null && IGTRecipe.of(this.lastRecipe).getBatchSize() > 1 &&
                !matchRecipeOutput(this.machine, this.lastRecipe)) {
            this.setWaiting(null);
            return;
        }
        this.machine.afterWorking();
        if (this.lastRecipe != null) {
            handleRecipeOutput(this.machine, this.lastRecipe);
            if (this.machine instanceof ISuspendableMachine suspendableMachine && suspendableMachine.gtlcore$isSuspendAfterFinish()) {
                this.setStatus(RecipeLogic.Status.SUSPEND);
                suspendableMachine.gtlcore$setSuspendAfterFinish(false);
            } else {
                if (this.machine.alwaysTryModifyRecipe()) {
                    if (this.lastOriginRecipe != null) {
                        var modified = this.machine.fullModifyRecipe(this.lastOriginRecipe.copy(), this.ocParams, this.ocResult);
                        if (modified == null) {
                            this.markLastRecipeDirty();
                        } else {
                            this.lastRecipe = modified;
                        }
                    } else {
                        this.markLastRecipeDirty();
                    }
                }
                if (!this.recipeDirty && gtlcore$checkLastRecipe(this.lastRecipe, true)) {
                    this.setupRecipe(this.lastRecipe);
                    return;
                } else {
                    this.setStatus(RecipeLogic.Status.IDLE);
                }
            }
            this.progress = 0;
            this.duration = 0;
            this.isActive = false;
        }
    }

    @Unique
    public Iterator<GTRecipe> gtlcore$searchRecipe(IRecipeCapabilityHolder holder,
                                                   @NotNull Predicate<GTRecipe> canHandle) {
        if (!holder.hasProxies()) {
            return null;
        } else {
            var iterator = this.machine.getRecipeType().getLookup().getRecipeIterator(holder, canHandle);

            boolean any = false;
            GTRecipe recipe = null;
            while (iterator.hasNext()) {
                recipe = iterator.next();
                if (recipe != null) {
                    any = true;
                    break;
                }
            }

            if (any) {
                iterator.reset();
                return Collections.singleton(recipe).iterator();
            } else {
                for (var logic : this.machine.getRecipeType().getCustomRecipeLogicRunners()) {
                    recipe = logic.createCustomRecipe(holder);
                    if (recipe != null) return Collections.singleton(recipe).iterator();
                }
                if (recipeStatus == null || recipeStatus.isSuccess()) RecipeResult.of(this.machine, RecipeResult.FAIL_FIND);
                return Collections.emptyIterator();
            }
        }
    }

    @Unique
    private boolean gtlcore$checkLastRecipe(GTRecipe lastRecipe) {
        return gtlcore$checkLastRecipe(lastRecipe, false);
    }

    @Unique
    private boolean gtlcore$checkLastRecipe(GTRecipe lastRecipe, boolean restarting) {
        return matchRecipe(this.machine, lastRecipe) &&
                (restarting ? ComputationRecipes.matchRestartTick(this.machine, lastRecipe) :
                        lastRecipe.matchTickRecipe(this.machine)).isSuccess() &&
                lastRecipe.checkConditions(this.machine.getRecipeLogic()).isSuccess();
    }
}
