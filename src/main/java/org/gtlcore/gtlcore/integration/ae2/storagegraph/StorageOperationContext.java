package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;

import java.util.HashMap;
import java.util.IdentityHashMap;
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
    private final Map<SourceId, Map<AEKey, Long>> simulatedExtract = new HashMap<>();
    private final Map<SourceId, Map<AEKey, Long>> simulatedInsert = new HashMap<>();
    private final Map<MEStorage, Map<AEKey, Long>> simulatedTargetExtract = new IdentityHashMap<>();
    private final Map<MEStorage, Map<AEKey, Long>> simulatedTargetInsert = new IdentityHashMap<>();
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

    public long simulateExtractBudget(SourceId source, AEKey key, long requested) {
        return simulateBudget(simulatedExtract, source, key, requested);
    }

    public void recordSimulatedExtract(SourceId source, AEKey key, long amount) {
        recordSimulated(simulatedExtract, source, key, amount);
    }

    public long simulateInsertBudget(SourceId source, AEKey key, long requested) {
        return simulateBudget(simulatedInsert, source, key, requested);
    }

    public void recordSimulatedInsert(SourceId source, AEKey key, long amount) {
        recordSimulated(simulatedInsert, source, key, amount);
    }

    public long simulateTargetExtractBudget(MEStorage target, AEKey key, long requested) {
        return simulateBudget(simulatedTargetExtract, target, key, requested);
    }

    public void recordSimulatedTargetExtract(MEStorage target, AEKey key, long amount) {
        recordSimulated(simulatedTargetExtract, target, key, amount);
    }

    public long simulateTargetInsertBudget(MEStorage target, AEKey key, long requested) {
        return simulateBudget(simulatedTargetInsert, target, key, requested);
    }

    public void recordSimulatedTargetInsert(MEStorage target, AEKey key, long amount) {
        recordSimulated(simulatedTargetInsert, target, key, amount);
    }

    private static <K> long simulateBudget(Map<K, Map<AEKey, Long>> simulated,
                                           K source, AEKey key, long requested) {
        Map<AEKey, Long> byKey = simulated.get(source);
        if (byKey != null && byKey.getOrDefault(key, 0L) > 0L) {
            return 0L;
        }
        return requested;
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
