package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import appeng.api.storage.MEStorage;

import java.util.List;

public record StorageGraphSnapshot(
                                   long topologyEpoch,
                                   long policyEpoch,
                                   int rawMountCount,
                                   int uniqueSourceCount,
                                   int aliasGroupCount,
                                   int opaqueSourceCount,
                                   int unverifiedAliasCount,
                                   List<Edge> edges,
                                   List<Source> sources,
                                   List<MEStorage> nodes,
                                   GraphStatus status) {

    public StorageGraphSnapshot {
        edges = List.copyOf(edges);
        sources = List.copyOf(sources);
        nodes = List.copyOf(nodes);
    }

    public boolean complete() {
        return status == GraphStatus.COMPLETE;
    }
}
