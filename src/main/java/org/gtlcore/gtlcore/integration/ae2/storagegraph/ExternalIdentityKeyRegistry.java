package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ExternalIdentityKeyRegistry {

    private static final Map<Object, Long> IDENTITIES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicLong NEXT = new AtomicLong(1L);

    private ExternalIdentityKeyRegistry() {}

    public static long intern(Object owner) {
        if (owner == null) {
            return 0L;
        }
        synchronized (IDENTITIES) {
            Long existing = IDENTITIES.get(owner);
            if (existing != null) {
                return existing;
            }
            long id = NEXT.getAndIncrement();
            IDENTITIES.put(owner, id);
            return id;
        }
    }

    public static void clear() {
        synchronized (IDENTITIES) {
            IDENTITIES.clear();
        }
    }
}
