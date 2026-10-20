package org.gtlcore.gtlcore.mixin.gtm.ae.slot;

import org.gtlcore.gtlcore.api.machine.trait.MEStock.IMESlot;

import com.gregtechceu.gtceu.integration.ae2.machine.MEStockingHatchPartMachine;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidSlot;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

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

@Mixin(targets = "com.gregtechceu.gtceu.integration.ae2.machine.MEStockingHatchPartMachine$ExportOnlyAEStockingFluidSlot", remap = false)
public abstract class ExportOnlyAEStockingFluidSlotMixin extends ExportOnlyAEFluidSlot implements IMESlot {

    @SuppressWarnings("target") // Synthetic outer-instance field exists in the dependency bytecode.
    @Shadow(remap = false)
    @Final
    private MEStockingHatchPartMachine this$0;

    @Setter
    @Getter
    private Runnable onConfigChanged;

    @Inject(method = "drain", at = @At("HEAD"), cancellable = true)
    private void gtlcore$respectWorkingEnabled(long maxDrain, boolean simulate, boolean notifyChanges,
                                               CallbackInfoReturnable<FluidStack> cir) {
        if (!this.this$0.isWorkingEnabled()) {
            cir.setReturnValue(FluidStack.empty());
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
