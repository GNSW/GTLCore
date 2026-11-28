package org.gtlcore.gtlcore.integration.ae2.storagegraph.adapters;

import org.gtlcore.gtlcore.integration.ae2.storagegraph.SourceKind;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.StorageGraphAdapter;

import appeng.api.storage.MEStorage;
import appeng.me.storage.DriveWatcher;

import java.util.List;

public final class DriveWatcherAdapter implements StorageGraphAdapter {

    @Override
    public Recognition recognize(MEStorage storage) {
        return storage instanceof DriveWatcher ? Recognition.FINITE_LEAF : Recognition.OPAQUE;
    }

    @Override
    public List<ChildEdge> children(MEStorage storage) {
        return List.of();
    }

    @Override
    public SourceKind sourceKind(MEStorage storage) {
        return SourceKind.FINITE;
    }
}
