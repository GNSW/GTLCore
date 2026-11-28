package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import appeng.api.storage.MEStorage;

import java.util.List;

public interface StorageGraphAdapter {

    enum Recognition {
        NETWORK_ROOT,
        TRANSPARENT_DELEGATE,
        FILTER_WRAPPER,
        TYPED_COMPOSITE,
        FINITE_LEAF,
        INFINITE,
        VOID,
        OPAQUE
    }

    record ChildEdge(MEStorage child, EdgeKind kind, PolicySnapshot policy, int nativeOrder, long externalKey) {}

    Recognition recognize(MEStorage storage);

    List<ChildEdge> children(MEStorage storage);

    SourceKind sourceKind(MEStorage storage);

    /**
     * 容器节点（如空 NetworkStorage）即使没有子节点也不是物理来源。
     */
    default boolean isPhysicalSource(MEStorage storage) {
        return true;
    }
}
