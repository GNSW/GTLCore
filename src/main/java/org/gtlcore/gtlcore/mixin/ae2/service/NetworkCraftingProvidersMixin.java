package org.gtlcore.gtlcore.mixin.ae2.service;

import org.gtlcore.gtlcore.integration.ae2.graph.GraphProviderVersion;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.storage.AEKeyFilter;
import appeng.me.service.helpers.NetworkCraftingProviders;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.Map;

@Mixin(NetworkCraftingProviders.class)
public abstract class NetworkCraftingProvidersMixin implements GraphProviderVersion {

    @Shadow(remap = false)
    @Final
    private Map<IPatternDetails, ?> craftingMethods;

    @Unique
    private long gtlcore$generation;

    @WrapOperation(method = "getFuzzyCraftable",
                   at = @At(value = "INVOKE", target = "Lappeng/api/storage/AEKeyFilter;matches(Lappeng/api/stacks/AEKey;)Z"),
                   remap = false)
    private boolean gtlcore$filterUnmountedCraftables(AEKeyFilter filter, AEKey key, Operation<Boolean> original,
                                                      @Local Object2LongMap.Entry<AEKey> candidate) {
        // AE retains zero references after unmounting. Skip them inside the search so later
        // live variants can still match, without changing the shared counter's semantics.
        return candidate.getLongValue() > 0 && original.call(filter, key);
    }

    @Inject(method = "setLastModifiedOnTick", at = @At("RETURN"), remap = false)
    private void gtlcore$advanceProviderRevision(CallbackInfo ci) {
        gtlcore$generation++;
    }

    @Override
    public long gtlcore$providerGeneration() {
        return gtlcore$generation;
    }

    @Override
    public Iterable<IPatternDetails> gtlcore$registeredPatterns() {
        return Collections.unmodifiableSet(craftingMethods.keySet());
    }
}
