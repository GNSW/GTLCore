// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

/** Regroups a bounded loop body only when its exact inventory requirements permit it. */
final class LoopBatching {

    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private LoopBatching() {}

    static Map<String, BigInteger> counts(PlanStep body) {
        var computation = new PlanCountComputation(body);
        // Limit structural work, not expanded call occurrences. Large shared
        // loop bodies can still have just a handful of distinct program nodes.
        for (int slice = 0; slice < 128; slice++) if (computation.step(null)) {
            var counts = computation.result();
            if (counts.size() > PipelineScheduler.WINDOW || counts.values().stream().anyMatch(count -> count.compareTo(MAX) > 0)) return Map.of();
            return counts;
        }
        return Map.of();
    }

    static boolean fixedPerRun(GraphRecipe<?> recipe) {
        return recipe.configurationInputs().equals(recipe.reusableInputs());
    }

    record Group(Map<String, BigInteger> counts, long iterations) {}

    /**
     * A productive loop can accumulate the return-side material while its
     * original first recipe still has only one seed. Trying a different cycle
     * entry lets that material fund a larger batch, without adding any runs.
     * Every proposed order passes the same exact prefix/headroom check below.
     */
    static <K> Group group(Map<String, BigInteger> counts, long remaining,
                           Map<String, GraphRecipe<K>> recipes, Function<K, BigInteger> stock) {
        long best = iterations(counts, remaining, recipes, stock);
        Map<String, BigInteger> selected = counts;
        if (best < remaining && counts.size() > 1) {
            long incidences = 0;
            for (String id : counts.keySet()) {
                GraphRecipe<K> recipe = recipes.get(id);
                if (!fixedPerRun(recipe)) return null;
                incidences += recipe.inputs().size() + recipe.outputs().size();
            }
            // This is server-tick work. Limit extra exact checks by material
            // incidences; declining a reorder leaves the original cursor intact.
            int trials = (int) Math.min(counts.size() - 1L, 4096L / Math.max(1, incidences));
            var entries = new ArrayList<>(counts.entrySet());
            for (int shift = 1; shift <= trials; shift++) {
                Map<String, BigInteger> rotated = new LinkedHashMap<>();
                for (int i = 0; i < entries.size(); i++) {
                    var entry = entries.get((i + shift) % entries.size());
                    rotated.put(entry.getKey(), entry.getValue());
                }
                long possible = iterations(rotated, remaining, recipes, stock);
                if (possible > best) {
                    best = possible;
                    selected = rotated;
                    if (best == remaining) break;
                }
            }
            // With two recipes the rotations already cover every permutation.
            if (best < remaining && counts.size() > 2) {
                Map<String, BigInteger> funded = fundedOrder(counts, remaining, recipes, stock);
                if (funded != null) {
                    // The heuristic only proposes an order. Recheck its complete
                    // affine interval against the unchanged physical forecast.
                    long possible = iterations(funded, remaining, recipes, stock);
                    if (possible > best) {
                        best = possible;
                        selected = funded;
                    }
                }
            }
        }
        return best > 1 ? new Group(selected, best) : null;
    }

    /**
     * A cyclic rotation cannot repair every witness order. For example, B->C,
     * A+raw->B, C->9A needs A+raw->B, B->C, C->9A once A has accumulated.
     * Propose a resource-funded permutation without expanding the loop. The
     * fixed visit allowance bounds optional work on the server thread.
     */
    private static <K> Map<String, BigInteger> fundedOrder(Map<String, BigInteger> counts, long remaining,
                                                         Map<String, GraphRecipe<K>> recipes, Function<K, BigInteger> stock) {
        int allowance = 4096;
        long trial = 0;
        Map<K, BigInteger> initial = new HashMap<>();
        for (String id : counts.keySet()) {
            GraphRecipe<K> recipe = recipes.get(id);
            int cost = recipe.inputs().size() + recipe.outputs().size() + 1;
            if ((allowance -= cost) < 0) return null;
            for (K key : recipe.inputs().keySet()) initial.computeIfAbsent(key, stock);
            for (K key : recipe.outputs().keySet()) initial.computeIfAbsent(key, stock);
            trial = Math.max(trial, iterations(Collections.singletonMap(id, counts.get(id)), remaining, recipes, stock));
        }
        for (int attempt = 0; attempt < 8 && trial > 1; attempt++, trial /= 2) {
            Map<K, BigInteger> available = new HashMap<>(initial);
            Map<String, BigInteger> ordered = new LinkedHashMap<>();
            while (ordered.size() < counts.size()) {
                boolean progress = false;
                for (var entry : counts.entrySet()) {
                    if (ordered.containsKey(entry.getKey())) continue;
                    GraphRecipe<K> recipe = recipes.get(entry.getKey());
                    int cost = recipe.inputs().size() + recipe.outputs().size() + 1;
                    if ((allowance -= cost) < 0) return null;
                    if (iterations(Collections.singletonMap(entry.getKey(), entry.getValue()), trial, recipes, available::get) < trial) continue;
                    BigInteger runs = entry.getValue().multiply(BigInteger.valueOf(trial));
                    recipe.inputs().forEach((key, amount) -> available.merge(key, runs.multiply(BigInteger.valueOf(amount)).negate(), BigInteger::add));
                    recipe.outputs().forEach((key, amount) -> available.merge(key, runs.multiply(BigInteger.valueOf(amount)), BigInteger::add));
                    ordered.put(entry.getKey(), entry.getValue());
                    progress = true;
                }
                if (!progress) break;
            }
            if (ordered.size() == counts.size()) return ordered;
        }
        return null;
    }

    /** All constraints are affine in the number of regrouped iterations; no trial dispatches. */
    static <K> long iterations(Map<String, BigInteger> counts, long remaining,
                               Map<String, GraphRecipe<K>> recipes, Function<K, BigInteger> stock) {
        BigInteger lower = BigInteger.TWO, upper = BigInteger.valueOf(remaining);
        Map<K, BigInteger> prefix = new HashMap<>();
        for (var entry : counts.entrySet()) {
            GraphRecipe<K> recipe = recipes.get(entry.getKey());
            if (!fixedPerRun(recipe)) return 0;
            BigInteger copies = entry.getValue();
            upper = upper.min(MAX.divide(copies));
            var keys = new LinkedHashSet<>(recipe.inputs().keySet());
            keys.addAll(recipe.outputs().keySet());
            for (K key : keys) {
                BigInteger available = stock.apply(key);
                if (available.signum() < 0 || available.compareTo(MAX) > 0) return 0;
                BigInteger input = BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L));
                BigInteger delta = BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L)).subtract(input);
                BigInteger previous = prefix.getOrDefault(key, BigInteger.ZERO);
                BigInteger loss = delta.negate().max(BigInteger.ZERO);
                // required = input - loss + (copies * loss - previous) * n
                BigInteger coefficient = copies.multiply(loss).subtract(previous);
                BigInteger slack = available.subtract(input.subtract(loss));
                if (coefficient.signum() > 0) {
                    if (slack.signum() < 0) return 0;
                    upper = upper.min(slack.divide(coefficient));
                } else if (coefficient.signum() == 0) {
                    if (slack.signum() < 0) return 0;
                } else if (slack.signum() < 0) {
                    BigInteger divisor = coefficient.negate();
                    lower = lower.max(slack.negate().add(divisor).subtract(BigInteger.ONE).divide(divisor));
                }
                BigInteger peak = previous.add(copies.multiply(delta.max(BigInteger.ZERO)));
                if (peak.signum() > 0) upper = upper.min(MAX.subtract(available).divide(peak));
                if (upper.compareTo(lower) < 0) return 0;
                prefix.put(key, previous.add(copies.multiply(delta)));
            }
        }
        return upper.compareTo(lower) < 0 ? 0 : upper.longValueExact();
    }
}
