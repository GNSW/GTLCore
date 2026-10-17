package org.gtlcore.gtlcore.mixin.ae2.crafting;

import org.gtlcore.gtlcore.integration.ae2.InventorySnapshot;
import org.gtlcore.gtlcore.integration.ae2.InventorySnapshotVersion;
import org.gtlcore.gtlcore.integration.ae2.crafting.IMaxFastNetworkInventoryFingerprint;
import org.gtlcore.gtlcore.integration.ae2.crafting.InventorySimulationAccess;
import org.gtlcore.gtlcore.integration.ae2.crafting.compiled.MaxFastFingerprint;

import appeng.api.config.FuzzyMode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.NetworkCraftingSimulationState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(NetworkCraftingSimulationState.class)
public abstract class NetworkCraftingSimulationStateMixin implements IMaxFastNetworkInventoryFingerprint {

    @Shadow(remap = false)
    @Final
    private KeyCounter list;

    @Unique
    private InventorySnapshot gtlcore$snapshot;
    @Unique
    private InventorySimulationAccess gtlcore$access;

    @Override
    @Unique
    public MaxFastFingerprint.Result gtlcore$getMaxFastInventoryFingerprint() {
        // Deferred permissions are not a fingerprint of the whole physical stock.
        if (gtlcore$access == null || gtlcore$access.requiresSimulation())
            return new MaxFastFingerprint.Result(MaxFastFingerprint.UNAVAILABLE, 0, 0L);
        return gtlcore$snapshot == null ? MaxFastFingerprint.ofKeyCounter(list) : gtlcore$snapshot.fingerprint();
    }

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void gtlcore$captureSource(IStorageService storage, IActionSource source, CallbackInfo ci) {
        if (source != null) gtlcore$access = new InventorySimulationAccess(storage.getInventory(), source);
    }

    @Redirect(method = "<init>",
              at = @At(value = "INVOKE",
                       target = "Lappeng/api/networking/storage/IStorageService;getCachedInventory()Lappeng/api/stacks/KeyCounter;"),
              remap = false)
    private KeyCounter gtlcore$captureVersion(IStorageService storage) {
        KeyCounter cached = storage.getCachedInventory();
        if (!(storage instanceof InventorySnapshotVersion version)) return cached;
        // No inventory traversal, index building or waiting on workers here.
        gtlcore$snapshot = version.gtlcore$inventorySnapshot();
        return new KeyCounter();
    }

    /**
     * @author GTLCore
     * @reason Async calculations must never read AE's mutable live inventory counter.
     */
    @Overwrite(remap = false)
    protected long simulateExtractParent(AEKey what, long amount) {
        long available = gtlcore$snapshot == null ? list.get(what) : gtlcore$snapshot.get(what);
        if (gtlcore$access != null) available = gtlcore$access.available(what, available);
        return Math.min(available, amount);
    }

    /**
     * @author GTLCore
     * @reason Enumerate stable variants and check permissions only for relevant keys.
     */
    @Overwrite(remap = false)
    protected Iterable<AEKey> findFuzzyParent(AEKey input) {
        if (input == null) return List.of();
        var keys = gtlcore$snapshot == null ? new ArrayList<AEKey>() : gtlcore$snapshot.findFuzzy(input);
        if (gtlcore$snapshot == null) {
            for (var entry : list.findFuzzy(input, FuzzyMode.IGNORE_ALL)) keys.add(entry.getKey());
        }
        if (gtlcore$access != null && gtlcore$access.requiresSimulation())
            keys.removeIf(key -> gtlcore$access.available(key,
                    gtlcore$snapshot == null ? list.get(key) : gtlcore$snapshot.get(key)) <= 0);
        return keys;
    }
}
