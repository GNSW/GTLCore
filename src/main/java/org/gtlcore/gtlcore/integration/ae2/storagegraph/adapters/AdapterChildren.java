package org.gtlcore.gtlcore.integration.ae2.storagegraph.adapters;

import org.gtlcore.gtlcore.integration.ae2.storagegraph.EdgeKind;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.PolicySnapshot;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.StorageGraphAdapter;
import org.gtlcore.gtlcore.integration.ae2.throughput.ThroughputStorageView;

import appeng.api.storage.MEStorage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class AdapterChildren {

    private AdapterChildren() {}

    static List<StorageGraphAdapter.ChildEdge> of(MEStorage storage, EdgeKind kind) {
        if (!(storage instanceof ThroughputStorageView view)) {
            return List.of();
        }
        Collection<MEStorage> children = view.gtlcore$getChildStorages();
        if (children == null || children.isEmpty()) {
            return List.of();
        }
        List<StorageGraphAdapter.ChildEdge> result = new ArrayList<>(children.size());
        int order = 0;
        for (MEStorage child : children) {
            if (child == null) {
                continue;
            }
            result.add(new StorageGraphAdapter.ChildEdge(child, kind, PolicySnapshot.unknown(), order++, 0L));
        }
        return result;
    }
}
