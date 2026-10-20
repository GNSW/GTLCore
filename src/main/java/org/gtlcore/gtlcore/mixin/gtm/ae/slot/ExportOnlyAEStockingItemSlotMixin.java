package org.gtlcore.gtlcore.mixin.gtm.ae.slot;

import org.gtlcore.gtlcore.api.machine.trait.MEStock.IMESlot;

import com.gregtechceu.gtceu.integration.ae2.machine.MEStockingBusPartMachine;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemSlot;

import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.GenericStack;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static com.lowdragmc.lowdraglib.LDLib.isRemote;

@Mixin(targets = "com.gregtechceu.gtceu.integration.ae2.machine.MEStockingBusPartMachine$ExportOnlyAEStockingItemSlot", remap = false)
public abstract class ExportOnlyAEStockingItemSlotMixin extends ExportOnlyAEItemSlot implements IMESlot {

    @SuppressWarnings("target") // Synthetic outer-instance field exists in the dependency bytecode.
    @Shadow(remap = false)
    @Final
    private MEStockingBusPartMachine this$0;

    @Setter
    @Getter
    private Runnable onConfigChanged;

    @Inject(method = "extractItem", at = @At("HEAD"), cancellable = true)
    private void gtlcore$respectWorkingEnabled(int slot, int amount, boolean simulate, boolean notifyChanges,
                                               CallbackInfoReturnable<ItemStack> cir) {
        if (!this.this$0.isWorkingEnabled()) {
            cir.setReturnValue(ItemStack.EMPTY);
        }
    }

    @Override
    public void setConfig(@Nullable GenericStack config) {
        super.setConfig(config);
        if (!isRemote()) onConfigChanged.run();
    }

    @Override
    public void setConfigWithoutNotify(@Nullable GenericStack config) {
        this.config = config;
    }
}
