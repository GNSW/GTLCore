package org.gtlcore.gtlcore.integration.ae2.storagegraph.adapters;

import org.gtlcore.gtlcore.integration.ae2.storagegraph.EdgeKind;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.ExternalInventoryIdentity;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.PolicySnapshot;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.SourceKind;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.StorageGraphAdapter;
import org.gtlcore.gtlcore.integration.ae2.throughput.ThroughputStorageView;

import appeng.api.storage.MEStorage;
import appeng.me.storage.MEInventoryHandler;

import java.util.ArrayList;
import java.util.List;

public final class StorageBusAdapter implements StorageGraphAdapter {

    @Override
    public Recognition recognize(MEStorage storage) {
        if (!(storage instanceof MEInventoryHandler)) {
            return Recognition.OPAQUE;
        }
        return storage.getClass().getName().contains("StorageBusInventory") ? Recognition.FILTER_WRAPPER : Recognition.OPAQUE;
    }

    @Override
    public List<ChildEdge> children(MEStorage storage) {
        long externalKey = ExternalInventoryIdentity.get(storage);
        if (externalKey == 0L || !(storage instanceof ThroughputStorageView view)) {
            return AdapterChildren.of(storage, EdgeKind.DELEGATE);
        }
        List<ChildEdge> result = new ArrayList<>();
        int order = 0;
        for (MEStorage child : view.gtlcore$getChildStorages()) {
            if (child == null) {
                continue;
            }
            result.add(new ChildEdge(child, EdgeKind.DELEGATE, PolicySnapshot.unknown(), order++, externalKey));
        }
        return result;
    }

    @Override
    public SourceKind sourceKind(MEStorage storage) {
        return SourceKind.OPAQUE;
    }
}
