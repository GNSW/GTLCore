package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import appeng.api.stacks.AEKeyType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ExternalIdentityKeyRegistry {

    private static final Map<Object, Long> OWNERS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ViewKey, Long> VIEWS = Collections.synchronizedMap(new HashMap<>());
    private static final AtomicLong NEXT = new AtomicLong(1L);

    private ExternalIdentityKeyRegistry() {}

    public static long intern(Object owner) {
        if (owner == null) {
            return 0L;
        }
        synchronized (OWNERS) {
            Long existing = OWNERS.get(owner);
            if (existing != null) {
                return existing;
            }
            long id = NEXT.getAndIncrement();
            OWNERS.put(owner, id);
            return id;
        }
    }

    public static long internView(long ownerId, AEKeyType keyType, List<?> traits) {
        if (ownerId == 0L || keyType == null) {
            return 0L;
        }
        List<Integer> signature = new ArrayList<>(traits.size());
        for (Object trait : traits) {
            signature.add(System.identityHashCode(trait));
        }
        ViewKey key = new ViewKey(ownerId, keyType, List.copyOf(signature));
        synchronized (VIEWS) {
            Long existing = VIEWS.get(key);
            if (existing != null) {
                return existing;
            }
            long id = NEXT.getAndIncrement();
            VIEWS.put(key, id);
            return id;
        }
    }

    public static void clear() {
        synchronized (OWNERS) {
            OWNERS.clear();
        }
        synchronized (VIEWS) {
            VIEWS.clear();
        }
    }

    public record ViewKey(long ownerId, AEKeyType keyType, List<Integer> signature) {}
}
