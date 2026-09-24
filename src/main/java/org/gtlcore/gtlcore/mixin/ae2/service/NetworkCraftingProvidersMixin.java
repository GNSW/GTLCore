package org.gtlcore.gtlcore.mixin.ae2.service;

import org.gtlcore.gtlcore.integration.ae2.graph.GraphProviderVersion;

import appeng.me.service.helpers.NetworkCraftingProviders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetworkCraftingProviders.class)
public abstract class NetworkCraftingProvidersMixin implements GraphProviderVersion {

    @Unique
    private long gtlcore$generation;

    @Inject(method = "setLastModifiedOnTick", at = @At("RETURN"), remap = false)
    private void gtlcore$advanceProviderRevision(CallbackInfo ci) {
        gtlcore$generation++;
    }

    @Override
    public long gtlcore$providerGeneration() {
        return gtlcore$generation;
    }
}
