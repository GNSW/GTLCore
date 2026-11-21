package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import org.gtlcore.gtlcore.integration.ae2.throughput.ThroughputStorageView;

import appeng.api.storage.MEStorage;

import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;

/** Assigns a stable source id per physical object and invalidates it when its delegate changes. */
public final class SourceRegistry {

    private static final IdentityHashMap<MEStorage, Entry> ENTRIES = new IdentityHashMap<>();
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
        }
    }

    public static void onRemoved(MEStorage node) {
        if (node == null) {
            return;
        }
        synchronized (ENTRIES) {
            ENTRIES.remove(node);
        }
    }

    public static void reset() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
            EXTERNAL.clear();
            nextId = 1L;
        }
    }

    private static long delegateHash(MEStorage node) {
        if (node instanceof ThroughputStorageView view) {
            Collection<MEStorage> children = view.gtlcore$getChildStorages();
            if (children != null && !children.isEmpty()) {
                return System.identityHashCode(children.iterator().next());
            }
        }
        return 0L;
    }

    private record Entry(SourceId id, long delegateHash) {}
}
