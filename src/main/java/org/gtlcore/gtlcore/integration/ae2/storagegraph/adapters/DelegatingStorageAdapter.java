package org.gtlcore.gtlcore.integration.ae2.storagegraph.adapters;

import org.gtlcore.gtlcore.integration.ae2.storagegraph.EdgeKind;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.SourceKind;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.StorageGraphAdapter;

import appeng.api.storage.MEStorage;
import appeng.me.storage.DelegatingMEInventory;
import appeng.me.storage.MEInventoryHandler;

import java.util.List;

public final class DelegatingStorageAdapter implements StorageGraphAdapter {

    @Override
    public Recognition recognize(MEStorage storage) {
        if (storage instanceof MEInventoryHandler) {
            return Recognition.OPAQUE;
        }
        return storage instanceof DelegatingMEInventory ? Recognition.TRANSPARENT_DELEGATE : Recognition.OPAQUE;
    }

    @Override
    public List<ChildEdge> children(MEStorage storage) {
        return AdapterChildren.of(storage, EdgeKind.DELEGATE);
    }

    @Override
    public SourceKind sourceKind(MEStorage storage) {
        return SourceKind.OPAQUE;
    }
}
