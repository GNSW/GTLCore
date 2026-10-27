package org.gtlcore.gtlcore.mixin.gtm.ae.machine;

import com.gregtechceu.gtceu.integration.ae2.machine.MEInputHatchPartMachine;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidList;

import net.minecraft.nbt.CompoundTag;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MEInputHatchPartMachine.class)
public interface MEInputHatchPartMachineAccessor {

    @Accessor(value = "aeFluidHandler", remap = false)
    ExportOnlyAEFluidList gtlcore$getAeFluidHandler();

    @Invoker(value = "writeConfigToTag", remap = false)
    CompoundTag gtlcore$writeConfigToTag();

    @Invoker(value = "readConfigFromTag", remap = false)
    void gtlcore$readConfigFromTag(CompoundTag tag);
}
