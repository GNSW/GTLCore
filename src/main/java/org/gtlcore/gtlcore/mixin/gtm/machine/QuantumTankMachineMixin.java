package org.gtlcore.gtlcore.mixin.gtm.machine;

import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
import com.gregtechceu.gtceu.common.machine.storage.QuantumTankMachine;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(QuantumTankMachine.class)
public abstract class QuantumTankMachineMixin {

    @Shadow(remap = false)
    @Final
    protected NotifiableFluidTank cache;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void gtlcore$trackLockChanges(CallbackInfo ci) {
        // Track the lock storage itself: ghost-slot edits and restored state can write it directly.
        cache.getLockedFluid().setOnContentsChanged(() -> {
            cache.setFilter(fluid -> !cache.isLocked() || fluid.isFluidEqual(cache.getLockedFluid().getFluid()));
            cache.onContentsChanged();
        });
        cache.setFilter(fluid -> !cache.isLocked() || fluid.isFluidEqual(cache.getLockedFluid().getFluid()));
    }
}
