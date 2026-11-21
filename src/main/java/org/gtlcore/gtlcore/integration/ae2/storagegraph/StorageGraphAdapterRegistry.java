package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import org.gtlcore.gtlcore.integration.ae2.storagegraph.adapters.CompositeStorageAdapter;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.adapters.DelegatingStorageAdapter;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.adapters.DriveWatcherAdapter;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.adapters.NetworkStorageAdapter;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.adapters.OpaqueStorageAdapter;
import org.gtlcore.gtlcore.integration.ae2.storagegraph.adapters.StorageBusAdapter;

import appeng.api.storage.MEStorage;

import java.util.List;

public final class StorageGraphAdapterRegistry {

    private static final List<StorageGraphAdapter> ADAPTERS = List.of(
            new DriveWatcherAdapter(),
            new CompositeStorageAdapter(),
            new StorageBusAdapter(),
            new DelegatingStorageAdapter(),
            new NetworkStorageAdapter());

    private static final StorageGraphAdapter FALLBACK = new OpaqueStorageAdapter();

    private StorageGraphAdapterRegistry() {}

    public static StorageGraphAdapter find(MEStorage storage) {
        if (storage != null) {
            for (StorageGraphAdapter adapter : ADAPTERS) {
                if (adapter.recognize(storage) != StorageGraphAdapter.Recognition.OPAQUE) {
                    return adapter;
                }
            }
        }
        return FALLBACK;
    }
}
