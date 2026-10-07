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
 * Inputs are never consumed. A recipe needs one full input batch, then every
 * resource it can increase is allowed to grow without bound. Finite components
 * keep their initial stock, giving an inductive optimistic inventory box.
 * Failure even in this relaxed model proves a missing starting resource; success
 * says nothing about quantities, ordering or seed recovery.
 */
final class MissingStockAnalysis<K> implements AutoCloseable {

    private final GraphCompiler<K> compiler;
    private final GraphCatalogIndex<K> sharedIndex;
    private final GraphSourceIndex<K> sharedSources;
    private GraphCatalogIndex.Builder<K> building;
    private final Map<K, Long> stock;
    private final Set<K> external, required;
    private final Set<String> excluded;
    private final K target;
    private final boolean force;
    private final PlanningBudget budget;
    private final List<GraphRecipe<K>> recipes;
    private int[] waiting;
    private int indexing;
    private final Map<K, List<Integer>> consumers = new HashMap<>();
    private final Set<K> produced = new HashSet<>();
    private final Set<K> unbounded = new HashSet<>();
    private final ArrayDeque<Integer> ready = new ArrayDeque<>();
    private Iterator<K> outputs;
    private GraphRecipe<K> firing;
    private Iterator<Integer> waking;
    private int[] indexedWaking;
    private int wakeCursor;
    private long memory;
    private boolean complete, blocked, closed;

    MissingStockAnalysis(GraphCompiler<K> compiler, K target, Map<K, Long> stock, Set<K> external,
                         Set<K> required, Set<String> excluded, boolean force, PlanningBudget budget) {
        this.compiler = compiler;
        recipes = compiler.catalog();
        sharedIndex = compiler.catalogIndex();
        sharedSources = sharedIndex == null ? compiler.sourceIndex() : null;
        this.target = target;
        this.stock = stock;
        this.external = external;
        this.required = required;
        this.excluded = excluded;
        this.force = force;
        this.budget = budget;
    }

    boolean step() {
        budget.operation(PlanningBudget.Operation.SCAN, 1);
        if (complete) return true;
        if (closed) throw new IllegalStateException("Missing stock analysis is closed");
        if (waiting == null) {
            reserve(16L + 4L * recipes.size());
            waiting = new int[recipes.size()];
            if (sharedIndex == null && sharedSources == null && compiler.reuseCatalogIndex()) building = new GraphCatalogIndex.Builder<>(recipes, budget);
        }
        if (indexing < recipes.size()) {
            int id = indexing++;
            GraphRecipe<K> recipe = recipes.get(id);
            if (building != null) building.add(recipe, id);
            if (excluded.contains(recipe.id())) {
                waiting[id] = -1;
                return false;
            }
            int count = 0;
            reserve(64);
            for (var input : recipe.inputs().entrySet()) {
                budget.operation(PlanningBudget.Operation.SCAN, 1);
                K key = input.getKey();
                if (!available(key, input.getValue())) {
                    if (sharedIndex == null && sharedSources == null) {
                        reserve(80);
                        consumers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(id);
                    }
                    count++;
                }
            }
            waiting[id] = count;
            if (count == 0) ready.add(id);
            return false;
        }
        if (building != null) {
            if (!building.step()) return false;
            if (building.result() != null) compiler.rememberCatalogIndex(building.result());
            building.close();
            building = null;
        }
        if (indexedWaking != null && wakeCursor < indexedWaking.length) {
            wake(indexedWaking[wakeCursor++]);
            return false;
        }
        indexedWaking = null;
        if (waking != null && waking.hasNext()) {
            wake(waking.next());
            return false;
        }
        waking = null;
        if (outputs != null && outputs.hasNext()) {
            K key = outputs.next();
            if (firing.executionOutputs().containsKey(key) && produced.add(key)) reserve(64);
            long consumed = firing.inputs().getOrDefault(key, 0L) - firing.configurationInputs().getOrDefault(key, 0L) + firing.reusableInputs().getOrDefault(key, 0L);
            if (firing.outputs().get(key) > consumed && unbounded.add(key)) {
                reserve(64);
                // Only previously deficient ports wait for this transition.
                // A finite initial stock may satisfy some, but not all, consumers.
                if (!external.contains(key)) {
                    wakingKey = key;
                    if (sharedIndex == null && sharedSources == null) waking = consumers.getOrDefault(key, List.of()).iterator();
                    else {
                        indexedWaking = sharedIndex != null ? sharedIndex.consumers(key) : sharedSources.consumers.get(key);
                        wakeCursor = 0;
                    }
                }
            }
            return false;
        }
        outputs = null;
        if (!ready.isEmpty()) {
            firing = recipes.get(ready.removeFirst());
            outputs = firing.outputs().keySet().iterator();
            return false;
        }
        blocked = force ? !produced.contains(target) && !external.contains(target) : !available(target, 1);
        for (K key : required) {
            budget.operation(PlanningBudget.Operation.SCAN, 1);
            if (!available(key, 1)) blocked = true;
        }
        complete = true;
        return true;
    }

    private void wake(int id) {
        if (waiting[id] > 0) {
            // The shared index includes already funded ports. The private
            // index contains only deficient ports and needs no second test.
            if ((sharedIndex != null || sharedSources != null) && stock.getOrDefault(wakingKey, 0L) >= recipes.get(id).inputs().get(wakingKey)) return;
            if (--waiting[id] == 0) ready.add(id);
        }
    }

    private K wakingKey;

    private boolean available(K key, long amount) {
        return stock.getOrDefault(key, 0L) >= amount || external.contains(key) || unbounded.contains(key);
    }

    private void reserve(long bytes) {
        budget.reserve(bytes);
        memory += bytes;
    }

    boolean blocked() {
        if (!complete) throw new IllegalStateException("Missing stock analysis is incomplete");
        return blocked;
    }

    Set<String> unreachableRecipes() {
        if (!complete) throw new IllegalStateException("Missing stock analysis is incomplete");
        Set<String> result = new HashSet<>();
        for (int i = 0; i < recipes.size(); i++) {
            budget.operation(PlanningBudget.Operation.SCAN, 1);
            if (waiting[i] > 0) result.add(recipes.get(i).id());
        }
        return Set.copyOf(result);
    }

    @Override
    public void close() {
        if (building != null) building.close();
        building = null;
        budget.release(memory);
        memory = 0;
        closed = true;
    }
}
