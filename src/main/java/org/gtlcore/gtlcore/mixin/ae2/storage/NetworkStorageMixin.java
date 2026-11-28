package org.gtlcore.gtlcore.mixin.ae2.storage;

import org.gtlcore.gtlcore.config.StorageGraphMode;
import org.gtlcore.gtlcore.integration.ae2.crafting.ManualCraftingInventoryLock;
import org.gtlcore.gtlcore.integration.ae2.storage.NetworkExtractionSimulation;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.ExternalInventoryIdentity;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.SourceRegistry;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.StorageGraphManager;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.StorageGraphSnapshot;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.StorageOperationContext;
import org.gtlcore.gtlcore.integration.ae2.throughput.ThroughputMonitorStorageTracker;
import org.gtlcore.gtlcore.integration.ae2.throughput.ThroughputStorageView;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.me.storage.NetworkStorage;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NavigableMap;

@Mixin(NetworkStorage.class)
public abstract class NetworkStorageMixin implements ThroughputStorageView, ManualCraftingInventoryLock.AvailabilityView {

    @Shadow(remap = false)
    @Final
    private NavigableMap<Integer, List<MEStorage>> priorityInventory;

    @Unique
    private long gtlcore$throughputTopologyVersion;

    @Inject(method = "mount", at = @At("RETURN"), remap = false)
    private void gtlcore$bumpThroughputTopologyOnMount(int priority, MEStorage storage, CallbackInfo ci) {
        gtlcore$throughputTopologyVersion++;
        ThroughputMonitorStorageTracker.onTopologyChanged((MEStorage) (Object) this);
        StorageGraphManager.onTopologyChanged((MEStorage) (Object) this);
    }

    @Inject(method = "unmount", at = @At("RETURN"), remap = false)
    private void gtlcore$bumpThroughputTopologyOnUnmount(MEStorage storage, CallbackInfo ci) {
        gtlcore$throughputTopologyVersion++;
        ThroughputMonitorStorageTracker.onTopologyChanged((MEStorage) (Object) this);
        StorageGraphManager.onTopologyChanged((MEStorage) (Object) this);
        SourceRegistry.onRemoved(storage);
    }

    @Inject(method = "getAvailableStacks", at = @At("HEAD"), remap = false)
    private void gtlcore$observeStorageGraph(KeyCounter out, CallbackInfo ci) {
        StorageGraphManager.onAccess((MEStorage) (Object) this);
    }

    @WrapMethod(method = "getAvailableStacks", remap = false)
    private void gtlcore$shadowAvailableStacks(KeyCounter out, Operation<Void> original) {
        StorageGraphMode mode = StorageGraphManager.mode();
        if (mode == StorageGraphMode.ON) {
            if (StorageGraphManager.fillUniqueIfExact((MEStorage) (Object) this, out)) {
                return;
            }
            original.call(out);
            return;
        }
        if (mode == StorageGraphMode.OFF) {
            original.call(out);
            return;
        }
        long legacyBefore = StorageGraphManager.keyCounterTotal(out);
        StorageGraphManager.logMounts(priorityInventory);
        StorageGraphManager.enterNested();
        try {
            original.call(out);
        } finally {
            StorageGraphManager.exitNested();
        }
        long legacyAfter = StorageGraphManager.keyCounterTotal(out);
        StorageGraphManager.shadowAvailableStacks((MEStorage) (Object) this, out, legacyBefore, legacyAfter);
    }

    @WrapMethod(method = "insert", remap = false)
    private long gtlcore$trackInsert(AEKey what, long amount, Actionable mode, IActionSource source,
                                     Operation<Long> original) {
        if (mode == Actionable.SIMULATE) {
            StorageOperationContext context = StorageOperationContext.current();
            boolean ownContext = false;
            if (context == null && StorageGraphManager.mode() == StorageGraphMode.ON) {
                StorageGraphSnapshot snapshot = StorageGraphManager.snapshot((MEStorage) (Object) this);
                context = StorageOperationContext.begin(mode, source,
                        snapshot == null ? 0L : snapshot.topologyEpoch());
                ownContext = true;
            }
            try {
                long legacy = original.call(what, amount, mode, source);
                StorageGraphManager.shadowInsert((MEStorage) (Object) this, what, amount, mode, source, legacy);
                return legacy;
            } finally {
                if (ownContext) {
                    StorageOperationContext.end(context);
                }
            }
        }
        if (mode == Actionable.MODULATE && StorageGraphManager.mode() == StorageGraphMode.ON && StorageOperationContext.current() == null) {
            StorageGraphSnapshot snapshot = StorageGraphManager.snapshot((MEStorage) (Object) this);
            StorageOperationContext context = StorageOperationContext.begin(mode, source,
                    snapshot == null ? 0L : snapshot.topologyEpoch());
            try {
                long inserted = original.call(what, amount, mode, source);
                if (inserted > 0L && ThroughputMonitorStorageTracker.isTrackingActive()) {
                    ThroughputMonitorStorageTracker.beginInsert((MEStorage) (Object) this, source);
                    ThroughputMonitorStorageTracker.endInsert((MEStorage) (Object) this, what, inserted, source);
                }
                return inserted;
            } finally {
                StorageOperationContext.end(context);
            }
        }
        if (mode != Actionable.MODULATE || !ThroughputMonitorStorageTracker.isTrackingActive()) {
            return original.call(what, amount, mode, source);
        }
        StorageGraphManager.InsertProbe insertProbe = StorageGraphManager.probeInsertUnique(
                (MEStorage) (Object) this, what, amount, source);
        ThroughputMonitorStorageTracker.beginInsert((MEStorage) (Object) this, source);
        long inserted;
        try {
            inserted = original.call(what, amount, mode, source);
            ThroughputMonitorStorageTracker.endInsert((MEStorage) (Object) this, what, inserted, source);
        } catch (RuntimeException | Error failure) {
            ThroughputMonitorStorageTracker.abortOperation();
            throw failure;
        }
        StorageGraphManager.logInsert(inserted, insertProbe, what, amount);
        return inserted;
    }

    @WrapMethod(method = "extract", remap = false)
    private long gtlcore$trackExtraction(AEKey what, long amount, Actionable mode, IActionSource source,
                                         Operation<Long> original) {
        if (mode == Actionable.SIMULATE) {
            StorageOperationContext context = StorageOperationContext.current();
            boolean ownContext = false;
            if (context == null && StorageGraphManager.mode() == StorageGraphMode.ON) {
                StorageGraphSnapshot snapshot = StorageGraphManager.snapshot((MEStorage) (Object) this);
                context = StorageOperationContext.begin(mode, source,
                        snapshot == null ? 0L : snapshot.topologyEpoch());
                ownContext = true;
            }
            try {
                StorageGraphManager.enterNested();
                long legacy;
                try {
                    legacy = NetworkExtractionSimulation.network(this, what,
                            () -> original.call(what, amount, mode, source));
                } finally {
                    StorageGraphManager.exitNested();
                }
                if (context != null && legacy > 0L) {
                    context.recordNetworkExtract(SourceRegistry.idFor((MEStorage) (Object) this).runtimeId(), what, legacy);
                }
                StorageGraphManager.shadowSimulate((MEStorage) (Object) this, what, amount, source, legacy);
                return legacy;
            } finally {
                if (ownContext) {
                    StorageOperationContext.end(context);
                }
            }
        }
        if (mode == Actionable.MODULATE && StorageGraphManager.mode() == StorageGraphMode.ON && StorageOperationContext.current() == null) {
            StorageGraphSnapshot snapshot = StorageGraphManager.snapshot((MEStorage) (Object) this);
            StorageOperationContext context = StorageOperationContext.begin(mode, source,
                    snapshot == null ? 0L : snapshot.topologyEpoch());
            try {
                long limitedAmount = ManualCraftingInventoryLock.limitExtraction((MEStorage) (Object) this, what, amount, source);
                long extracted = original.call(what, limitedAmount, mode, source);
                if (extracted > 0L && ThroughputMonitorStorageTracker.isTrackingActive()) {
                    ThroughputMonitorStorageTracker.beginExtraction((MEStorage) (Object) this, source);
                    ThroughputMonitorStorageTracker.endExtraction((MEStorage) (Object) this, what, extracted, source);
                }
                return extracted;
            } finally {
                StorageOperationContext.end(context);
            }
        }
        if (mode != Actionable.MODULATE || !ThroughputMonitorStorageTracker.isTrackingActive()) {
            return original.call(what, amount, mode, source);
        }
        ThroughputMonitorStorageTracker.beginExtraction((MEStorage) (Object) this, source);
        long extracted;
        try {
            extracted = original.call(what, amount, mode, source);
            ThroughputMonitorStorageTracker.endExtraction((MEStorage) (Object) this, what, extracted, source);
        } catch (RuntimeException | Error failure) {
            ThroughputMonitorStorageTracker.abortOperation();
            throw failure;
        }
        return extracted;
    }

    @WrapOperation(method = "extract",
                   remap = false,
                   at = @At(value = "INVOKE", target = "Lappeng/api/storage/MEStorage;extract(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;Lappeng/api/networking/security/IActionSource;)J"))
    private long gtlcore$simulateMountedExtraction(MEStorage storage, AEKey what, long amount, Actionable mode,
                                                   IActionSource source, Operation<Long> original) {
        if (mode != Actionable.SIMULATE) {
            return original.call(storage, what, amount, mode, source);
        }
        if (ExternalInventoryIdentity.get(storage) != 0L) {
            return original.call(storage, what, amount, mode, source);
        }
        return NetworkExtractionSimulation.mounted(storage, what, amount,
                requested -> original.call(storage, what, requested, mode, source));
    }

    @ModifyVariable(method = "extract", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private long gtlcore$limitExtractionToUnlockedInventory(long amount, AEKey what, long requested,
                                                            Actionable mode, IActionSource source) {
        return ManualCraftingInventoryLock.limitExtraction((MEStorage) (Object) this, what, amount, source);
    }

    @Override
    public Collection<MEStorage> gtlcore$getChildStorages() {
        List<MEStorage> storages = new ArrayList<>();
        for (List<MEStorage> priorityStorages : priorityInventory.values()) {
            storages.addAll(priorityStorages);
        }
        return storages;
    }

    @Override
    public NavigableMap<Integer, List<MEStorage>> gtlcore$getPriorityStorages() {
        return priorityInventory;
    }

    @Override
    public long gtlcore$getAvailableAmount(AEKey what, IActionSource source) {
        return NetworkExtractionSimulation.isolated(() -> NetworkExtractionSimulation.network(
                () -> gtlcore$simulateAvailableAmount(what, source)));
    }

    @Unique
    private long gtlcore$simulateAvailableAmount(AEKey what, IActionSource source) {
        long available = 0;
        for (List<MEStorage> priorityStorages : priorityInventory.values()) {
            for (MEStorage storage : priorityStorages) {
                long remaining = Long.MAX_VALUE - available;
                long stored = NetworkExtractionSimulation.mounted(storage, what, remaining,
                        requested -> storage.extract(what, requested, Actionable.SIMULATE, source));
                if (stored >= remaining) {
                    return Long.MAX_VALUE;
                }
                available += stored;
            }
        }
        return available;
    }

    @Override
    public long gtlcore$getTopologyVersion() {
        return gtlcore$throughputTopologyVersion;
    }
}
