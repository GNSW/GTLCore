// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Complete finite producer-key projections for one immutable capture. A rule
 * and its producer index must both be the identical captured objects; neither
 * a rule ID nor recipe IDs authorize reuse across replaced semantics/supply.
 * These projections select a positive state attempt, never a negative proof.
 */
final class CapturedStateProducers<K> {
    static final int MAX_KEYS = 256, MAX_ENTRIES = 32;
    static final long MAX_BYTES = 64L << 10;
    private record Entry<K>(StateFamily<K> family, Map<K, List<Integer>> scope, List<K> keys, long bytes) {}
    private record Snapshot<K>(List<Entry<K>> entries, long bytes) {}
    record Metrics(long hits, long misses, int entries, long bytes) {}

    private final AtomicReference<Snapshot<K>> saved = new AtomicReference<>(new Snapshot<>(List.of(), 0));
    private final AtomicLong hits = new AtomicLong(), misses = new AtomicLong();

    List<K> get(StateFamily<K> family, Map<K, List<Integer>> scope, PlanningBudget budget) {
        for (var entry : saved.get().entries) {
            budget.compilationScan();
            if (entry.family == family && entry.scope == scope) {
                hits.updateAndGet(n -> n == Long.MAX_VALUE ? n : n + 1);
                return entry.keys;
            }
        }
        misses.updateAndGet(n -> n == Long.MAX_VALUE ? n : n + 1);
        return null;
    }

    /** Bounded optional retention workspace; no current-request reservation. */
    Capture begin(StateFamily<K> family, Map<K, List<Integer>> scope, PlanningBudget budget) {
        return budget.tryCacheCharge(1) ? new Capture(family, scope, budget) : null;
    }

    final class Capture implements AutoCloseable {
        private final StateFamily<K> family;
        private final Map<K, List<Integer>> scope;
        private final PlanningBudget budget;
        private ArrayList<K> keys = new ArrayList<>();

        private Capture(StateFamily<K> family, Map<K, List<Integer>> scope, PlanningBudget budget) {
            this.family = family; this.scope = scope; this.budget = budget;
        }

        void add(K key) {
            if (keys == null) return;
            if (keys.size() == MAX_KEYS || !budget.tryCacheCharge(1)) { close(); return; }
            keys.add(key);
        }

        /** Called only after the entire original index was scanned. */
        void complete() {
            if (keys == null) return;
            try {
                var before = saved.get();
                if (!budget.tryCacheCharge(1L + keys.size() + before.entries.size())) return;
                for (var entry : before.entries) if (entry.family == family && entry.scope == scope) return;
                long bytes = 160L + 32L * keys.size();
                var entries = new ArrayList<Entry<K>>();
                entries.add(new Entry<>(family, scope, List.copyOf(keys), bytes));
                for (var entry : before.entries) {
                    if (entries.size() == MAX_ENTRIES || entry.bytes > MAX_BYTES - bytes) break;
                    entries.add(entry); bytes += entry.bytes;
                }
                // A racing publication can decline optional retention. No
                // callback, checkpoint or host equality runs under a monitor.
                saved.compareAndSet(before, new Snapshot<>(List.copyOf(entries), bytes));
            } finally { close(); }
        }

        @Override public void close() { if (keys != null) { keys.clear(); keys = null; } }
    }

    Metrics metrics() {
        var snapshot = saved.get();
        return new Metrics(hits.get(), misses.get(), snapshot.entries.size(), snapshot.bytes);
    }
}
