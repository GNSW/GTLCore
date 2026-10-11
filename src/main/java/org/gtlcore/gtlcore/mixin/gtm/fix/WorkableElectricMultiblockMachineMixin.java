package org.gtlcore.gtlcore.mixin.gtm.fix;

import org.gtlcore.gtlcore.api.machine.trait.IBatchMachine;
import org.gtlcore.gtlcore.api.machine.trait.ILockRecipe;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeStatus;
import org.gtlcore.gtlcore.api.recipe.BatchProcessing;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.api.recipe.RecipeText;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.IOpticalComputationProvider;
import com.gregtechceu.gtceu.api.capability.IOpticalComputationReceiver;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.IFancyUIMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.misc.EnergyContainerList;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gregtechceu.gtceu.common.machine.multiblock.electric.FusionReactorMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.electric.research.DataBankMachine;
import com.gregtechceu.gtceu.utils.GTUtil;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.List;

@Mixin(WorkableElectricMultiblockMachine.class)
public abstract class WorkableElectricMultiblockMachineMixin extends WorkableMultiblockMachine implements IFancyUIMachine, IBatchMachine {

    @Unique
    private static final String GTLCORE_BATCH_ENABLED_NBT = "GTLCoreBatchEnabled";

    @Unique
    @Getter
    private boolean batchEnabled = false;

    public WorkableElectricMultiblockMachineMixin(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Shadow(remap = false)
    protected EnergyContainerList energyContainer;

    @Shadow(remap = false)
    public abstract EnergyContainerList getEnergyContainer();

    @Shadow(remap = false)
    protected int tier;

    @Shadow(remap = false)
    public boolean isGenerator() {
        throw new AssertionError();
    }

    /**
     * @author mod_author, Dragons
     * @reason always 1A amperage, Fix 2 64A EnergyHatch
     */
    @Overwrite(remap = false)
    public long getOverclockVoltage() {
        if (this.energyContainer == null) {
            this.energyContainer = this.getEnergyContainer();
        }

        return Math.max(energyContainer.getInputVoltage(), energyContainer.getOutputVoltage());
    }

    /**
     * @author Dragons
     * @reason always 1A amperage
     */
    @Overwrite(remap = false)
    public long getMaxVoltage() {
        if (this.energyContainer == null) {
            this.energyContainer = getEnergyContainer();
        }
        return this.isGenerator() ? energyContainer.getOutputVoltage() : energyContainer.getNumHighestInputContainers() > 1 ? GTValues.V[Math.min(GTUtil.getTierByVoltage(energyContainer.getHighestInputVoltage()) + 1, GTValues.MAX)] : energyContainer.getHighestInputVoltage();
    }

    @Override
    public void setBatchEnabled(boolean enabled) {
        boolean nextState = enabled && supportsBatchProcessing();
        if (this.batchEnabled == nextState) return;
        this.batchEnabled = nextState;
        this.markDirty();
        this.getRecipeLogic().markLastRecipeDirty();
        this.getRecipeLogic().updateTickSubscription();
    }

    @Override
    public void saveCustomPersistedData(@NotNull CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        tag.putBoolean(GTLCORE_BATCH_ENABLED_NBT, this.batchEnabled);
    }

    @Override
    public void loadCustomPersistedData(@NotNull CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.contains(GTLCORE_BATCH_ENABLED_NBT, Tag.TAG_BYTE)) {
            this.batchEnabled = tag.getBoolean(GTLCORE_BATCH_ENABLED_NBT);
        }
    }

    @Override
    public boolean supportsBatchProcessing() {
        return !BatchProcessing.isBatchDisabledDefinition(this) && gtlcore$hasBaseBatchSupport() &&
                !BatchProcessing.isCrossRecipeParallel(this);
    }

    @Override
    public boolean canConfigureBatchProcessing() {
        return !BatchProcessing.isBatchDisabledDefinition(this) && gtlcore$hasBaseBatchSupport() &&
                BatchProcessing.canConfigureBatchProcessing(this);
    }

    @Unique
    private boolean gtlcore$hasBaseBatchSupport() {
        var recipeTypes = getDefinition().getRecipeTypes();
        return !isGenerator() && !(self() instanceof IOpticalComputationReceiver) &&
                !(self() instanceof IOpticalComputationProvider) && !(self() instanceof DataBankMachine) &&
                recipeTypes != null && Arrays.stream(recipeTypes).anyMatch(type -> type != GTRecipeTypes.DUMMY_RECIPES);
    }

    @Override
    public void attachConfigurators(ConfiguratorPanel configuratorPanel) {
        configuratorPanel.attachConfigurators(new IFancyConfiguratorButton.Toggle(
                GuiTextures.BUTTON_POWER.getSubTexture(0, 0, 1, 0.5),
                GuiTextures.BUTTON_POWER.getSubTexture(0, 0.5, 1, 0.5),
                this::isWorkingEnabled, (clickData, pressed) -> this.setWorkingEnabled(pressed))
                .setTooltipsSupplier(pressed -> List.of(
                        Component.translatable(pressed ? "behaviour.soft_hammer.enabled" : "behaviour.soft_hammer.disabled"))));
        if (this.self() instanceof IOpticalComputationReceiver || this.self() instanceof IOpticalComputationProvider || this.self() instanceof DataBankMachine) return;
        if (!this.isGenerator()) {
            IRecipeCapabilityMachine.attachConfigurators(configuratorPanel, (WorkableElectricMultiblockMachine) self());
            ILockRecipe.attachRecipeLockable(configuratorPanel, this.getRecipeLogic());
        }
    }

    @ModifyArg(method = "addDisplayText", at = @At(value = "INVOKE", target = "Lcom/gregtechceu/gtceu/api/machine/multiblock/MultiblockDisplayText$Builder;addEnergyTierLine(I)Lcom/gregtechceu/gtceu/api/machine/multiblock/MultiblockDisplayText$Builder;"), index = 0, remap = false)
    private int gtlcore$getDisplayedRecipeTier(int energyTier) {
        // Fusion recipes are limited by the reactor tier, not the inherited hatch voltage tier.
        return self() instanceof FusionReactorMachine fusion ? fusion.getTier() : energyTier;
    }

    @Inject(method = "addDisplayText", at = @At(value = "INVOKE", target = "Lcom/gregtechceu/gtceu/api/machine/multiblock/WorkableElectricMultiblockMachine;getDefinition()Lcom/gregtechceu/gtceu/api/machine/MultiblockMachineDefinition;"), remap = false)
    public void addDisplayText(List<Component> textList, CallbackInfo ci) {
        if (this.isFormed()) {
            if (this.getRecipeLogic() instanceof IRecipeStatus status &&
                    status.getRecipeStatus() != null &&
                    status.getRecipeStatus().reason() != null) {
                textList.add(status.getRecipeStatus().reason().copy().withStyle(ChatFormatting.RED));
                if (status.getWorkingStatus() != null && status.getWorkingStatus().reason() != null)
                    textList.add(status.getWorkingStatus().reason().copy().withStyle(ChatFormatting.RED));
            }
        }
        if (this.getRecipeLogic() instanceof ILockRecipe iLockRecipe) {
            if (iLockRecipe.isLock() && iLockRecipe.getLockRecipe() != null) {
                textList.add(Component.translatable("gui.gtlcore.recipe_lock.recipe")
                        .withStyle((style -> style.withHoverEvent((new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                RecipeText.getRecipeInputText(iLockRecipe.getLockRecipe())
                                        .append(RecipeText.getRecipeOutputText(iLockRecipe.getLockRecipe()))))))));
            } else {
                textList.add(Component.translatable("gui.gtlcore.recipe_lock.no_recipe"));
            }
        }
        var activeRecipe = this.getRecipeLogic().getLastRecipe();
        if (this.getRecipeLogic().isActive() && activeRecipe != null && IGTRecipe.of(activeRecipe).getBatchSize() > 1) {
            textList.add(Component.translatable("gui.gtlcore.batch_processing.active",
                    IGTRecipe.of(activeRecipe).getBatchSize()));
        }
    }
}
