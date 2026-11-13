package org.gtlcore.gtlcore.mixin.gtm.jade;

import com.gregtechceu.gtceu.integration.jade.provider.CapabilityBlockProvider;

import net.minecraft.nbt.CompoundTag;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Dispatches to the existing provider, including GTLCore's overrides, without a fake Jade accessor. */
@Mixin(CapabilityBlockProvider.class)
public interface MonitorProviderInvoker {

    @Invoker(value = "write", remap = false)
    void gtlcore$writeMonitorData(CompoundTag data, Object capability);
}
