package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-operation state shared by one routed storage graph operation. The context is scoped to the
 * calling thread with a stack-like reuse rule: a nested operation (a sub-network calling back into
 * the same graph) reuses the outer context instead of planning again, and the context is always
 * cleared in a finally block.
 */
public final class StorageOperationContext {

    private static final ThreadLocal<StorageOperationContext> CURRENT = new ThreadLocal<>();

    private final long operationId;
    private final long graphEpoch;
    private final Actionable mode;
    private final IActionSource actionSource;
    private final Map<SourceId, Boolean> touched = new HashMap<>();
    private final Map<SourceId, Long> consumed = new HashMap<>();
    private final Map<SourceId, Long> inserted = new HashMap<>();
    private final Map<Long, Map<AEKey, Long>> simulatedCanonicalExtract = new HashMap<>();
    private final Map<Long, Map<AEKey, Long>> simulatedCanonicalInsert = new HashMap<>();
    private final Map<Long, Map<AEKey, Long>> networkExtract = new HashMap<>();
    private final Map<Long, Map<AEKey, Long>> networkInsert = new HashMap<>();
    private int depth;

    private StorageOperationContext(long operationId, long graphEpoch, Actionable mode, IActionSource actionSource) {
        this.operationId = operationId;
        this.graphEpoch = graphEpoch;
        this.mode = mode;
        this.actionSource = actionSource;
    }

    public static StorageOperationContext current() {
        return CURRENT.get();
    }

    public static long isolated(java.util.function.LongSupplier probe) {
        StorageOperationContext previous = CURRENT.get();
        CURRENT.remove();
        try {
            return probe.getAsLong();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static StorageOperationContext begin(Actionable mode, IActionSource actionSource, long graphEpoch) {
        StorageOperationContext existing = CURRENT.get();
        if (existing != null) {
            existing.depth++;
            return existing;
        }
        StorageOperationContext context = new StorageOperationContext(
                System.nanoTime(), graphEpoch, mode, actionSource);
        context.depth = 1;
        CURRENT.set(context);
        return context;
    }

    public static void end(StorageOperationContext context) {
        if (context == null) {
            return;
        }
        if (--context.depth <= 0 && CURRENT.get() == context) {
            CURRENT.remove();
        }
    }

    public long operationId() {
        return operationId;
    }

    public long graphEpoch() {
        return graphEpoch;
    }

    public Actionable mode() {
        return mode;
    }

    public IActionSource actionSource() {
        return actionSource;
    }

    public boolean touch(SourceId id) {
        return touched.putIfAbsent(id, Boolean.TRUE) == null;
    }

    public boolean visited(SourceId id) {
        return touched.containsKey(id);
    }

    public void addConsumed(SourceId id, long amount) {
        consumed.merge(id, amount, Long::sum);
    }

    public void addInserted(SourceId id, long amount) {
        inserted.merge(id, amount, Long::sum);
    }

    public long consumed(SourceId id) {
        return consumed.getOrDefault(id, 0L);
    }

    public long inserted(SourceId id) {
        return inserted.getOrDefault(id, 0L);
    }

    public long usedCanonicalExtract(long canonical, AEKey key) {
        return used(simulatedCanonicalExtract, canonical, key);
    }

    public void recordUsedCanonicalExtract(long canonical, AEKey key, long amount) {
        recordSimulated(simulatedCanonicalExtract, canonical, key, amount);
    }

    public long usedCanonicalInsert(long canonical, AEKey key) {
        return used(simulatedCanonicalInsert, canonical, key);
    }

    public void recordUsedCanonicalInsert(long canonical, AEKey key, long amount) {
        recordSimulated(simulatedCanonicalInsert, canonical, key, amount);
    }

    public long usedNetworkExtract(long network, AEKey key) {
        return used(networkExtract, network, key);
    }

    public void recordNetworkExtract(long network, AEKey key, long amount) {
        recordSimulated(networkExtract, network, key, amount);
    }

    public long usedNetworkInsert(long network, AEKey key) {
        return used(networkInsert, network, key);
    }

    public void recordNetworkInsert(long network, AEKey key, long amount) {
        recordSimulated(networkInsert, network, key, amount);
    }

    private static <K> long used(Map<K, Map<AEKey, Long>> used, K source, AEKey key) {
        Map<AEKey, Long> byKey = used.get(source);
        return byKey == null ? 0L : byKey.getOrDefault(key, 0L);
    }

    private static <K> void recordSimulated(Map<K, Map<AEKey, Long>> simulated,
                                            K source, AEKey key, long amount) {
        if (amount > 0L) {
            simulated.computeIfAbsent(source, ignored -> new HashMap<>()).merge(key, amount, Long::sum);
        }
    }

    public long totalConsumed() {
        long total = 0L;
        for (long value : consumed.values()) {
            total = StorageGraphManager.saturatingAdd(total, value);
        }
        return total;
    }

    public long totalInserted() {
        long total = 0L;
        for (long value : inserted.values()) {
            total = StorageGraphManager.saturatingAdd(total, value);
        }
        return total;
    }
}
