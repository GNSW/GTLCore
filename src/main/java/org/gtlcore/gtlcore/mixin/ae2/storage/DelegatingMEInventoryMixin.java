package org.gtlcore.gtlcore.mixin.ae2.storage;

import org.gtlcore.gtlcore.integration.ae2.storagegraph.SourceRegistry;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.StorageGraphManager;
import org.gtlcore.gtlcore.integration.ae2.throughput.ThroughputMonitorStorageTracker;
import org.gtlcore.gtlcore.integration.ae2.throughput.ThroughputStorageView;

import appeng.api.storage.MEStorage;
import appeng.me.storage.DelegatingMEInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.List;

@Mixin(DelegatingMEInventory.class)
public abstract class DelegatingMEInventoryMixin implements ThroughputStorageView {

    @Shadow(remap = false)
    protected abstract MEStorage getDelegate();

    @Inject(method = "setDelegate", at = @At("RETURN"), remap = false)
    private void gtlcore$invalidateThroughputTopology(MEStorage delegate, CallbackInfo ci) {
        ThroughputMonitorStorageTracker.onTopologyChanged((MEStorage) (Object) this);
        SourceRegistry.onDelegateChanged((MEStorage) (Object) this);
        StorageGraphManager.onTopologyChanged();
    }

    @Override
    public Collection<MEStorage> gtlcore$getChildStorages() {
        MEStorage delegate = getDelegate();
        return delegate == null ? List.of() : List.of(delegate);
    }
}
