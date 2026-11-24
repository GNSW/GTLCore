package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import appeng.api.storage.MEStorage;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Canonical identity of an external inventory behind a storage bus. Only inventories whose physical
 * container can be proven (currently the block adjacent to a storage bus) are registered here; an
 * unregistered wrapper keeps its OPAQUE fallback.
 */
public final class ExternalInventoryIdentity {

    private static final Map<MEStorage, Long> IDENTITIES = Collections.synchronizedMap(new WeakHashMap<>());

    private ExternalInventoryIdentity() {}

    public static void put(MEStorage storage, long identity) {
        if (storage != null && identity != 0L) {
            IDENTITIES.put(storage, identity);
        }
    }

    public static long get(MEStorage storage) {
        return storage == null ? 0L : IDENTITIES.getOrDefault(storage, 0L);
    }
}
