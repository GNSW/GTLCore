package org.gtlcore.gtlcore.mixin.gtm.machine.part;

import com.gregtechceu.gtceu.common.machine.multiblock.part.ReservoirHatchPartMachine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(ReservoirHatchPartMachine.class)
public abstract class ReservoirHatchPartMachineMixin {

    @ModifyArg(method = "createTank",
               at = @At(value = "INVOKE",
                        target = "Lcom/gregtechceu/gtceu/common/machine/multiblock/part/ReservoirHatchPartMachine$InfiniteWaterTank;<init>(J)V"),
               index = 0,
               remap = false)
    private long gtlcore$maxCapacity(long capacity) {
        return Long.MAX_VALUE;
    }

    @ModifyConstant(method = "autoIO", constant = @Constant(longValue = 20L), remap = false)
    private long gtlcore$refillEvery5Ticks(long interval) {
        return 5L;
    }
}
