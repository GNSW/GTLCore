// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/** Positive-only compilation at an explicit supply boundary. Original physical
 * bindings must be restored and checked before any word leaves this owner. */
final class GraphSupplyView<K> implements AutoCloseable {
    private final PlanningBudget budget;
    private final Map<String, GraphRecipe<K>> originals = new LinkedHashMap<>();
    private GraphCompiler<K> compiler;
    private long memory;

    GraphSupplyView(GraphCompiler<K> source, Set<K> supplied, PlanningBudget budget) {
        this.budget = budget;
        try {
            long bytes = 1024L + 256L * source.catalog().size();
            for (var r : source.catalog()) {
                budget.check(); bytes += 192L * (r.slots().size() + r.outputs().size());
            }
            budget.reserve(bytes); memory = bytes;
            var catalog = new ArrayList<GraphRecipe<K>>();
            var projected = new LinkedHashMap<String, GraphRecipe<K>>();
            var keys = new LinkedHashSet<K>();
            for (var r : source.catalog()) {
                budget.check(); originals.put(r.id(), r);
                var slots = new ArrayList<GraphRecipe.Slot<K>>();
                var outputs = new LinkedHashMap<K, Long>();
                for (var s : r.slots()) { budget.check(); if (!supplied.contains(s.key())) slots.add(s); }
                for (var e : r.outputs().entrySet()) { budget.check(); if (!supplied.contains(e.getKey())) outputs.put(e.getKey(), e.getValue()); }
                if (outputs.isEmpty() || r.executionOutputs().keySet().stream().allMatch(supplied::contains)) continue;
                var view = slots.size() == r.slots().size() && outputs.size() == r.outputs().size() ? r :
                        new GraphRecipe<>(r.id(), r.binding(), slots, outputs);
                projected.put(r.id(), view); catalog.add(view); keys.addAll(view.executionOutputs().keySet());
            }
            var order = new LinkedHashMap<K, List<GraphRecipe<K>>>();
            for (K key : keys) {
                var producers = new ArrayList<GraphRecipe<K>>();
                for (var r : source.producers(key)) {
                    budget.check(); var view = projected.get(r.id());
                    if (view != null) producers.add(view);
                }
                order.put(key, List.copyOf(producers));
            }
            compiler = new GraphCompiler<>(List.copyOf(catalog), Collections.unmodifiableMap(order));
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }
    GraphCompiler<K> compiler() { return compiler; }
    Map<String, GraphRecipe<K>> originals() { return originals; }
    public void close() { compiler = null; originals.clear(); budget.release(memory); memory = 0; }
}
