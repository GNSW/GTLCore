package org.gtlcore.gtlcore.mixin.ae2.storage;

import org.gtlcore.gtlcore.integration.ae2.storagegraph.SourceRegistry;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.StorageGraphManager;
import org.gtlcore.gtlcore.integration.ae2.throughput.ThroughputMonitorStorageTracker;
import org.gtlcore.gtlcore.integration.ae2.throughput.ThroughputStorageView;

import appeng.api.stacks.AEKeyType;
import appeng.api.storage.MEStorage;
import appeng.me.storage.CompositeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mixin(CompositeStorage.class)
public abstract class CompositeStorageMixin implements ThroughputStorageView {

    @Shadow(remap = false)
    private Map<AEKeyType, MEStorage> storages;

    @Inject(method = "setStorages", at = @At("RETURN"), remap = false)
    private void gtlcore$invalidateThroughputTopology(Map<AEKeyType, MEStorage> storages, CallbackInfo ci) {
        ThroughputMonitorStorageTracker.onTopologyChanged((MEStorage) (Object) this);
        SourceRegistry.onDelegateChanged((MEStorage) (Object) this);
        StorageGraphManager.onTopologyChanged();
    }

    @Override
    public Collection<MEStorage> gtlcore$getChildStorages() {
        return storages == null ? List.of() : List.copyOf(storages.values());
    }
}
