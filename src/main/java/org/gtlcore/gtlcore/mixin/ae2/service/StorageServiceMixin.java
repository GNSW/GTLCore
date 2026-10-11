package org.gtlcore.gtlcore.mixin.ae2.service;

import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.InventorySnapshotVersion;
import org.gtlcore.gtlcore.utils.NumberUtils;

import appeng.api.networking.storage.IStorageWatcherNode;
import appeng.hooks.ticking.TickHandler;
import appeng.me.helpers.InterestManager;
import appeng.me.helpers.StackWatcher;
import appeng.me.service.StorageService;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 代码参考自gto
 * &#064;line <a href="https://github.com/GregTech-Odyssey/GTOCore">...</a>
 */

@Mixin(StorageService.class)
public abstract class StorageServiceMixin implements InventorySnapshotVersion {

    @Unique
    private long gtlcore$inventorySnapshotVersion;

    @Override
    public long gtlcore$inventorySnapshotVersion() {
        return gtlcore$inventorySnapshotVersion;
    }

    @Inject(method = "updateCachedStacks", at = @At("RETURN"), remap = false)
    private void gtlcore$inventorySampled(CallbackInfo ci) {
        gtlcore$inventorySnapshotVersion++;
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
