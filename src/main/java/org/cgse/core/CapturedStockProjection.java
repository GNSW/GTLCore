// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Request-owned positive trial: old key hints, freshly read physical amounts. */
final class CapturedStockProjection<K> implements AutoCloseable {
    private final List<K> keys;
    private final Map<K, Long> stock;
    private final PlanningBudget budget;
    private final Map<K, Long> values = new LinkedHashMap<>();
    private final Map<K, Long> view = Collections.unmodifiableMap(values);
    private long memory = 256;
    private int next;
    private boolean done, declined, closed;

    static <K> CapturedStockProjection<K> create(List<K> keys, Map<K, Long> stock, PlanningBudget budget) {
        if (keys == null || keys.isEmpty() || 2L * keys.size() >= stock.size() || !budget.tryReserve(256)) return null;
        return new CapturedStockProjection<>(keys, stock, budget);
    }

    private CapturedStockProjection(List<K> keys, Map<K, Long> stock, PlanningBudget budget) {
        this.keys = keys; this.stock = stock; this.budget = budget;
    }

    boolean step() {
        if (closed) throw new IllegalStateException("Closed stock projection");
        budget.checkpoint();
        if (done) return true;
        if (next == keys.size()) return done = true;
        budget.compilationCheck();
        K key = keys.get(next++);
        long amount = stock.getOrDefault(key, 0L);
        if (amount > 0) {
            if (!budget.tryReserve(96)) { declined = true; return done = true; }
            memory += 96; values.put(key, amount);
        }
        return false;
    }

    Map<K, Long> result() {
        if (!done || closed) throw new IllegalStateException("Stock projection is not available");
        return declined ? null : view;
    }

    @Override public void close() {
        if (closed) return;
        closed = true; values.clear(); budget.release(memory); memory = 0;
    }
}
