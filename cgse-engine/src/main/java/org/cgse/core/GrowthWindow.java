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
    // The live window is a moving suffix, not the identity of a cycle. Retain
    // bounded, already observed recipe membership when one stage is in flight.
    private Set<String> indexed = Set.of();
    private long generation = -1;
    private final Map<String, Set<String>> groups = new HashMap<>();
    private final Map<K, Set<String>> protectedKeys = new HashMap<>();
    private final Set<String> loanSuppliers = new HashSet<>();

    void accepted(String id) { loanSuppliers.removeAll(groups.getOrDefault(id, Set.of())); }

    GrowthWindow(Map<String, GraphRecipe<K>> recipes, Map<String, BigInteger> planned, AdaptiveScheduling<K> feedback) {
        this.recipes = recipes; this.planned = planned; this.feedback = feedback;
    }

    boolean enabled() { return feedback.enabled(); }

    boolean preferred(String id) {
        Set<String> group = groups.get(id);
        return feedback.enabled() && group != null && group.stream().allMatch(feedback::growing);
    }

    private boolean sharing(String id) {
        Set<String> group = groups.get(id);
        return group != null && group.stream().anyMatch(feedback::congested);
    }

    boolean extend() { return groups.keySet().stream().anyMatch(this::preferred) && feedback.spend(256); }

    private void index(List<PlanStep.Batch> window) {
        if (generation != feedback.generation()) loanSuppliers.clear();
        Set<String> visible = new LinkedHashSet<>();
        for (var batch : window) visible.add(batch.recipe());
        Set<String> selected = new LinkedHashSet<>(visible);
        for (String id : visible) selected.addAll(groups.getOrDefault(id, Set.of()));
        // Historical acyclic stages and groups with no remaining visible work
        // must not fill the bounded index and hide a later, unrelated cycle.
        // If retaining partial groups would exceed the limit, preserve the
        // original coverage of the complete current window instead.
        if (selected.size() > PipelineScheduler.WINDOW) selected = visible;
        if (selected.equals(indexed) && generation == feedback.generation()) return;
        // This local index deliberately uses one long bitset and bounded work;
        // do not alias recipe bits if the caller supplies an oversized window.
        if (selected.size() > PipelineScheduler.WINDOW) return;
        int n = selected.size(), incidences = 0;
        for (String id : selected) {
            GraphRecipe<K> recipe = recipes.get(id);
            incidences += recipe.inputs().size() + recipe.outputs().size();
            if (!LoopBatching.fixedPerRun(recipe) || incidences > 256) {
                return;
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
        // Completed or replaced groups must not retain historical loan hints.
        loanSuppliers.retainAll(groups.keySet());
    }

    List<PlanStep.Batch> regroup(List<PlanStep.Batch> window, ToLongFunction<K> forecast) {
        return regroup(window, forecast, forecast);
    }

    List<PlanStep.Batch> regroup(List<PlanStep.Batch> window, ToLongFunction<K> forecast, ToLongFunction<K> available) {
        if (!feedback.enabled()) return null;
        // A retained cycle index does not make non-linear recipe inputs safe
        // for the fixed-per-run proof below, nor permit an unbounded stock scan.
        Set<String> checked = new HashSet<>();
        long incidences = 0;
        for (var batch : window) if (checked.add(batch.recipe())) {
            var recipe = recipes.get(batch.recipe());
            incidences += (long) recipe.inputs().size() + recipe.outputs().size();
            if (incidences > 256 || !LoopBatching.fixedPerRun(recipe)) return null;
        }
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
        // Relinquishing the soft reservation is ineffective if a previously
        // grouped growth prefix still reserves the entire next batch. Propose
        // one funded consumer loan, then rebuild all remaining exact work.
        String consumer = null;
        long loan = 0;
        for (String id : remaining.keySet()) {
            if (groups.containsKey(id)) continue;
            var recipe = recipes.get(id);
            if (!feedback.spend(1 + recipe.inputs().size() * (1 + remaining.size()) + recipe.outputs().size())) break;
            long count = funded(id, remaining, stock);
            for (var input : recipe.inputs().entrySet()) count = Math.min(count, available.applyAsLong(input.getKey()) / input.getValue());
            boolean released = false;
            for (var input : recipe.inputs().entrySet()) {
                long loss = input.getValue() - recipe.outputs().getOrDefault(input.getKey(), 0L);
                Set<String> suppliers = protectedKeys.get(input.getKey());
                if (loss <= 0 || suppliers == null) continue;
                if (suppliers.stream().anyMatch(loanSuppliers::contains)) { count = 0; break; }
                long reserve = 0;
                boolean capacityKnown = false;
                for (String supplier : suppliers) if (remaining.containsKey(supplier)) {
                    // Missing timing coverage is not evidence of saturation.
                    if (!sharing(supplier)) { count = 0; break; }
                    released = true;
                    long perRun = recipes.get(supplier).inputs().getOrDefault(input.getKey(), 0L);
                    long known = feedback.capacityReserve(supplier);
                    capacityKnown |= perRun > 0 && known > 0;
                    reserve = Math.max(reserve, ExactAmounts.capped(BigInteger.valueOf(perRun).multiply(BigInteger.valueOf(Math.max(1, known)))));
                }
                BigInteger physical = BigInteger.valueOf(available.applyAsLong(input.getKey()));
                if (!capacityKnown) reserve = Math.max(reserve, physical.add(BigInteger.ONE).divide(BigInteger.TWO).longValueExact());
                count = Math.min(count, ExactAmounts.capped(physical.subtract(BigInteger.valueOf(reserve))
                        .max(BigInteger.ZERO).divide(BigInteger.valueOf(loss))));
            }
            if (released && count > 0) { consumer = id; loan = count; break; }
        }
        for (int attempt = 0; consumer != null && loan > 0 && attempt < 3; attempt++, loan /= 2) {
            var result = order(remaining, stock, consumer, loan);
            if (result != null) {
                for (K key : recipes.get(consumer).inputs().keySet()) loanSuppliers.addAll(protectedKeys.getOrDefault(key, Set.of()));
                return result.equals(window) ? null : result;
            }
        }
        var result = order(remaining, stock, null, 0);
        return result == null || result.equals(window) ? null : result;
    }

    private List<PlanStep.Batch> order(Map<String, BigInteger> counts, Map<K, BigInteger> initial, String loan, long loanCount) {
        var remaining = new LinkedHashMap<>(counts);
        var stock = new HashMap<>(initial);
        List<PlanStep.Batch> result = new ArrayList<>();
        if (loan != null) execute(loan, loanCount, remaining, stock, result);
        while (!remaining.isEmpty() && result.size() < PipelineScheduler.WINDOW) {
            boolean moved = false;
            for (int pass = 0; pass < 2 && !moved; pass++) for (String id : new ArrayList<>(remaining.keySet())) {
                if (preferred(id) != (pass == 0)) continue;
                var recipe = recipes.get(id);
                if (!feedback.spend(1 + recipe.inputs().size() + recipe.outputs().size())) return null;
                long count = funded(id, remaining, stock);
                if (count == 0) continue;
                execute(id, count, remaining, stock, result);
                moved = true;
                break;
            }
            if (!moved) return null;
        }
        // The loan is only a heuristic. The complete unchanged multiset must
        // have funded prefixes and long headroom before replacing the window.
        return remaining.isEmpty() ? result : null;
    }

    private long funded(String id, Map<String, BigInteger> remaining, Map<K, BigInteger> stock) {
        var recipe = recipes.get(id);
        long count = ExactAmounts.capped(remaining.get(id));
        for (var input : recipe.inputs().entrySet()) count = Math.min(count,
                stock.get(input.getKey()).divide(BigInteger.valueOf(input.getValue())).longValueExact());
        for (var output : recipe.outputs().entrySet()) {
            count = Math.min(count, Long.MAX_VALUE / output.getValue());
            long net = output.getValue() - recipe.inputs().getOrDefault(output.getKey(), 0L);
            if (net > 0) count = Math.min(count, ExactAmounts.capped(ExactAmounts.LONG_MAX.subtract(stock.get(output.getKey()))
                    .divide(BigInteger.valueOf(net))));
        }
        return count;
    }

    private void execute(String id, long count, Map<String, BigInteger> remaining, Map<K, BigInteger> stock, List<PlanStep.Batch> result) {
        var recipe = recipes.get(id);
        BigInteger runs = BigInteger.valueOf(count);
        recipe.inputs().forEach((key, amount) -> stock.put(key, stock.get(key).subtract(runs.multiply(BigInteger.valueOf(amount)))));
        recipe.outputs().forEach((key, amount) -> stock.put(key, stock.get(key).add(runs.multiply(BigInteger.valueOf(amount)))));
        result.add(new PlanStep.Batch(id, count));
        BigInteger left = remaining.get(id).subtract(runs);
        if (left.signum() == 0) remaining.remove(id); else remaining.put(id, left);
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
