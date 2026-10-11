package org.gtlcore.gtlcore.mixin.gtmt;

import org.gtlcore.gtlcore.integration.gtmt.WirelessEnergyMonitorSnapshot;

import com.gregtechceu.gtceu.api.machine.MetaMachine;

import com.hepdd.gtmthings.api.misc.WirelessEnergyManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.math.BigInteger;
import java.util.UUID;

@Mixin(WirelessEnergyManager.class)
public abstract class WirelessEnergyManagerMixin {

    @Inject(method = "addEUToGlobalEnergyMap(Ljava/util/UUID;Ljava/math/BigInteger;Lcom/gregtechceu/gtceu/api/machine/MetaMachine;)Z",
            at = @At("RETURN"),
            remap = false)
    private static void gtlcore$recordWirelessEnergyMonitorSnapshot(UUID userId, BigInteger energy,
                                                                    MetaMachine machine,
                                                                    CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            WirelessEnergyMonitorSnapshot.record(userId, energy.longValue(), machine);
            org.gtlcore.gtlcore.integration.gtmt.monitor.WirelessEnergyMachineStats.record(userId, energy, machine);
        }
    }
}
