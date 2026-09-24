package org.gtlcore.gtlcore.mixin.ae2.service;

import org.gtlcore.gtlcore.integration.ae2.graph.GraphStorageWatch;

import appeng.api.networking.storage.IStorageWatcherNode;
import appeng.api.stacks.AEKey;
import appeng.me.helpers.InterestManager;
import appeng.me.helpers.StackWatcher;
import appeng.me.service.StorageService;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;

@Mixin(value = StorageService.class, remap = false)
public abstract class GraphStorageServiceMixin implements GraphStorageWatch {

    @Shadow
    @Final
    private InterestManager<StackWatcher<IStorageWatcherNode>> interestManager;

    @Override
    public Subscription gtlcore$watchGraphResources(Set<AEKey> keys, Runnable changed) {
        return GraphStorageWatch.subscribe(interestManager, keys, changed);
    }
}
