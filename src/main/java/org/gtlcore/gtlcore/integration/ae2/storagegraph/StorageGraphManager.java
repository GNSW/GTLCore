package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.config.StorageGraphMode;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class StorageGraphManager {

    public static final int MAX_NODES = 4096;
    public static final int MAX_DEPTH = 32;

    private static final IdentityHashMap<MEStorage, StorageGraphSnapshot> CACHE = new IdentityHashMap<>();
    private static final ThreadLocal<Integer> SHADOW_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<IdentityHashMap<MEStorage, StorageGraphSnapshot>> ACCESS_SNAPSHOTS = ThreadLocal
            .withInitial(IdentityHashMap::new);
    private static final long SHADOW_COMPARE_INTERVAL_NANOS = 1_000_000_000L;
    private static long lastShadowCompareNanos;
    private static long topologyEpoch;
    private static long policyEpoch;

    private StorageGraphManager() {}

    public static StorageGraphMode mode() {
        ConfigHolder config = ConfigHolder.INSTANCE;
        return config == null ? StorageGraphMode.OFF : config.ae.ae2StorageGraphMode;
    }

    public static void onTopologyChanged() {
        long epoch;
        synchronized (StorageGraphManager.class) {
            topologyEpoch++;
            CACHE.clear();
            epoch = topologyEpoch;
        }
        StorageGraphDiagnostics.logTopologyChange(epoch);
    }

    public static void onPolicyChanged() {
        long epoch;
        synchronized (StorageGraphManager.class) {
            policyEpoch++;
            CACHE.clear();
            epoch = policyEpoch;
        }
        StorageGraphDiagnostics.logPolicyChange(epoch);
    }

    public static void reset() {
        synchronized (StorageGraphManager.class) {
            topologyEpoch++;
            policyEpoch++;
            CACHE.clear();
        }
        SourceRegistry.reset();
        StorageGraphDiagnostics.resetThrottle();
    }

    public static void enterNested() {
        SHADOW_DEPTH.set(SHADOW_DEPTH.get() + 1);
    }

    public static void exitNested() {
        SHADOW_DEPTH.set(SHADOW_DEPTH.get() - 1);
    }

    public static void onAccess(MEStorage root) {
        if (root == null || mode() != StorageGraphMode.SHADOW) {
            return;
        }
        ACCESS_SNAPSHOTS.get().put(root, collect(root));
    }

    public static StorageGraphSnapshot snapshot(MEStorage root) {
        if (root == null || mode() == StorageGraphMode.OFF) {
            return null;
        }
        synchronized (StorageGraphManager.class) {
            StorageGraphSnapshot cached = CACHE.get(root);
            if (cached != null) {
                return cached;
            }
            StorageGraphSnapshot snapshot = collect(root);
            CACHE.put(root, snapshot);
            return snapshot;
        }
    }

    public static void uniqueAvailableStacks(MEStorage root, KeyCounter out) {
        if (root == null || out == null) {
            return;
        }
        StorageGraphSnapshot snapshot = snapshot(root);
        if (snapshot == null) {
            return;
        }
        for (Source source : snapshot.sources()) {
            MEStorage physical = source.physical();
            if (physical != null) {
                physical.getAvailableStacks(out);
            }
        }
    }

    public static boolean fillUniqueIfExact(MEStorage root, KeyCounter out) {
        if (root == null || out == null || mode() != StorageGraphMode.ON) {
            return false;
        }
        StorageGraphSnapshot snapshot = collect(root);
        KeyCounter unique = new KeyCounter();
        KeyCounter opaque = new KeyCounter();
        for (Source source : snapshot.sources()) {
            if (source.physical() == null) {
                continue;
            }
            if (source.kind() == SourceKind.FINITE || source.kind() == SourceKind.INFINITE) {
                source.physical().getAvailableStacks(unique);
            } else {
                source.physical().getAvailableStacks(opaque);
            }
        }
        if (keyCounterTotal(opaque) > 0L) {
            StorageGraphDiagnostics.logCompatFallback(snapshot.uniqueSourceCount(), snapshot.opaqueSourceCount());
            if (compatFallback()) {
                return false;
            }
        }
        out.addAll(unique);
        return true;
    }

    public static long simulateUniqueIfExact(MEStorage root, AEKey what, long amount, IActionSource source) {
        if (root == null || what == null || mode() != StorageGraphMode.ON) {
            return -1L;
        }
        StorageGraphSnapshot snapshot = collect(root);
        KeyCounter opaque = new KeyCounter();
        long remaining = amount;
        long unique = 0L;
        for (Source graphSource : snapshot.sources()) {
            if (graphSource.physical() == null) {
                continue;
            }
            if (graphSource.kind() == SourceKind.FINITE || graphSource.kind() == SourceKind.INFINITE) {
                if (remaining <= 0L) {
                    continue;
                }
                long got = graphSource.physical().extract(what, remaining, Actionable.SIMULATE, source);
                if (got > 0L) {
                    unique = saturatingAdd(unique, got);
                    remaining -= got;
                }
            } else {
                graphSource.physical().getAvailableStacks(opaque);
            }
        }
        if (keyCounterTotal(opaque) > 0L) {
            StorageGraphDiagnostics.logCompatFallback(snapshot.uniqueSourceCount(), snapshot.opaqueSourceCount());
            if (compatFallback()) {
                return -1L;
            }
        }
        return unique;
    }

    public static long insertUniqueIfExact(MEStorage root, AEKey what, long amount, IActionSource source) {
        if (root == null || what == null || mode() != StorageGraphMode.ON) {
            return -1L;
        }
        StorageGraphSnapshot snapshot = collect(root);
        long remaining = amount;
        long unique = 0L;
        boolean opaqueMayAccept = false;
        for (Source graphSource : snapshot.sources()) {
            if (graphSource.physical() == null) {
                continue;
            }
            if (graphSource.kind() == SourceKind.FINITE || graphSource.kind() == SourceKind.INFINITE) {
                if (remaining <= 0L) {
                    continue;
                }
                long got = graphSource.physical().insert(what, remaining, Actionable.SIMULATE, source);
                if (got > 0L) {
                    unique = saturatingAdd(unique, got);
                    remaining -= got;
                }
            } else if (!opaqueMayAccept && remaining > 0L && graphSource.physical().insert(what, remaining, Actionable.SIMULATE, source) > 0L) {
                opaqueMayAccept = true;
            }
        }
        if (opaqueMayAccept) {
            StorageGraphDiagnostics.logCompatFallback(snapshot.uniqueSourceCount(), snapshot.opaqueSourceCount());
            if (compatFallback()) {
                return -1L;
            }
        }
        return unique;
    }

    private static boolean compatFallback() {
        ConfigHolder config = ConfigHolder.INSTANCE;
        return config == null || config.ae.ae2StorageGraphCompatFallback;
    }

    public record InsertProbe(long unique, int finite, int sources) {}

    public static InsertProbe probeInsertUnique(MEStorage root, AEKey what, long amount, IActionSource source) {
        if (root == null || what == null || mode() != StorageGraphMode.SHADOW) {
            return null;
        }
        StorageGraphSnapshot snapshot = collect(root);
        long unique = 0L;
        int finite = 0;
        for (Source graphSource : snapshot.sources()) {
            if (graphSource.kind() != SourceKind.FINITE || graphSource.physical() == null) {
                continue;
            }
            long got = graphSource.physical().insert(what, amount, Actionable.SIMULATE, source);
            if (got > 0) {
                unique = saturatingAdd(unique, got);
                finite++;
            }
        }
        return new InsertProbe(unique, finite, snapshot.sources().size());
    }

    public static void logInsert(long legacy, InsertProbe probe, AEKey what, long amount) {
        if (probe == null) {
            return;
        }
        StorageGraphDiagnostics.logSimulateInsert(legacy, probe.unique(), probe.sources(), probe.finite(),
                String.valueOf(what), amount);
    }

    public static void shadowInsert(MEStorage root, AEKey what, long amount, Actionable mode,
                                    IActionSource source, long legacy) {
        if (mode != Actionable.SIMULATE) {
            return;
        }
        logInsert(legacy, probeInsertUnique(root, what, amount, source), what, amount);
    }

    public static void shadowAvailableStacks(MEStorage root, KeyCounter legacyOut, long legacyBefore, long legacyAfter) {
        if (root == null || legacyOut == null || mode() != StorageGraphMode.SHADOW || SHADOW_DEPTH.get() != 0) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastShadowCompareNanos < SHADOW_COMPARE_INTERVAL_NANOS) {
            return;
        }
        lastShadowCompareNanos = now;
        SHADOW_DEPTH.set(1);
        try {
            IdentityHashMap<MEStorage, StorageGraphSnapshot> snapshots = ACCESS_SNAPSHOTS.get();
            StorageGraphSnapshot snapshot = snapshots.remove(root);
            if (snapshot == null) {
                snapshot = collect(root);
            }
            if (snapshots.isEmpty()) {
                ACCESS_SNAPSHOTS.remove();
            }
            KeyCounter unique = new KeyCounter();
            StringBuilder perSource = new StringBuilder();
            int shown = 0;
            if (snapshot != null) {
                perSource.append("snap=").append(snapshot.topologyEpoch()).append('/')
                        .append(snapshot.sources().size()).append("; ");
                for (Source source : snapshot.sources()) {
                    MEStorage physical = source.physical();
                    if (physical == null) {
                        continue;
                    }
                    KeyCounter one = new KeyCounter();
                    physical.getAvailableStacks(one);
                    long oneTotal = 0L;
                    for (var entry : one) {
                        oneTotal += entry.getLongValue();
                    }
                    unique.addAll(one);
                    if (shown < 8) {
                        perSource.append(source.kind()).append(':')
                                .append(physical.getClass().getName()).append('@')
                                .append(Integer.toHexString((int) source.identityHash()))
                                .append('=').append(one.size()).append('/').append(oneTotal).append("; ");
                        shown++;
                    }
                }
            }
            long legacyTotal = 0L;
            long uniqueTotal = 0L;
            for (var entry : legacyOut) {
                legacyTotal += entry.getLongValue();
            }
            for (var entry : unique) {
                uniqueTotal += entry.getLongValue();
            }
            int mismatched = 0;
            for (AEKey key : unique.keySet()) {
                if (legacyOut.get(key) != unique.get(key)) {
                    mismatched++;
                }
            }
            for (AEKey key : legacyOut.keySet()) {
                if (unique.get(key) == 0L) {
                    mismatched++;
                }
            }
            StorageGraphDiagnostics.logAvailableStacks(legacyOut.size(), unique.size(), legacyTotal, uniqueTotal,
                    mismatched, legacyBefore, legacyAfter, perSource.toString());
        } finally {
            SHADOW_DEPTH.set(0);
        }
    }

    public static void shadowSimulate(MEStorage root, AEKey what, long amount, IActionSource source, long legacy) {
        if (root == null || what == null || mode() != StorageGraphMode.SHADOW || SHADOW_DEPTH.get() != 0) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastShadowCompareNanos < SHADOW_COMPARE_INTERVAL_NANOS) {
            return;
        }
        lastShadowCompareNanos = now;
        SHADOW_DEPTH.set(1);
        try {
            StorageGraphSnapshot snapshot = collect(root);
            long unique = 0L;
            int finite = 0;
            for (Source graphSource : snapshot.sources()) {
                if (graphSource.kind() != SourceKind.FINITE) {
                    continue;
                }
                MEStorage physical = graphSource.physical();
                if (physical == null) {
                    continue;
                }
                long got = physical.extract(what, amount, Actionable.SIMULATE, source);
                if (got > 0) {
                    unique = saturatingAdd(unique, got);
                    finite++;
                }
            }
            StorageGraphDiagnostics.logSimulate(legacy, unique, snapshot.sources().size(), finite,
                    String.valueOf(what), amount);
        } finally {
            SHADOW_DEPTH.set(0);
        }
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    public static void logMounts(Map<Integer, List<MEStorage>> priorityInventory) {
        if (priorityInventory == null || mode() != StorageGraphMode.SHADOW) {
            return;
        }
        StringBuilder mounts = new StringBuilder();
        int shown = 0;
        for (var entry : priorityInventory.entrySet()) {
            for (MEStorage mount : entry.getValue()) {
                if (shown >= 12) {
                    break;
                }
                mounts.append(entry.getKey()).append(':').append(mount.getClass().getName())
                        .append('@').append(Integer.toHexString(System.identityHashCode(mount))).append("; ");
                shown++;
            }
        }
        StorageGraphDiagnostics.logMounts(mounts.toString());
    }

    public static long keyCounterTotal(KeyCounter counter) {
        long total = 0L;
        for (var entry : counter) {
            total += entry.getLongValue();
        }
        return total;
    }

    static int countAliasGroups(Iterable<Integer> parentCounts) {
        int aliasGroups = 0;
        for (Integer count : parentCounts) {
            if (count != null && count > 1) {
                aliasGroups++;
            }
        }
        return aliasGroups;
    }

    public static String dump(MEStorage root) {
        if (root == null) {
            return "root=null";
        }
        StorageGraphSnapshot snapshot = collect(root);
        StringBuilder out = new StringBuilder();
        out.append("epoch=").append(snapshot.topologyEpoch())
                .append(" raw=").append(snapshot.rawMountCount())
                .append(" unique=").append(snapshot.uniqueSourceCount())
                .append(" alias=").append(snapshot.aliasGroupCount())
                .append(" opaque=").append(snapshot.opaqueSourceCount())
                .append(" unverified=").append(snapshot.unverifiedAliasCount())
                .append(" nodes=").append(snapshot.sources().size())
                .append(" edges=").append(snapshot.edges().size())
                .append(" cycles=").append(cycleCount(snapshot))
                .append('\n');
        for (Source source : snapshot.sources()) {
            out.append("source ").append(Long.toHexString(source.identityHash()))
                    .append(" id=").append(source.id().runtimeId())
                    .append(' ').append(source.kind())
                    .append(' ').append(source.physical() == null ? "null" : source.physical().getClass().getName())
                    .append('\n');
        }
        for (Edge edge : snapshot.edges()) {
            out.append("edge ").append(Long.toHexString(edge.fromNode()))
                    .append(" -> ").append(Long.toHexString(edge.toNode()))
                    .append(' ').append(edge.kind())
                    .append('\n');
        }
        return out.toString();
    }

    private static int cycleCount(StorageGraphSnapshot snapshot) {
        Map<Long, List<Long>> outgoing = new java.util.HashMap<>();
        for (Edge edge : snapshot.edges()) {
            outgoing.computeIfAbsent(edge.fromNode(), ignored -> new ArrayList<>()).add(edge.toNode());
        }
        java.util.Set<Long> visiting = new java.util.HashSet<>();
        java.util.Set<Long> done = new java.util.HashSet<>();
        int[] cycles = { 0 };
        for (Long node : outgoing.keySet()) {
            countCyclesFrom(node, outgoing, visiting, done, cycles);
        }
        return cycles[0];
    }

    private static void countCyclesFrom(Long node, Map<Long, List<Long>> outgoing,
                                        java.util.Set<Long> visiting, java.util.Set<Long> done, int[] cycles) {
        if (done.contains(node)) {
            return;
        }
        if (!visiting.add(node)) {
            cycles[0]++;
            return;
        }
        for (Long next : outgoing.getOrDefault(node, List.of())) {
            countCyclesFrom(next, outgoing, visiting, done, cycles);
        }
        visiting.remove(node);
        done.add(node);
    }

    private static StorageGraphSnapshot collect(MEStorage root) {
        long epoch = topologyEpoch;
        IdentityHashMap<MEStorage, Boolean> visited = new IdentityHashMap<>();
        IdentityHashMap<MEStorage, Integer> parentCount = new IdentityHashMap<>();
        IdentityHashMap<MEStorage, Long> externalKeys = new IdentityHashMap<>();
        ArrayDeque<MEStorage> queue = new ArrayDeque<>();
        List<Edge> edges = new ArrayList<>();
        long edgeId = 0L;
        int depth = 0;
        visited.put(root, Boolean.TRUE);
        queue.add(root);
        while (!queue.isEmpty() && visited.size() <= MAX_NODES && depth <= MAX_DEPTH) {
            int levelSize = queue.size();
            depth++;
            for (int i = 0; i < levelSize; i++) {
                MEStorage node = queue.poll();
                StorageGraphAdapter adapter = StorageGraphAdapterRegistry.find(node);
                for (StorageGraphAdapter.ChildEdge child : adapter.children(node)) {
                    edges.add(new Edge(edgeId++, System.identityHashCode(node), System.identityHashCode(child.child()),
                            child.kind(), child.policy(), child.nativeOrder()));
                    parentCount.merge(child.child(), 1, Integer::sum);
                    if (child.externalKey() != 0L) {
                        externalKeys.put(child.child(), child.externalKey());
                    } else {
                        Long inherited = externalKeys.get(node);
                        if (inherited != null) {
                            externalKeys.put(child.child(), inherited);
                        }
                    }
                    if (visited.putIfAbsent(child.child(), Boolean.TRUE) == null) {
                        queue.add(child.child());
                    }
                }
            }
        }

        Map<SourceId, Source> sourceMap = new java.util.LinkedHashMap<>();
        for (MEStorage node : visited.keySet()) {
            StorageGraphAdapter adapter = StorageGraphAdapterRegistry.find(node);
            if (!adapter.children(node).isEmpty()) {
                continue;
            }
            long externalKey = externalKeys.getOrDefault(node, 0L);
            SourceKind kind = adapter.sourceKind(node);
            SourceId id = SourceRegistry.idFor(node, externalKey);
            sourceMap.putIfAbsent(id, new Source(id, kind, node, System.identityHashCode(node), epoch));
        }
        List<Source> sources = new ArrayList<>(sourceMap.values());
        int opaqueSources = 0;
        for (Source source : sources) {
            if (source.kind() == SourceKind.OPAQUE) {
                opaqueSources++;
            }
        }

        int aliasGroups = countAliasGroups(parentCount.values());
        int unverifiedAliases = 0;
        for (var entry : parentCount.entrySet()) {
            if (entry.getValue() > 1 && !externalKeys.containsKey(entry.getKey())) {
                unverifiedAliases++;
            }
        }
        int uniqueSources = sources.size() - opaqueSources;
        StorageGraphSnapshot snapshot = new StorageGraphSnapshot(epoch, policyEpoch, edges.size(), uniqueSources,
                aliasGroups, opaqueSources, unverifiedAliases, edges, sources);
        StorageGraphDiagnostics.logSnapshot(snapshot);
        StorageGraphDiagnostics.logSources(sources);
        return snapshot;
    }
}
