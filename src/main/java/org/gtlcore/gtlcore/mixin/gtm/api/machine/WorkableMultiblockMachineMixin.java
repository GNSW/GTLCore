package org.gtlcore.gtlcore.mixin.gtm.api.machine;

import org.gtlcore.gtlcore.api.machine.computation.ComputationNetwork;
import org.gtlcore.gtlcore.api.machine.trait.*;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEFilterIOPartMachine;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEPatternPartMachine;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.gtlcore.gtlcore.mixin.gtm.cover.MachineControllerCoverAccessorMixin;
import org.gtlcore.gtlcore.utils.datastructure.FirstFlagRecipePartSet;

import com.gregtechceu.gtceu.api.block.ActiveBlock;
import com.gregtechceu.gtceu.api.capability.IDataAccessHatch;
import com.gregtechceu.gtceu.api.capability.IParallelHatch;
import com.gregtechceu.gtceu.api.capability.recipe.*;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.*;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.logic.OCParams;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;
import com.gregtechceu.gtceu.common.cover.MachineControllerCover;
import com.gregtechceu.gtceu.common.machine.multiblock.part.FluidHatchPartMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.MEOutputBusPartMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.MEOutputHatchPartMachine;

import com.lowdragmc.lowdraglib.syncdata.ISubscription;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import com.hepdd.gtmthings.common.block.machine.multiblock.part.appeng.MEOutputPartMachine;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.*;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;

@SuppressWarnings({ "AddedMixinMembersNamePattern", "MissingUnique" })
@Mixin(WorkableMultiblockMachine.class)
public abstract class WorkableMultiblockMachineMixin extends MultiblockControllerMachine implements IRecipeCapabilityMachine, IRecipeLogicMachine {

    @Shadow(remap = false)
    @Final
    protected List<ISubscription> traitSubscriptions;
    @Shadow(remap = false)
    @Final
    public RecipeLogic recipeLogic;
    @Shadow(remap = false)
    protected @Nullable LongSet activeBlocks;

    public WorkableMultiblockMachineMixin(IMachineBlockEntity holder) {
        super(holder);
    }

    @Inject(method = "onStructureFormed", at = @At("TAIL"), remap = false)
    public void onStructureFormed(CallbackInfo ci) {
        if (this.getLevel() instanceof ServerLevel sl) {
            // Invalidating the structure resets SUSPEND to IDLE. Reapply every control
            // cover synchronously, without waiting for another redstone neighbor update.
            for (var cover : getCoverContainer().getCovers()) {
                if (cover instanceof MachineControllerCover) {
                    ((MachineControllerCoverAccessorMixin) cover).gtlcore$updateInput();
                }
            }
            sl.getServer().execute(this::upDate);
        }
    }

    @Inject(method = "onStructureInvalid", at = @At("TAIL"), remap = false)
    public void onStructureInvalid(CallbackInfo ci) {
        clear();
        RecipeResult.of(this, null);
        RecipeResult.ofWorking(this, null);
    }

    @Inject(method = "onPartUnload", at = @At("TAIL"), remap = false)
    public void onPartUnload(CallbackInfo ci) {
        clear();
    }

    @Inject(method = "setActiveRecipeType", at = @At("TAIL"), remap = false)
    public void afterSetActiveRecipeType(int activeRecipeType, CallbackInfo ci) {
        if (!isRemote()) recipeLogic.updateTickSubscription();
    }

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public final @Nullable GTRecipe doModifyRecipe(GTRecipe recipe, @NotNull OCParams params, @NotNull OCResult result) {
        if (maintenanceMachine != null) recipe = maintenanceMachine.modifyRecipe(recipe);
        if (recipe != null && mufflerMachine != null) recipe = mufflerMachine.modifyRecipe(recipe);
        if (recipe != null && dataAccessHatch != null) recipe = dataAccessHatch.modifyRecipe(recipe);
        return recipe != null ? this.self().getDefinition().getRecipeModifier().apply(this.self(), recipe, params, result) : null;
    }

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public boolean onWorking() {
        return this.self().getDefinition().getOnWorking().test(this);
    }

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public void onWaiting() {}

    /**
     * The computation coordinator wakes registered consumers when supply returns. Keep the old retry for
     * third-party recipe paths that have not registered a computation demand.
     *
     * @author GTLCore
     * @reason Retry recipes after remote optical computation becomes available again.
     */
    @Overwrite(remap = false)
    public boolean keepSubscribing() {
        return recipeLogic instanceof IRecipeStatus status &&
                RecipeResult.FAIL_NO_ENOUGH_CWU_IN.equals(status.getRecipeStatus()) && !ComputationNetwork.isWaiting(this);
    }

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public boolean beforeWorking(@Nullable GTRecipe recipe) {
        return this.self().getDefinition().getBeforeWorking().test(this, recipe);
    }

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public void afterWorking() {
        if (maintenanceMachine != null) maintenanceMachine.afterWorking((IWorkableMultiController) this);
        if (mufflerMachine != null) mufflerMachine.afterWorking((IWorkableMultiController) this);
        this.self().getDefinition().getAfterWorking().accept(this);
    }

    /**
     * @author screret
     * @reason Performance
     */
    @Overwrite(remap = false)
    public void updateActiveBlocks(boolean active) {
        if (activeBlocks != null) {
            for (long pos : activeBlocks) {
                var blockPos = BlockPos.of(pos);
                var blockState = getLevel().getBlockState(blockPos);
                if (blockState.getBlock() instanceof ActiveBlock block) {
                    var newState = block.changeActive(blockState, active);
                    if (newState != blockState) {
                        getLevel().setBlock(blockPos, newState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                }
            }
        }
    }

    // ========================================
    // IRecipeCapabilityMachine
    // ========================================

    @Persisted
    @DescSynced
    @Getter
    @Setter
    private boolean isDistinct = false;
    private boolean meOutPutBus = false;
    private boolean meOutPutHatch = false;
    private boolean meOutPutDual = false;
    private boolean meOutPutWithFilter = false;
    private boolean meItemOutPutWithFilter = false;
    private boolean meFluidOutPutWithFilter = false;
    private boolean itemOutPutAlwaysMatch = false;
    private boolean fluidOutPutAlwaysMatch = false;

    // ==================== Special Hatch ====================
    private @Nullable IParallelHatch parallelHatch = null;
    private @Nullable IMaintenanceMachine maintenanceMachine = null;
    private @Nullable IMufflerMachine mufflerMachine = null;
    private @Nullable IDataAccessHatch dataAccessHatch = null;

    // ==================== Normal Part ====================
    private @Nullable RecipeHandlePart sharedInputRecipeHandlePart = null;
    private final Map<IO, List<RecipeHandlePart>> normalCapabilities = new EnumMap<>(IO.class); // Only Distinct Part

    // ==================== ME Part ====================
    private final ObjectList<MEPatternRecipeHandlePart> mePatternRecipeHandleParts = new ObjectArrayList<>();
    private final ObjectList<MEIORecipeHandlePart<?>> meOutputRecipeHandleParts = new ObjectArrayList<>();

    // ==================== Recipe -> Parts ====================
    private final Object2ObjectMap<GTRecipe, FirstFlagRecipePartSet> recipeHandleMap = new Object2ObjectOpenHashMap<>();

    @Override
    public boolean isRecipeOutputAlwaysMatch(GTRecipe recipe) {
        if (meOutPutWithFilter) return false;
        else if (meOutPutDual) return true;
        else if (meOutPutBus || recipe.getOutputContents(ItemRecipeCapability.CAP).isEmpty())
            return meOutPutHatch || recipe.getOutputContents(FluidRecipeCapability.CAP).isEmpty();
        else return false;
    }

    @Override
    public boolean itemOutPutAlwaysMatch() {
        return itemOutPutAlwaysMatch;
    }

    @Override
    public boolean fluidOutPutAlwaysMatch() {
        return fluidOutPutAlwaysMatch;
    }

    // ========================================
    // Recipe -> HandlePart Cache
    // ========================================

    @Override
    public @Nullable IRecipeHandlePart getActiveRecipeHandle(GTRecipe recipe) {
        final var handlers = recipeHandleMap.get(recipe);
        return handlers != null ? handlers.getActive() : null;
    }

    @Override
    public @NotNull Iterator<@NotNull IRecipeHandlePart> getAllCachedRecipeHandlesIter(GTRecipe recipe) {
        final var handlers = recipeHandleMap.get(recipe);
        return handlers != null ? handlers.getReverseIterator() : ObjectIterators.emptyIterator();
    }

    @Override
    public @NotNull ReferenceSet<@NotNull IRecipeHandlePart> getAllCachedRecipeHandles(GTRecipe recipe) {
        final var handlers = recipeHandleMap.get(recipe);
        return handlers != null ? handlers.getAll() : ReferenceSets.emptySet();
    }

    @Override
    public void tryAddAndActiveMERhp(MEPatternRecipeHandlePart part, GTRecipe recipe, int slot) {
        part.setLastRecipe2Slot(recipe, slot);
        tryAddAndActiveRhp(recipe, part);
    }

    @Override
    public void tryAddAndActiveRhp(GTRecipe recipe, IRecipeHandlePart part) {
        recipeHandleMap.computeIfAbsent(recipe, k -> new FirstFlagRecipePartSet()).addOrSetActive(part);
    }

    @Override
    public void sortMEOutput() {
        if (!meOutputRecipeHandleParts.isEmpty()) {
            meOutputRecipeHandleParts.sort(MEPatternRecipeHandlePart.COMPARATOR.reversed());

            boolean allHaveItemFilter = true;
            boolean allHaveFluidFilter = true;
            for (MEIORecipeHandlePart<?> meOutputRecipeHandlePart : meOutputRecipeHandleParts) {
                if (!meOutputRecipeHandlePart.hasItemFilter()) {
                    allHaveItemFilter = false;
                }
                if (!meOutputRecipeHandlePart.hasFluidFilter()) {
                    allHaveFluidFilter = false;
                }

                if (!allHaveItemFilter && !allHaveFluidFilter) {
                    break;
                }
            }

            meItemOutPutWithFilter = allHaveItemFilter;
            meFluidOutPutWithFilter = allHaveFluidFilter;
            meOutPutWithFilter = meItemOutPutWithFilter || meFluidOutPutWithFilter;
            itemOutPutAlwaysMatch = !meItemOutPutWithFilter && (meOutPutBus || meOutPutDual);
            fluidOutPutAlwaysMatch = !meFluidOutPutWithFilter && (meOutPutHatch || meOutPutDual);
        }
    }

    @Override
    public boolean emptyRecipeHandlePart() {
        return normalCapabilities.isEmpty() && mePatternRecipeHandleParts.isEmpty() && sharedInputRecipeHandlePart == null;
    }

    @Override
    public boolean emptyHandlePart() {
        return normalCapabilities.isEmpty() && mePatternRecipeHandleParts.isEmpty() && meOutputRecipeHandleParts.isEmpty() && sharedInputRecipeHandlePart == null;
    }

    @Override
    @Nullable
    public RecipeHandlePart getSharedRecipeHandlePart() {
        return sharedInputRecipeHandlePart;
    }

    @Override
    public List<MEPatternRecipeHandlePart> getMEPatternRecipeHandleParts() {
        return mePatternRecipeHandleParts;
    }

    @Override
    public List<MEIORecipeHandlePart<?>> getMEOutputRecipeHandleParts() {
        return meOutputRecipeHandleParts;
    }

    @Override
    public @NotNull List<RecipeHandlePart> getNormalRecipeHandlePart(IO io) {
        return normalCapabilities.getOrDefault(io, Collections.emptyList());
    }

    @Override
    public void upDate() {
        if (!this.isFormed) return;
        rebuildRecipeHandleParts(traitSubscriptions, getParts(), recipeLogic, isDistinct);
    }

    @Override
    public @Nullable IParallelHatch getParallelHatch() {
        return parallelHatch;
    }

    @Override
    public @Nullable IMaintenanceMachine getMaintenanceMachine() {
        return maintenanceMachine;
    }

    @Override
    public @Nullable IDataAccessHatch getDataAccessHatch() {
        return dataAccessHatch;
    }

    // ========================================
    // Rebuild
    // ========================================

    private void rebuildRecipeHandleParts(List<ISubscription> traitSubscriptions, List<IMultiPart> parts, RecipeLogic recipeLogic, boolean isDistinct) {
        clear();

        final var sharedInputHandlers = new ObjectArrayList<IRecipeHandler<?>>();
        for (IMultiPart part : parts) {
            if (part instanceof IMEFilterIOPartMachine mePart) {
                handleMETraitPart(traitSubscriptions, recipeLogic, mePart);
            } else if (part instanceof FluidHatchPartMachine || part instanceof IDistinctPart) {
                handleNormalPart(part, isDistinct, sharedInputHandlers);
            } else {
                handleSpecialPart(part);
            }
        }

        if (!isDistinct && !sharedInputHandlers.isEmpty()) {
            sharedInputRecipeHandlePart = RecipeHandlePart.of(IO.IN, sharedInputHandlers);
            mergeSharedHandlePart(sharedInputRecipeHandlePart);
        }
        sortMEOutput();
    }

    private void handleMETraitPart(List<ISubscription> traitSubscriptions, RecipeLogic recipeLogic, IMEFilterIOPartMachine mePart) {
        var pair = mePart.getMERecipeHandlerTraits();
        traitSubscriptions.add(pair.left().addBufferChangedListener(recipeLogic::updateTickSubscription));
        traitSubscriptions.add(pair.right().addBufferChangedListener(recipeLogic::updateTickSubscription));

        IO io = mePart.getMETrait().getIO();
        if (mePart instanceof IMEPatternPartMachine mePatternPart) {
            var me = MEPatternRecipeHandlePart.of(mePatternPart);
            me.restoreMachineCache(this::tryAddAndActiveRhp);
            mePatternRecipeHandleParts.add(me);
            if (io == IO.BOTH) {
                meOutputRecipeHandleParts.add(me);
                setAllMEOutputFlags();
            }
        } else if (io == IO.OUT) {
            meOutputRecipeHandleParts.add(MEIORecipeHandlePart.of(mePart));
            setAllMEOutputFlags();
        }
    }

    private void handleNormalPart(IMultiPart part, boolean isDistinct, List<IRecipeHandler<?>> sharedInputHandlers) {
        if (part instanceof MEOutputPartMachine) {
            setAllMEOutputFlags();
        } else if (part instanceof MEOutputBusPartMachine) {
            meOutPutBus = true;
        } else if (part instanceof MEOutputHatchPartMachine) {
            meOutPutHatch = true;
        }

        List<IRecipeHandler<?>> fluidHandlers = new ObjectArrayList<>();
        List<IRecipeHandler<?>> isolableHandlers = new ObjectArrayList<>();
        List<IRecipeHandler<?>> outputHandlers = new ObjectArrayList<>();
        boolean isOutput = false;

        for (var handler : part.getRecipeHandlers()) {
            if (handler.isProxy()) continue;

            IO io = handler.getHandlerIO();
            if (io == IO.IN) {
                if (handler.getCapability() == FluidRecipeCapability.CAP) fluidHandlers.add(handler);
                else isolableHandlers.add(handler);
            } else if (io == IO.OUT) {
                isOutput = true;
                outputHandlers.add(handler);
            }
        }

        if (isOutput) {
            normalCapabilities.computeIfAbsent(IO.OUT, ignore -> new ReferenceArrayList<>()).add(RecipeHandlePart.of(IO.OUT, outputHandlers));
        } else if (isDistinct) {
            isolableHandlers.addAll(fluidHandlers);
            normalCapabilities.computeIfAbsent(IO.IN, ignore -> new ReferenceArrayList<>()).add(RecipeHandlePart.of(IO.IN, isolableHandlers));
        } else if (part instanceof IDistinctPart distinctPart && distinctPart.isDistinct()) {
            normalCapabilities.computeIfAbsent(IO.IN, ignore -> new ReferenceArrayList<>()).add(RecipeHandlePart.of(IO.IN, isolableHandlers));
            sharedInputHandlers.addAll(fluidHandlers);
        } else {
            sharedInputHandlers.addAll(isolableHandlers);
            sharedInputHandlers.addAll(fluidHandlers);
        }
    }

    private void handleSpecialPart(IMultiPart part) {
        if (part instanceof IParallelHatch parallel) {
            this.parallelHatch = parallel;
        } else if (part instanceof IMufflerMachine muffler) {
            this.mufflerMachine = muffler;
        } else if (part instanceof IMaintenanceMachine maintenance) {
            this.maintenanceMachine = maintenance;
        } else if (part instanceof IDataAccessHatch data) {
            this.dataAccessHatch = data;
        }
    }

    private void mergeSharedHandlePart(@NotNull RecipeHandlePart shared) {
        for (List<RecipeHandlePart> value : normalCapabilities.values()) {
            for (RecipeHandlePart part : value) {
                part.addSharedFluidHandlers(shared.getCapability(FluidRecipeCapability.CAP));
            }
        }
    }

    // ========================================
    // Utils Methods
    // ========================================

    private void setAllMEOutputFlags() {
        meOutPutBus = true;
        meOutPutHatch = true;
        meOutPutDual = true;
    }

    private void clear() {
        meOutPutBus = false;
        meOutPutHatch = false;
        meOutPutDual = false;
        meOutPutWithFilter = false;
        meItemOutPutWithFilter = false;
        meFluidOutPutWithFilter = false;
        itemOutPutAlwaysMatch = false;
        fluidOutPutAlwaysMatch = false;
        parallelHatch = null;
        mufflerMachine = null;
        maintenanceMachine = null;
        dataAccessHatch = null;
        sharedInputRecipeHandlePart = null;
        normalCapabilities.clear();
        mePatternRecipeHandleParts.clear();
        meOutputRecipeHandleParts.clear();
        recipeHandleMap.clear();
    }
}
