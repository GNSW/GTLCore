package org.gtlcore.gtlcore.integration.ae2.storage;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;

/** Virtual depletion shared by all network branches of one simulated extraction. */
public final class NetworkExtractionSimulation {

    private static final ThreadLocal<Context> ACTIVE = new ThreadLocal<>();

    private NetworkExtractionSimulation() {}

    public static long network(Object storage, Object key, LongSupplier extraction) {
        return network(() -> {
            long result = extraction.getAsLong();
            if (result > 0) {
                var amounts = ACTIVE.get().networks.computeIfAbsent(storage, ignored -> new HashMap<>());
                long previous = amounts.getOrDefault(key, 0L);
                amounts.put(key, result > Long.MAX_VALUE - previous ? Long.MAX_VALUE : result + previous);
            }
            return result;
        });
    }

    /** Already simulated through this network, for its own crafting-reservation limit. */
    public static long extractedFromNetwork(Object storage, Object key) {
        Context context = ACTIVE.get();
        if (context == null) {
            return 0;
        }
        var amounts = context.networks.get(storage);
        return amounts == null ? 0 : amounts.getOrDefault(key, 0L);
    }

    public static long network(LongSupplier extraction) {
        Context context = ACTIVE.get();
        boolean root = context == null;
        if (root) {
            context = new Context();
            ACTIVE.set(context);
        }
        context.networkVisits++;
        try {
            return extraction.getAsLong();
        } finally {
            if (root) {
                ACTIVE.remove();
            }
        }
    }

    /**
     * Keep ae2's native access/filter checks. Only terminal mounts consume the virtual
     * budget: a mount delegating to another network is accounted at that network's
     * mounts, otherwise a diamond-shaped graph would subtract the same IO twice.
     * Keys use value equality; inventories must use identity, not equals().
     */
    public static long mounted(Object storage, Object key, long requested, LongUnaryOperator extraction) {
        Context context = ACTIVE.get();
        if (context == null || requested <= 0) {
            return extraction.applyAsLong(requested);
        }
        Map<Object, Long> amounts = context.extracted.get(storage);
        long used = amounts == null ? 0 : amounts.getOrDefault(key, 0L);
        long probe = requested > Long.MAX_VALUE - used ? Long.MAX_VALUE : requested + used;
        long visits = context.networkVisits;
        long available = extraction.applyAsLong(probe);
        if (context.networkVisits != visits) {
            return available;
        }
        long extracted = available <= used ? 0 : Math.min(requested, available - used);
        if (extracted > 0) {
            if (amounts == null) {
                amounts = new HashMap<>();
                context.extracted.put(storage, amounts);
            }
            amounts.put(key, used + extracted);
        }
        return extracted;
    }

    /** A reservation availability probe must neither consume nor inherit the caller's budget. */
    public static long isolated(LongSupplier query) {
        Context previous = ACTIVE.get();
        ACTIVE.remove();
        try {
            return query.getAsLong();
        } finally {
            if (previous != null) {
                ACTIVE.set(previous);
            }
        }
    }

    private static final class Context {

        private final Map<Object, Map<Object, Long>> extracted = new IdentityHashMap<>();
        private final Map<Object, Map<Object, Long>> networks = new IdentityHashMap<>();
        private long networkVisits;
    }
}
