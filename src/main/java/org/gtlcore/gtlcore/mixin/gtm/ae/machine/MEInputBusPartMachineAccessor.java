package org.gtlcore.gtlcore.mixin.gtm.ae.machine;

import com.gregtechceu.gtceu.integration.ae2.machine.MEInputBusPartMachine;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemList;

import net.minecraft.nbt.CompoundTag;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MEInputBusPartMachine.class)
public interface MEInputBusPartMachineAccessor {

    @Accessor(value = "aeItemHandler", remap = false)
    ExportOnlyAEItemList gtlcore$getAeItemHandler();

    @Invoker(value = "writeConfigToTag", remap = false)
    CompoundTag gtlcore$writeConfigToTag();

    @Invoker(value = "readConfigFromTag", remap = false)
    void gtlcore$readConfigFromTag(CompoundTag tag);
}
