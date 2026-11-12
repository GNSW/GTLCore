package org.gtlcore.gtlcore.mixin.gtm.api.machine;

import org.gtlcore.gtlcore.api.machine.IGTLRenderDisabled;

import com.gregtechceu.gtceu.api.machine.MetaMachine;

import net.minecraft.nbt.CompoundTag;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MetaMachine.class, remap = false)
public abstract class MetaMachineRenderDisabledMixin implements IGTLRenderDisabled {

    @Unique
    private boolean gtlcore$renderDisabled;

    @Override
    public boolean gtlcore$isRenderDisabled() {
        return gtlcore$renderDisabled;
    }

    @Override
    public void gtlcore$toggleRenderDisabled() {
        gtlcore$renderDisabled = !gtlcore$renderDisabled;
        MetaMachine self = (MetaMachine) (Object) this;
        self.notifyBlockUpdate();
        self.markDirty();
    }

    @Inject(method = "saveCustomPersistedData", at = @At("TAIL"), remap = false)
    private void gtlcore$saveRenderDisabled(CompoundTag tag, boolean forSyncing, CallbackInfo ci) {
        tag.putBoolean("gtlcore:renderDisabled", gtlcore$renderDisabled);
    }

    @Inject(method = "loadCustomPersistedData", at = @At("TAIL"), remap = false)
    private void gtlcore$loadRenderDisabled(CompoundTag tag, CallbackInfo ci) {
        gtlcore$renderDisabled = tag.getBoolean("gtlcore:renderDisabled");
    }
}
