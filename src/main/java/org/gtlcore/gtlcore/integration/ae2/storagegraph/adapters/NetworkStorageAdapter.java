package org.gtlcore.gtlcore.integration.ae2.storagegraph.adapters;

import org.gtlcore.gtlcore.integration.ae2.storagegraph.DirectionMode;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.EdgeKind;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.PolicySnapshot;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.SourceKind;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.StorageGraphAdapter;
import org.gtlcore.gtlcore.integration.ae2.throughput.ThroughputStorageView;

import appeng.api.storage.MEStorage;
import appeng.me.storage.NetworkStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;

public final class NetworkStorageAdapter implements StorageGraphAdapter {

    @Override
    public Recognition recognize(MEStorage storage) {
        return storage instanceof NetworkStorage ? Recognition.NETWORK_ROOT : Recognition.OPAQUE;
    }

    @Override
    public List<ChildEdge> children(MEStorage storage) {
        if (storage instanceof ThroughputStorageView view) {
            NavigableMap<Integer, List<MEStorage>> priorityStorages = view.gtlcore$getPriorityStorages();
            if (priorityStorages != null && !priorityStorages.isEmpty()) {
                List<ChildEdge> result = new ArrayList<>();
                int order = 0;
                for (var entry : priorityStorages.entrySet()) {
                    for (MEStorage child : entry.getValue()) {
                        if (child == null) {
                            continue;
                        }
                        result.add(new ChildEdge(child, EdgeKind.LOCAL_MOUNT,
                                new PolicySnapshot(DirectionMode.BOTH, entry.getKey(), "", false, false, 0),
                                order++, 0L));
                    }
                }
                return result;
            }
        }
        return AdapterChildren.of(storage, EdgeKind.LOCAL_MOUNT);
    }

    @Override
    public SourceKind sourceKind(MEStorage storage) {
        return SourceKind.OPAQUE;
    }

    @Override
    public boolean isPhysicalSource(MEStorage storage) {
        return false;
    }
}
