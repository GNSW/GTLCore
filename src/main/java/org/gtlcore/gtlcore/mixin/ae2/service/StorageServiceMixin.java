package org.gtlcore.gtlcore.mixin.ae2.service;

import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.InventorySnapshot;
import org.gtlcore.gtlcore.integration.ae2.InventorySnapshotVersion;
import org.gtlcore.gtlcore.utils.NumberUtils;

import appeng.api.networking.storage.IStorageWatcherNode;
import appeng.api.stacks.AEKey;
import appeng.hooks.ticking.TickHandler;
import appeng.me.helpers.InterestManager;
import appeng.me.helpers.StackWatcher;
import appeng.me.service.StorageService;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 代码参考自gto
 * &#064;line <a href="https://github.com/GregTech-Odyssey/GTOCore">...</a>
 */

@Mixin(StorageService.class)
public abstract class StorageServiceMixin implements InventorySnapshotVersion {

    @Unique
    private long gtlcore$inventorySnapshotVersion;
    @Unique
    private final InventorySnapshot.Publisher gtlcore$snapshots = new InventorySnapshot.Publisher();
    @Unique
    private InventorySnapshot.Changes gtlcore$pendingChanges;

    @Override
    public long gtlcore$inventorySnapshotVersion() {
        return gtlcore$inventorySnapshotVersion;
    }

    @Override
    public InventorySnapshot gtlcore$inventorySnapshot() {
        return gtlcore$snapshots.current();
    }

    @WrapMethod(method = "updateCachedStacks", remap = false)
    private void gtlcore$publishInventory(Operation<Void> original) {
        var changes = new InventorySnapshot.Changes();
        gtlcore$pendingChanges = changes;
        try {
            original.call();
            gtlcore$snapshots.publish(changes);
            gtlcore$inventorySnapshotVersion++;
        } catch (RuntimeException | Error failure) {
            // Failed enumeration must not publish partial stock or suppress retry.
            cachedStacksNeedUpdate = true;
            throw failure;
        } finally {
            gtlcore$pendingChanges = null;
        }
        // A watcher can submit a new order reentrantly. Publish before calling
        // it, after native inventory and quantity bookkeeping are both complete.
        changes.forEach(this::postWatcherUpdate);
    }

    @Redirect(method = "updateCachedStacks",
              at = @At(value = "INVOKE", target = "Lappeng/me/service/StorageService;postWatcherUpdate(Lappeng/api/stacks/AEKey;J)V"),
              remap = false)
    private void gtlcore$recordInventoryChange(StorageService service, AEKey key, long amount) {
        gtlcore$pendingChanges.add(key, amount);
    }

    @Shadow(remap = false)
    private void postWatcherUpdate(AEKey key, long amount) {
        throw new AssertionError();
    }

    @Shadow(remap = false)
    @Final
    @Mutable
    private final InterestManager<StackWatcher<IStorageWatcherNode>> interestManager;
    @Shadow(remap = false)
    private boolean cachedStacksNeedUpdate;

    @Shadow(remap = false)
    protected abstract void updateCachedStacks();

    @Unique
    private static final int STORAGE_MASK = NumberUtils.nearestPow2Lookup(ConfigHolder.INSTANCE.performance.ae2StorageServiceUpdateInterval) - 1;

    public StorageServiceMixin(InterestManager<StackWatcher<IStorageWatcherNode>> interestManager) {
        this.interestManager = interestManager;
    }

    /**
     * @author .
     * @reason 减少更新频率
     */
    @Overwrite(remap = false)
    public void onServerEndTick() {
        if (this.interestManager.isEmpty()) {
            this.cachedStacksNeedUpdate = true;
        } else {
            if ((TickHandler.instance().getCurrentTick() & STORAGE_MASK) == 0) this.updateCachedStacks();
        }
    }
}
