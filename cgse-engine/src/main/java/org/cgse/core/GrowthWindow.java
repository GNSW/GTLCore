// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.function.ToLongFunction;

/** Bounded, exactly funded regrouping of the live window; no new recipe executions. */
final class GrowthWindow<K> {
    private final Map<String, GraphRecipe<K>> recipes;
    private final Map<String, BigInteger> planned;
    private final AdaptiveScheduling<K> feedback;
    private Set<String> indexed = Set.of();
    private long generation = -1;
    private final Map<String, Set<String>> groups = new HashMap<>();
    private final Map<K, Set<String>> protectedKeys = new HashMap<>();

    GrowthWindow(Map<String, GraphRecipe<K>> recipes, Map<String, BigInteger> planned, AdaptiveScheduling<K> feedback) {
        this.recipes = recipes; this.planned = planned; this.feedback = feedback;
    }

    boolean enabled() { return feedback.enabled(); }

    boolean preferred(String id) {
        Set<String> group = groups.get(id);
        return feedback.enabled() && group != null && group.stream().allMatch(feedback::growing);
    }

    boolean extend() { return groups.keySet().stream().anyMatch(this::preferred) && feedback.spend(256); }

    private void index(List<PlanStep.Batch> window) {
        Set<String> selected = new LinkedHashSet<>();
        for (var batch : window) selected.add(batch.recipe());
        if (selected.equals(indexed) && generation == feedback.generation()) return;
        int n = selected.size(), incidences = 0;
        for (String id : selected) {
            GraphRecipe<K> recipe = recipes.get(id);
            incidences += recipe.inputs().size() + recipe.outputs().size();
            if (!LoopBatching.fixedPerRun(recipe) || incidences > 256) {
                groups.clear(); protectedKeys.clear(); indexed = selected; generation = feedback.generation(); return;
            }
        }
        if (!feedback.spend(n * n + incidences * 4)) return;
        indexed = selected;
        generation = feedback.generation();
        groups.clear(); protectedKeys.clear();
        List<String> ids = new ArrayList<>(selected);
        Map<K, Long> producers = new HashMap<>(), consumers = new HashMap<>();
        Set<K> conserved = DagScheduler.conserved(ids.stream().map(recipes::get).toList());
        for (int i = 0; i < n; i++) {
            long bit = 1L << i;
            var recipe = recipes.get(ids.get(i));
            for (K key : recipe.outputs().keySet()) if (!conserved.contains(key)) producers.merge(key, bit, (a, b) -> a | b);
            for (K key : recipe.inputs().keySet()) if (!conserved.contains(key)) consumers.merge(key, bit, (a, b) -> a | b);
        }
        long[] reach = new long[n];
        producers.forEach((key, mask) -> {
            long targets = consumers.getOrDefault(key, 0L);
            for (int i = 0; i < n; i++) if ((mask & 1L << i) != 0) reach[i] |= targets;
        });
        for (int k = 0; k < n; k++) for (int i = 0; i < n; i++)
            if ((reach[i] & 1L << k) != 0) reach[i] |= reach[k];
        long assigned = 0;
        for (int i = 0; i < n; i++) {
            if ((assigned & 1L << i) != 0 || (reach[i] & 1L << i) == 0) continue;
            Set<String> group = new LinkedHashSet<>();
            long mask = 0;
            for (int j = 0; j < n; j++) if ((reach[i] & 1L << j) != 0 && (reach[j] & 1L << i) != 0) {
                group.add(ids.get(j)); mask |= 1L << j;
            }
            assigned |= mask;
            Set<K> internal = new LinkedHashSet<>();
            boolean productive = false, drains = false;
            for (K key : producers.keySet()) {
                if ((producers.get(key) & mask) == 0 || (consumers.getOrDefault(key, 0L) & mask) == 0) continue;
                internal.add(key);
                BigInteger delta = BigInteger.ZERO;
                for (String id : group) {
                    var recipe = recipes.get(id);
                    BigInteger net = BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L))
                            .subtract(BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L)));
                    delta = delta.add(net.multiply(planned.get(id)));
                }
                productive |= delta.signum() > 0;
                drains |= delta.signum() < 0;
            }
            if (!productive || drains) continue;
            for (String id : group) {
                groups.put(id, group);
                Set<K> returns = new LinkedHashSet<>(recipes.get(id).outputs().keySet());
                returns.retainAll(internal);
                feedback.watch(id, returns);
            }
            for (K key : internal) protectedKeys.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).addAll(group);
        }
    }

    List<PlanStep.Batch> regroup(List<PlanStep.Batch> window, ToLongFunction<K> forecast) {
        if (!feedback.enabled()) return null;
        index(window);
        if (groups.isEmpty() || window.stream().allMatch(batch -> groups.containsKey(batch.recipe()))) return null;
        Map<String, BigInteger> remaining = new LinkedHashMap<>();
        Map<K, BigInteger> stock = new HashMap<>();
        for (var batch : window) {
            remaining.merge(batch.recipe(), BigInteger.valueOf(batch.runs()), BigInteger::add);
            var recipe = recipes.get(batch.recipe());
            for (K key : recipe.inputs().keySet()) stock.computeIfAbsent(key, k -> BigInteger.valueOf(forecast.applyAsLong(k)));
            for (K key : recipe.outputs().keySet()) stock.computeIfAbsent(key, k -> BigInteger.valueOf(forecast.applyAsLong(k)));
        }
        List<PlanStep.Batch> result = new ArrayList<>();
        while (!remaining.isEmpty() && result.size() < PipelineScheduler.WINDOW) {
            boolean moved = false;
            for (int pass = 0; pass < 2 && !moved; pass++) for (String id : new ArrayList<>(remaining.keySet())) {
                if (preferred(id) != (pass == 0)) continue;
                var recipe = recipes.get(id);
                if (!feedback.spend(1 + recipe.inputs().size() + recipe.outputs().size())) return null;
                long count = ExactAmounts.capped(remaining.get(id));
                for (var input : recipe.inputs().entrySet()) count = Math.min(count,
                        stock.get(input.getKey()).divide(BigInteger.valueOf(input.getValue())).longValueExact());
                for (var output : recipe.outputs().entrySet()) {
                    count = Math.min(count, Long.MAX_VALUE / output.getValue());
                    long net = output.getValue() - recipe.inputs().getOrDefault(output.getKey(), 0L);
                    if (net > 0) count = Math.min(count, ExactAmounts.capped(ExactAmounts.LONG_MAX.subtract(stock.get(output.getKey()))
                            .divide(BigInteger.valueOf(net))));
                }
                if (count == 0) continue;
                BigInteger runs = BigInteger.valueOf(count);
                recipe.inputs().forEach((key, amount) -> stock.put(key, stock.get(key).subtract(runs.multiply(BigInteger.valueOf(amount)))));
                recipe.outputs().forEach((key, amount) -> stock.put(key, stock.get(key).add(runs.multiply(BigInteger.valueOf(amount)))));
                result.add(new PlanStep.Batch(id, count));
                BigInteger left = remaining.get(id).subtract(runs);
                if (left.signum() == 0) remaining.remove(id); else remaining.put(id, left);
                moved = true;
                break;
            }
            if (!moved) return null;
        }
        // Every proposed physical batch was funded and its long headroom checked.
        // Equal counts leave the untouched suffix exactly the same inventory delta.
        return remaining.isEmpty() && !result.equals(window) ? result : null;
    }

    long limit(String id, long requested, List<PlanStep.Batch> window, ToLongFunction<K> forecast) {
        if (!feedback.enabled() || groups.isEmpty()) return requested;
        GraphRecipe<K> recipe = recipes.get(id);
        for (var input : recipe.inputs().entrySet()) {
            long loss = input.getValue() - recipe.outputs().getOrDefault(input.getKey(), 0L);
            Set<String> suppliers = protectedKeys.get(input.getKey());
            if (loss <= 0 || suppliers == null || suppliers.contains(id)) continue;
            long reserve = 0, available = forecast.applyAsLong(input.getKey());
            Set<String> seen = new HashSet<>();
            for (var batch : window) if (suppliers.contains(batch.recipe()) && seen.add(batch.recipe()) && preferred(batch.recipe())) {
                long perRun = recipes.get(batch.recipe()).inputs().getOrDefault(input.getKey(), 0L);
                reserve = Math.max(reserve, ExactAmounts.capped(BigInteger.valueOf(perRun).multiply(BigInteger.valueOf(batch.runs()))));
            }
            requested = Math.min(requested, (available - Math.min(available, reserve)) / loss);
        }
        return requested;
    }
}
