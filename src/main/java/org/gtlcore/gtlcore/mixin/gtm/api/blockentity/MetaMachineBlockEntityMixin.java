package org.gtlcore.gtlcore.mixin.gtm.api.blockentity;

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.MEBusPartMachine;

import net.minecraft.core.Direction;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MetaMachineBlockEntity.class)
public class MetaMachineBlockEntityMixin {

    @Inject(method = "getCapability(Lcom/gregtechceu/gtceu/api/machine/MetaMachine;Lnet/minecraftforge/common/capabilities/Capability;Lnet/minecraft/core/Direction;)Lnet/minecraftforge/common/util/LazyOptional;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void gtlcore$denyAeBusExternalIO(MetaMachine machine, Capability<?> cap, @Nullable Direction side,
                                                    CallbackInfoReturnable<LazyOptional<?>> cir) {
        if (machine instanceof MEBusPartMachine && (cap == ForgeCapabilities.ITEM_HANDLER || cap == ForgeCapabilities.FLUID_HANDLER)) {
            cir.setReturnValue(LazyOptional.empty());
        }
    }
}
