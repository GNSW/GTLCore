package org.gtlcore.gtlcore.integration.ae2.storagegraph.adapters;

import org.gtlcore.gtlcore.integration.ae2.storagegraph.EdgeKind;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.SourceKind;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.StorageGraphAdapter;

import appeng.api.storage.MEStorage;
import appeng.me.storage.NetworkStorage;

import java.util.List;

public final class NetworkStorageAdapter implements StorageGraphAdapter {

    @Override
    public Recognition recognize(MEStorage storage) {
        return storage instanceof NetworkStorage ? Recognition.NETWORK_ROOT : Recognition.OPAQUE;
    }

    @Override
    public List<ChildEdge> children(MEStorage storage) {
        return AdapterChildren.of(storage, EdgeKind.LOCAL_MOUNT);
    }

    @Override
    public SourceKind sourceKind(MEStorage storage) {
        return SourceKind.OPAQUE;
    }
}
