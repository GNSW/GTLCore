// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Capture-local source positions from verified state supports. These are
 * positive trial hints, not a complete producer index or reusable stock. */
final class CapturedStateSources<K> {
    static final int MAX_SOURCES = 128, MAX_PORTS = 4096;
    static final long MAX_BYTES = 8L << 10;
    private record Snapshot<K>(List<CapturedCatalog.Entry<K>> scope, List<Integer> sources, long bytes) {}
    record Metrics(long hits, int sources, long bytes) {}
    private final AtomicReference<Snapshot<K>> saved = new AtomicReference<>();
    private final AtomicLong hits = new AtomicLong();

    List<Integer> get(List<CapturedCatalog.Entry<K>> scope, PlanningBudget budget) {
        budget.compilationScan();
        var value = saved.get();
        if (value == null || value.scope != scope) return null;
        hits.updateAndGet(n -> n == Long.MAX_VALUE ? n : n + 1);
        return value.sources;
    }

    void retain(List<CapturedCatalog.Entry<K>> scope, List<Integer> selected, PlanningBudget budget) {
        if (selected == null || selected.isEmpty() || selected.size() > MAX_SOURCES) return;
        var before = saved.get();
        if (before != null && before.scope != scope) return;
        if (!budget.tryCacheCharge(1L + selected.size() + (before == null ? 0 : before.sources.size()))) return;
        var previous = before == null ? Set.<Integer>of() : new HashSet<>(before.sources);
        var sources = new LinkedHashSet<Integer>(); int ports = 0;
        boolean changed = before == null;
        for (int index : selected) {
            if (!budget.tryCacheScan(1) || index < 0 || index >= scope.size()) return;
            if (previous.contains(index)) { sources.add(index); continue; }
            var recipe = scope.get(index).recipe(); if (recipe.size() == 0) continue;
            boolean state = false;
            for (var input : recipe.inputs()) {
                if (!budget.tryCacheScan(1) || ++ports > MAX_PORTS) return;
                for (var candidate : input.candidates()) {
                    if (!budget.tryCacheScan(1) || ++ports > MAX_PORTS) return;
                    if (candidate.family() != null && candidate.stack().amount() == 1 && !candidate.configuration() && !candidate.reusable()) {
                        state = true; break;
                    }
                }
                if (state) break;
            }
            if (state) { sources.add(index); changed = true; }
        }
        if (!changed || sources.isEmpty()) return;
        if (before != null) for (int index : before.sources) {
            if (!budget.tryCacheScan(1)) return;
            if (sources.size() == MAX_SOURCES) break;
            sources.add(index);
        }
        // Keep recent useful supports, but restore the immutable catalog order
        // before any new request can use them. Copy/sort belongs to retention.
        long bytes = 128L + 32L * sources.size();
        if (bytes > MAX_BYTES || !budget.tryCacheCharge(8L * sources.size())) return;
        var ordered = new ArrayList<>(sources); ordered.sort(Integer::compare);
        saved.compareAndSet(before, new Snapshot<>(scope, List.copyOf(ordered), bytes));
    }

    Metrics metrics() {
        var value = saved.get();
        return new Metrics(hits.get(), value == null ? 0 : value.sources.size(), value == null ? 0 : value.bytes);
    }
}
