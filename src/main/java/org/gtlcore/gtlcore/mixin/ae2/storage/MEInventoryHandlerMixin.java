package org.gtlcore.gtlcore.mixin.ae2.storage;

import org.gtlcore.gtlcore.integration.ae2.storagegraph.MEInventoryPolicyView;

import appeng.api.config.IncludeExclude;
import appeng.api.stacks.AEKey;
import appeng.me.storage.MEInventoryHandler;
import appeng.util.prioritylist.IPartitionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(MEInventoryHandler.class)
public abstract class MEInventoryHandlerMixin implements MEInventoryPolicyView {

    @Shadow(remap = false)
    private IPartitionList partitionList;
    @Shadow(remap = false)
    private IncludeExclude partitionListMode;
    @Shadow(remap = false)
    private boolean filterOnExtraction;
    @Shadow(remap = false)
    private boolean filterAvailableContents;
    @Shadow(remap = false)
    private boolean allowExtraction;
    @Shadow(remap = false)
    private boolean allowInsertion;

    @Override
    public boolean gtlcore$allowsExtract(AEKey key) {
        return !filterOnExtraction || (allowExtraction && gtlcore$passesFilter(key));
    }

    @Override
    public boolean gtlcore$allowsInsert(AEKey key) {
        return allowInsertion && gtlcore$passesFilter(key);
    }

    @Override
    public boolean gtlcore$isAvailable(AEKey key) {
        return !filterAvailableContents || (allowExtraction && gtlcore$passesFilter(key));
    }

    @Unique
    private boolean gtlcore$passesFilter(AEKey key) {
        return partitionList == null || partitionList.isEmpty() || partitionList.matchesFilter(key, partitionListMode);
    }
}
