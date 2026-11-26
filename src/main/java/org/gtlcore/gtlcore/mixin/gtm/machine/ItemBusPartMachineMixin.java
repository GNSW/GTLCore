package org.gtlcore.gtlcore.mixin.gtm.machine;

import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
import org.gtlcore.gtlcore.api.machine.trait.NotifiableCircuitItemStackHandler;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.cover.filter.FilterHandler;
import com.gregtechceu.gtceu.api.cover.filter.FilterHandlers;
import com.gregtechceu.gtceu.api.cover.filter.ItemFilter;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredIOPartMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.gregtechceu.gtceu.common.machine.multiblock.part.ItemBusPartMachine;

import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;

import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemBusPartMachine.class)
public abstract class ItemBusPartMachineMixin extends TieredIOPartMachine {

    @Unique
    @Persisted
    @DescSynced
    private FilterHandler<ItemStack, ItemFilter> gtlcore$filterHandler;

    public ItemBusPartMachineMixin(IMachineBlockEntity holder, int tier, IO io) {
        super(holder, tier, io);
    }

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void gtlcore$initializeFilterHandler(IMachineBlockEntity holder, int tier, IO io, Object[] args,
                                                 CallbackInfo ci) {
        gtlcore$filterHandler = FilterHandlers.item(this);
    }

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    protected int getInventorySize() {
        int sizeRoot = 1 + getTier();
        return sizeRoot * sizeRoot;
    }

    @Inject(method = "createInventory", at = @At("HEAD"), remap = false, cancellable = true)
    protected void createInventory(Object[] args, CallbackInfoReturnable<NotifiableItemStackHandler> cir) {
        if (io == IO.IN) {
            cir.setReturnValue(new NotifiableItemStackHandler(this, getInventorySize(), IO.IN)
                    .setFilter(itemStack -> !IntCircuitBehaviour.isIntegratedCircuit(itemStack) && gtlcore$matchesFilter(itemStack)));
        } else {
            cir.setReturnValue(new NotifiableItemStackHandler(this, getInventorySize(), io)
                    .setFilter(this::gtlcore$matchesFilter));
        }
    }

    @Unique
    private boolean gtlcore$matchesFilter(ItemStack stack) {
        return gtlcore$filterHandler == null || !gtlcore$filterHandler.isFilterPresent() || gtlcore$filterHandler.getFilter().test(stack);
    }

    @Inject(method = "createUIWidget", at = @At("RETURN"), remap = false)
    private void gtlcore$addFilterSlot(CallbackInfoReturnable<Widget> cir) {
        if (cir.getReturnValue() instanceof WidgetGroup group && gtlcore$filterHandler != null) {
            var size = group.getSize();
            group.setSize(size.width + 18, size.height);
            group.addWidget(gtlcore$filterHandler.createFilterSlotUI(size.width, 4));
        }
    }

    @Inject(method = "createCircuitItemHandler", at = @At("HEAD"), remap = false, cancellable = true)
    protected void createCircuitItemHandler(Object[] args, CallbackInfoReturnable<NotifiableItemStackHandler> cir) {
        if (args.length > 0 && args[0] instanceof IO io && io == IO.IN) {
            cir.setReturnValue(new NotifiableCircuitItemStackHandler(this));
        }
    }

    @Inject(method = "setDistinct", at = @At("RETURN"), remap = false)
    public void setDistinct(boolean isDistinct, CallbackInfo ci) {
        for (var controller : this.getControllers()) {
            if (controller instanceof IRecipeCapabilityMachine machine && !machine.isDistinct()) {
                machine.upDate();
            }
        }
    }
}
