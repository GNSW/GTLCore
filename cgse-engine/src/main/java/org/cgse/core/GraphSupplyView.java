// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/** Positive-only compilation at an explicit supply boundary. Original physical
 * bindings must be restored and checked before any word leaves this owner. */
final class GraphSupplyView<K> implements AutoCloseable {
    private static final class LocalLimit extends RuntimeException {}
    private final PlanningBudget budget;
    private final long allowance, started;
    private final Map<String, GraphRecipe<K>> originals = new LinkedHashMap<>();
    private GraphCompiler<K> compiler;
    private long memory;

    GraphSupplyView(GraphCompiler<K> source, Set<K> supplied, PlanningBudget budget) {
        this(source, supplied, budget, Long.MAX_VALUE);
    }

    /** Optional callers share the request's hard accounting, but may decline
     * before a complete catalog projection consumes their local work share. */
    static <K> GraphSupplyView<K> bounded(GraphCompiler<K> source, Set<K> supplied, PlanningBudget budget, long maximumWork) {
        try { return new GraphSupplyView<>(source, supplied, budget, Math.max(0, maximumWork)); }
        catch (LocalLimit ignored) { return null; }
    }

    private GraphSupplyView(GraphCompiler<K> source, Set<K> supplied, PlanningBudget budget, long maximumWork) {
        this.budget = budget;
        allowance = maximumWork;
        started = allowance == Long.MAX_VALUE ? 0 : budget.threadWork();
        try {
            requireRoom();
            long bytes = 1024L + 256L * source.catalog().size();
            for (var r : source.catalog()) {
                check(); bytes += 192L * (r.slots().size() + r.outputs().size());
            }
            budget.reserve(bytes); memory = bytes;
            var catalog = new ArrayList<GraphRecipe<K>>();
            var projected = new LinkedHashMap<String, GraphRecipe<K>>();
            var keys = new LinkedHashSet<K>();
            for (var r : source.catalog()) {
                check(); originals.put(r.id(), r);
                var slots = new ArrayList<GraphRecipe.Slot<K>>();
                var outputs = new LinkedHashMap<K, Long>();
                for (var s : r.slots()) { check(); if (!supplied.contains(s.key())) slots.add(s); }
                for (var e : r.outputs().entrySet()) { check(); if (!supplied.contains(e.getKey())) outputs.put(e.getKey(), e.getValue()); }
                if (outputs.isEmpty() || r.executionOutputs().keySet().stream().allMatch(supplied::contains)) continue;
                var view = slots.size() == r.slots().size() && outputs.size() == r.outputs().size() ? r :
                        new GraphRecipe<>(r.id(), r.binding(), slots, outputs);
                projected.put(r.id(), view); catalog.add(view); keys.addAll(view.executionOutputs().keySet());
            }
            var order = new LinkedHashMap<K, List<GraphRecipe<K>>>();
            for (K key : keys) {
                var producers = new ArrayList<GraphRecipe<K>>();
                for (var r : source.producers(key)) {
                    check(); var view = projected.get(r.id());
                    if (view != null) producers.add(view);
                }
                order.put(key, List.copyOf(producers));
            }
            compiler = new GraphCompiler<>(List.copyOf(catalog), Collections.unmodifiableMap(order));
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }
    private void requireRoom() {
        if (allowance != Long.MAX_VALUE && budget.threadWork() - started >= allowance) {
            budget.checkpoint();
            throw new LocalLimit();
        }
    }
    private void check() { requireRoom(); budget.check(); }
    GraphCompiler<K> compiler() { return compiler; }
    Map<String, GraphRecipe<K>> originals() { return originals; }
    public void close() { compiler = null; originals.clear(); budget.release(memory); memory = 0; }
}
