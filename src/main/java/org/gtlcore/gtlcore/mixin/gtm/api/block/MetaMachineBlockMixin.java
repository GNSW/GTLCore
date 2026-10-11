package org.gtlcore.gtlcore.mixin.gtm.api.block;

import org.gtlcore.gtlcore.api.machine.trait.IBatchMachine;
import org.gtlcore.gtlcore.config.ConfigHolder;

import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.machine.MetaMachine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MetaMachineBlock.class)
public abstract class MetaMachineBlockMixin {

    @Inject(method = "setPlacedBy", at = @At("TAIL"))
    private void gtlcore$enableBatchProcessingByDefault(Level level, BlockPos pos, BlockState state,
                                                        LivingEntity placer, ItemStack stack, CallbackInfo ci) {
        if (level.isClientSide || ConfigHolder.INSTANCE == null ||
                !ConfigHolder.INSTANCE.machines.batchProcessingEnabledByDefault)
            return;

        MetaMachine machine = MetaMachine.getMachine(level, pos);
        if (machine instanceof IBatchMachine batchMachine && batchMachine.supportsBatchProcessing()) {
            batchMachine.setBatchEnabled(true);
        }
    }
}
