package org.gtlcore.gtlcore.integration.ae2.storagegraph.adapters;

import org.gtlcore.gtlcore.integration.ae2.storagegraph.ExternalInventoryIdentity;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.SourceKind;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.StorageGraphAdapter;

import appeng.api.storage.MEStorage;

import java.util.List;

public final class OpaqueStorageAdapter implements StorageGraphAdapter {

    @Override
    public Recognition recognize(MEStorage storage) {
        return Recognition.OPAQUE;
    }

    @Override
    public List<ChildEdge> children(MEStorage storage) {
        return List.of();
    }

    @Override
    public SourceKind sourceKind(MEStorage storage) {
        if (ExternalInventoryIdentity.isInfinite(storage)) {
            return SourceKind.INFINITE;
        }
        if (ExternalInventoryIdentity.get(storage) != 0L) {
            return SourceKind.FINITE;
        }
        if (storage.getClass().getName().contains("CraftingServiceStorage")) {
            return SourceKind.VIRTUAL;
        }
        return SourceKind.OPAQUE;
    }
}
