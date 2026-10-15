package org.gtlcore.gtlcore.mixin.gtm.machine.part;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(targets = "com.gregtechceu.gtceu.common.machine.multiblock.part.ReservoirHatchPartMachine$InfiniteWaterTank")
public abstract class InfiniteWaterTankMixin {

    @ModifyArg(method = "refillWater",
               at = @At(value = "INVOKE",
                        target = "Lcom/lowdragmc/lowdraglib/misc/FluidStorage;fill(ILcom/lowdragmc/lowdraglib/side/fluid/FluidStack;ZZ)J"),
               index = 1,
               remap = false)
    private FluidStack gtlcore$fillToCapacity(FluidStack resource) {
        return FluidStack.create(resource, Long.MAX_VALUE);
    }
}
