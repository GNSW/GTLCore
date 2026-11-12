package org.gtlcore.gtlcore.mixin.gtm.cover;

import com.gregtechceu.gtceu.common.cover.MachineControllerCover;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MachineControllerCover.class)
public interface MachineControllerCoverAccessorMixin {

    @Invoker(value = "updateInput", remap = false)
    void gtlcore$updateInput();
}
