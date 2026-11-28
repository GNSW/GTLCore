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
    private static final Map<Object, Long> VALUES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<PairKey, Long> PAIRS = Collections.synchronizedMap(new HashMap<>());
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

    public static long internValue(Object value) {
        if (value == null) {
            return 0L;
        }
        synchronized (VALUES) {
            Long existing = VALUES.get(value);
            if (existing != null) {
                return existing;
            }
            long id = NEXT.getAndIncrement();
            VALUES.put(value, id);
            return id;
        }
    }

    public static long internView(long ownerId, AEKeyType keyType, List<?> traits) {
        if (keyType == null) {
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

    public static long internPair(Object first, Object second, AEKeyType keyType) {
        long a = intern(first);
        long b = intern(second);
        if (a == 0L || b == 0L || keyType == null) {
            return 0L;
        }
        PairKey key = new PairKey(Math.min(a, b), Math.max(a, b), keyType);
        synchronized (PAIRS) {
            Long existing = PAIRS.get(key);
            if (existing != null) {
                return existing;
            }
            long id = NEXT.getAndIncrement();
            PAIRS.put(key, id);
            return id;
        }
    }

    public static void cleanup() {
        synchronized (OWNERS) {
            java.util.Set<Long> live = new java.util.HashSet<>(OWNERS.values());
            synchronized (VIEWS) {
                VIEWS.keySet().removeIf(key -> key.ownerId() != 0L && !live.contains(key.ownerId()));
            }
            synchronized (PAIRS) {
                PAIRS.keySet().removeIf(pair -> !live.contains(pair.first()) || !live.contains(pair.second()));
            }
        }
    }

    public static void clear() {
        synchronized (OWNERS) {
            OWNERS.clear();
        }
        synchronized (VALUES) {
            VALUES.clear();
        }
        synchronized (PAIRS) {
            PAIRS.clear();
        }
        synchronized (VIEWS) {
            VIEWS.clear();
        }
    }

    public record ViewKey(long ownerId, AEKeyType keyType, List<Integer> signature) {}

    public record PairKey(long first, long second, AEKeyType keyType) {}
}
