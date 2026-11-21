package org.gtlcore.gtlcore.integration.ae2.storagegraph;

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
                                   boolean complete) {

    public StorageGraphSnapshot {
        edges = List.copyOf(edges);
        sources = List.copyOf(sources);
    }
}
