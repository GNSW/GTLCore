package org.gtlcore.gtlcore.mixin.gtm.machine;

import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
import org.gtlcore.gtlcore.api.machine.trait.NotifiableCircuitItemStackHandler;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredIOPartMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.gregtechceu.gtceu.common.machine.multiblock.part.ItemBusPartMachine;

import com.lowdragmc.lowdraglib.gui.widget.PhantomSlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
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
    private ItemStackTransfer gtlcore$outputFilterSlot;

    public ItemBusPartMachineMixin(IMachineBlockEntity holder, int tier, IO io) {
        super(holder, tier, io);
    }

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void gtlcore$initializeOutputFilterSlot(IMachineBlockEntity holder, int tier, IO io, Object[] args,
                                                    CallbackInfo ci) {
        gtlcore$outputFilterSlot = new ItemStackTransfer(1);
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
                    .setFilter(itemStack -> !IntCircuitBehaviour.isIntegratedCircuit(itemStack)));
        } else if (io == IO.OUT) {
            cir.setReturnValue(new NotifiableItemStackHandler(this, getInventorySize(), io)
                    .setFilter(this::gtlcore$matchesFilter));
        }
    }

    @Unique
    private boolean gtlcore$matchesFilter(ItemStack stack) {
        if (gtlcore$outputFilterSlot == null) return true;
        ItemStack filterStack = gtlcore$outputFilterSlot.getStackInSlot(0);
        return filterStack.isEmpty() || ItemStack.isSameItemSameTags(filterStack, stack);
    }

    @Inject(method = "createUIWidget", at = @At("RETURN"), remap = false)
    private void gtlcore$addFilterSlot(CallbackInfoReturnable<Widget> cir) {
        if (io == IO.OUT && cir.getReturnValue() instanceof WidgetGroup group && gtlcore$outputFilterSlot != null) {
            var size = group.getSize();
            int x = (size.width - 18) / 2;
            group.setSize(size.width, size.height + 18);
            group.addWidget(new PhantomSlotWidget(gtlcore$outputFilterSlot, 0, x, size.height)
                    .setBackground(GuiTextures.SLOT)
                    .setChangeListener(this::gtlcore$onOutputFilterChanged));
        }
    }

    @Unique
    private void gtlcore$onOutputFilterChanged() {
        getInventory().onContentsChanged();
        markDirty();
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
