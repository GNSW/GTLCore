package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import org.gtlcore.gtlcore.integration.ae2.throughput.ThroughputStorageView;

import appeng.api.storage.MEStorage;

import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;

/** Assigns a stable source id per physical object and invalidates it when its delegate changes. */
public final class SourceRegistry {

    private static final IdentityHashMap<MEStorage, Entry> ENTRIES = new IdentityHashMap<>();
    private static final WeakHashMap<MEStorage, Long> EXTERNAL_OWNERS = new WeakHashMap<>();
    private static final HashMap<Long, Integer> EXTERNAL_REFS = new HashMap<>();
    private static final HashMap<Long, SourceId> EXTERNAL = new HashMap<>();
    private static long nextId = 1L;

    private SourceRegistry() {}

    public static SourceId idFor(MEStorage node) {
        return idFor(node, 0L);
    }

    public static SourceId idFor(MEStorage node, long externalKey) {
        if (node == null) {
            return new SourceId(0L);
        }
        if (externalKey != 0L) {
            synchronized (ENTRIES) {
                Long previous = EXTERNAL_OWNERS.put(node, externalKey);
                if (previous == null || previous != externalKey) {
                    if (previous != null) {
                        releaseRef(previous);
                    }
                    EXTERNAL_REFS.merge(externalKey, 1, Integer::sum);
                }
                return EXTERNAL.computeIfAbsent(externalKey, ignored -> new SourceId(nextId++));
            }
        }
        long delegateHash = delegateHash(node);
        synchronized (ENTRIES) {
            Entry entry = ENTRIES.get(node);
            if (entry != null && entry.delegateHash == delegateHash) {
                return entry.id;
            }
            SourceId id = new SourceId(nextId++);
            ENTRIES.put(node, new Entry(id, delegateHash));
            return id;
        }
    }

    public static void onDelegateChanged(MEStorage node) {
        if (node == null) {
            return;
        }
        synchronized (ENTRIES) {
            ENTRIES.remove(node);
            releaseExternal(node);
        }
    }

    public static void onRemoved(MEStorage node) {
        if (node == null) {
            return;
        }
        synchronized (ENTRIES) {
            ENTRIES.remove(node);
            releaseExternal(node);
        }
    }

    public static void cleanup() {
        synchronized (ENTRIES) {
            java.util.Set<Long> live = new java.util.HashSet<>(EXTERNAL_OWNERS.values());
            EXTERNAL_REFS.keySet().removeIf(key -> !live.contains(key));
            EXTERNAL.keySet().removeIf(key -> !live.contains(key));
        }
    }

    private static void releaseExternal(MEStorage node) {
        Long externalKey = EXTERNAL_OWNERS.remove(node);
        if (externalKey != null) {
            releaseRef(externalKey);
        }
    }

    private static void releaseRef(long externalKey) {
        int left = EXTERNAL_REFS.merge(externalKey, -1, Integer::sum);
        if (left <= 0) {
            EXTERNAL_REFS.remove(externalKey);
            EXTERNAL.remove(externalKey);
        }
    }

    public static void reset() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
            EXTERNAL_OWNERS.clear();
            EXTERNAL_REFS.clear();
            EXTERNAL.clear();
            nextId = 1L;
        }
    }

    private static long delegateHash(MEStorage node) {
        if (node instanceof ThroughputStorageView view) {
            Collection<MEStorage> children = view.gtlcore$getChildStorages();
            if (children != null && !children.isEmpty()) {
                return ExternalIdentityKeyRegistry.internWeak(children.iterator().next());
            }
        }
        return 0L;
    }

    private record Entry(SourceId id, long delegateHash) {}
}
