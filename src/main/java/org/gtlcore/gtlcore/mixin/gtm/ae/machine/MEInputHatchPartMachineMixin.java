package org.gtlcore.gtlcore.mixin.gtm.ae.machine;

import org.gtlcore.gtlcore.api.gui.AdvancedMEConfigurator;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IModifiableSyncOffset;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.IOptimizedMEList;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.integration.ae2.machine.MEHatchPartMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.MEInputHatchPartMachine;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidList;

import net.minecraft.nbt.CompoundTag;

import appeng.api.networking.IGridNodeListener;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MEInputHatchPartMachine.class)
public abstract class MEInputHatchPartMachineMixin extends MEHatchPartMachine implements IModifiableSyncOffset {

    @Shadow(remap = false)
    protected ExportOnlyAEFluidList aeFluidHandler;

    public MEInputHatchPartMachineMixin(IMachineBlockEntity holder, IO io, Object... args) {
        super(holder, io, args);
    }

    @Inject(method = "autoIO",
            at = @At(value = "INVOKE",
                     target = "Lcom/gregtechceu/gtceu/integration/ae2/machine/MEInputHatchPartMachine;syncME()V",
                     shift = At.Shift.AFTER),
            remap = false)
    public void autoIO(CallbackInfo ci) {
        if (aeFluidHandler instanceof IOptimizedMEList machine) {
            machine.setChanged(true);
        }
    }

    @Inject(method = "writeConfigToTag",
            at = @At("RETURN"),
            remap = false)
    public void writesSyncOffset(CallbackInfoReturnable<CompoundTag> cir) {
        cir.getReturnValue().putInt("SyncOffset", getOffset());
    }

    @Inject(method = "readConfigFromTag",
            at = @At("RETURN"),
            remap = false)
    public void readSyncOffset(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains("SyncOffset")) {
            this.setOffset(tag.getInt("SyncOffset"));
        }
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.@NotNull State reason) {
        super.onMainNodeStateChanged(reason);
        if (getMainNode().isOnline()) aeFluidHandler.notifyListeners();
    }

    @Override
    public void attachConfigurators(@NotNull ConfiguratorPanel configuratorPanel) {
        super.attachConfigurators(configuratorPanel);
        configuratorPanel.attachConfigurators(new AdvancedMEConfigurator(this::setOffset, this::getOffset));
    }
}
