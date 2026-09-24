package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Tries each recipe once, waking only consumers of a resource just produced. */
final class RegionOrder<K> {

    private final List<GraphRecipe<K>> recipes;
    private final Set<K> internal, external;
    private final Map<K, Long> stock;
    private final PlanningBudget budget;
    private final Map<K, BigInteger> available = new HashMap<>();
    private final Map<K, List<Integer>> consumers = new HashMap<>();
    private final Deque<Integer> ready = new ArrayDeque<>();
    private final List<GraphRecipe<K>> ordered = new ArrayList<>();
    private final boolean[] queued, done;
    private int index;
    private long memory;

    RegionOrder(List<GraphRecipe<K>> recipes, Set<K> internal, Map<K, Long> stock, Set<K> external, PlanningBudget budget) {
        this.recipes = recipes;
        this.internal = internal;
        this.stock = stock;
        this.external = external;
        this.budget = budget;
        queued = new boolean[recipes.size()];
        done = new boolean[recipes.size()];
        reserve(128L + 40L * recipes.size());
    }

    boolean step() {
        budget.check();
        if (index < recipes.size()) {
            var recipe = recipes.get(index);
            for (K key : recipe.inputs().keySet()) if (internal.contains(key) && !external.contains(key)) {
                budget.check();
                consumers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(index);
                available.putIfAbsent(key, BigInteger.valueOf(stock.getOrDefault(key, 0L)));
                reserve(96);
            }
            if (enabled(recipe)) enqueue(index);
            index++;
            return false;
        }
        if (ready.isEmpty()) return true;
        int id = ready.removeFirst();
        queued[id] = false;
        var recipe = recipes.get(id);
        if (done[id] || !enabled(recipe)) return false;
        done[id] = true;
        ordered.add(recipe);
        for (var input : recipe.inputs().entrySet()) if (internal.contains(input.getKey()) && !external.contains(input.getKey())) {
            budget.check();
            available.merge(input.getKey(), BigInteger.valueOf(input.getValue()).negate(), BigInteger::add);
        }
        for (var output : recipe.outputs().entrySet()) {
            budget.check();
            K key = output.getKey();
            if (external.contains(key)) continue;
            available.merge(key, BigInteger.valueOf(output.getValue()), BigInteger::add);
            for (int consumer : consumers.getOrDefault(key, List.of())) {
                budget.check();
                if (!done[consumer]) enqueue(consumer);
            }
        }
        return ordered.size() == recipes.size();
    }

    private boolean enabled(GraphRecipe<K> recipe) {
        for (var input : recipe.inputs().entrySet()) {
            budget.check();
            if (internal.contains(input.getKey()) && !external.contains(input.getKey()) &&
                    available.getOrDefault(input.getKey(), BigInteger.valueOf(stock.getOrDefault(input.getKey(), 0L)))
                            .compareTo(BigInteger.valueOf(input.getValue())) < 0) return false;
        }
        return true;
    }

    private void enqueue(int id) {
        if (!queued[id]) {
            queued[id] = true;
            ready.addLast(id);
        }
    }

    List<GraphRecipe<K>> result() {
        return ordered.size() == recipes.size() ? List.copyOf(ordered) : null;
    }

    private void reserve(long bytes) {
        budget.reserve(bytes);
        memory += bytes;
    }

    void close() {
        budget.release(memory);
        memory = 0;
    }
}
