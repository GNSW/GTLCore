package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Monotone over-approximation of every allowed producer, including alternatives.
 * Inputs are never consumed and one available unit can supply any input count.
 * Failure even in this relaxed model proves a missing starting resource; success
 * says nothing about quantities, ordering or seed recovery.
 */
final class MissingStockAnalysis<K> {

    private final Iterator<GraphRecipe<K>> indexing;
    private final Map<K, Long> stock;
    private final Set<K> external, required;
    private final Set<String> excluded;
    private final K target;
    private final boolean force;
    private final PlanningBudget budget;
    private final List<GraphRecipe<K>> recipes = new ArrayList<>();
    private final List<Integer> waiting = new ArrayList<>();
    private final Map<K, List<Integer>> consumers = new HashMap<>();
    private final Set<K> produced = new HashSet<>();
    private final ArrayDeque<Integer> ready = new ArrayDeque<>();
    private Iterator<K> outputs;
    private Iterator<Integer> waking;
    private long memory;
    private boolean complete, blocked;

    MissingStockAnalysis(GraphCompiler<K> compiler, K target, Map<K, Long> stock, Set<K> external,
                         Set<K> required, Set<String> excluded, boolean force, PlanningBudget budget) {
        indexing = compiler.catalog().iterator();
        this.target = target;
        this.stock = stock;
        this.external = external;
        this.required = required;
        this.excluded = excluded;
        this.force = force;
        this.budget = budget;
    }

    boolean step() {
        budget.check();
        if (complete) return true;
        if (indexing.hasNext()) {
            GraphRecipe<K> recipe = indexing.next();
            if (excluded.contains(recipe.id())) return false;
            int id = recipes.size(), count = 0;
            recipes.add(recipe);
            reserve(64);
            for (K key : recipe.inputs().keySet()) {
                budget.check();
                if (!available(key)) {
                    consumers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(id);
                    reserve(80);
                    count++;
                }
            }
            waiting.add(count);
            if (count == 0) ready.add(id);
            return false;
        }
        if (waking != null && waking.hasNext()) {
            int id = waking.next();
            int remaining = waiting.get(id) - 1;
            waiting.set(id, remaining);
            if (remaining == 0) ready.add(id);
            return false;
        }
        waking = null;
        if (outputs != null && outputs.hasNext()) {
            K key = outputs.next();
            if (produced.add(key)) {
                reserve(64);
                waking = consumers.getOrDefault(key, List.of()).iterator();
            }
            return false;
        }
        outputs = null;
        if (!ready.isEmpty()) {
            outputs = recipes.get(ready.removeFirst()).outputs().keySet().iterator();
            return false;
        }
        blocked = force ? !produced.contains(target) && !external.contains(target) : !available(target);
        for (K key : required) {
            budget.check();
            if (!available(key)) blocked = true;
        }
        complete = true;
        budget.release(memory);
        return true;
    }

    private boolean available(K key) {
        return stock.getOrDefault(key, 0L) > 0 || external.contains(key) || produced.contains(key);
    }

    private void reserve(long bytes) {
        budget.reserve(bytes);
        memory += bytes;
    }

    boolean blocked() {
        if (!complete) throw new IllegalStateException("Missing stock analysis is incomplete");
        return blocked;
    }
}
