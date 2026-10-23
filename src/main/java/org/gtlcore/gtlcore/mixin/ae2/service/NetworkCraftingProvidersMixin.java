package org.gtlcore.gtlcore.mixin.ae2.service;

import org.gtlcore.gtlcore.integration.ae2.graph.ByproductPatternIndex;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphProviderVersion;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphProviderSnapshot;
import org.gtlcore.gtlcore.integration.ae2.graph.RegisteredPatternIndex;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGridNode;
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

    @Shadow(remap = false)
    @Final
    private Map<IGridNode, ?> craftingProviders;

    @Unique
    private long gtlcore$generation;

    @Unique
    private long gtlcore$planningGeneration;

    @Unique
    private IGridNode gtlcore$removedNode;

    @Unique
    private GraphProviderSnapshot gtlcore$removedState;

    @Unique
    private final RegisteredPatternIndex gtlcore$outputIndex = new RegisteredPatternIndex();

    @Override
    public void gtlcore$patternChanged(IPatternDetails pattern, boolean present) {
        gtlcore$outputIndex.changed(pattern, present);
    }

    @Override
    public ByproductPatternIndex gtlcore$patternSources() {
        return gtlcore$outputIndex.view();
    }

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
    public long gtlcore$planningGeneration() {
        gtlcore$finishRemoval();
        return gtlcore$planningGeneration;
    }

    @Unique
    private void gtlcore$finishRemoval() {
        if (gtlcore$removedState != null) {
            gtlcore$planningGeneration++;
            gtlcore$removedNode = null;
            gtlcore$removedState = null;
        }
    }

    @Inject(method = "removeProvider", at = @At("HEAD"), remap = false)
    private void gtlcore$rememberRemoval(IGridNode node, CallbackInfo ci) {
        // CraftingService refreshes a node by synchronous remove/add. Keep only
        // that immediate removal pending; any other edit or revision read makes
        // a real removal observable. No grid scan or retained removal history.
        gtlcore$finishRemoval();
        Object old = craftingProviders.get(node);
        if (old instanceof GraphProviderSnapshot snapshot) {
            gtlcore$removedNode = node;
            gtlcore$removedState = snapshot;
        }
    }

    @Inject(method = "addProvider", at = @At("RETURN"), remap = false)
    private void gtlcore$recordRegistration(IGridNode node, CallbackInfo ci) {
        Object current = craftingProviders.get(node);
        if (gtlcore$removedNode == node && current instanceof GraphProviderSnapshot snapshot) {
            if (!snapshot.gtlcore$samePlanningState(gtlcore$removedState)) gtlcore$planningGeneration++;
            gtlcore$removedNode = null;
            gtlcore$removedState = null;
        } else {
            gtlcore$finishRemoval();
            if (current != null) gtlcore$planningGeneration++;
        }
    }

    @Override
    public Iterable<IPatternDetails> gtlcore$registeredPatterns() {
        return Collections.unmodifiableSet(craftingMethods.keySet());
    }
}
