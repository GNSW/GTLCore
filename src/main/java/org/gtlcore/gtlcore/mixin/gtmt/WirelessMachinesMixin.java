package org.gtlcore.gtlcore.mixin.gtmt;

import com.gregtechceu.gtceu.api.GTValues;

import com.hepdd.gtmthings.data.WirelessMachines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Arrays;

@Mixin(value = WirelessMachines.class, remap = false)
public class WirelessMachinesMixin {

    @ModifyVariable(method = "registerWirelessLaserHatch", at = @At("HEAD"), argsOnly = true, index = 3)
    private static int[] gtlcore$removeEvLaserHatchTiers(int[] tiers) {
        if (Arrays.stream(tiers).noneMatch(tier -> tier == GTValues.EV)) {
            return tiers;
        }
        return Arrays.stream(tiers).filter(tier -> tier != GTValues.EV).toArray();
    }
}
