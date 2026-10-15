// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Admission hint for bounded choices hidden behind aliases and private chains. */
final class CountRecoveryShape {
    private record Signature<K>(Map<K, Long> inputs, Map<K, Long> outputs) {}

    private CountRecoveryShape() {}

    static <K> boolean compact(RecipeCountModel<K> model, BigInteger[] lower, BigInteger[] upper,
                               PlanningBudget budget) {
        int size = model.recipes.size(), free = 0;
        if (size <= 128 || size > 8192 || model.keys.size() > 8192) return false;
        for (int i = 0; i < size; i++) {
            budget.check();
            if (upper[i] == null || upper[i].compareTo(lower[i]) < 0) return false;
            if (!upper[i].equals(lower[i])) free++;
        }
        if (free <= 128) return false;
        long entries = 0;
        for (var recipe : model.recipes) {
            budget.check();
            entries += recipe.inputs().size() + recipe.outputs().size();
        }
        // This is only a small scheduling observation, not another compiler.
        // Refuse dense/low-budget inputs without spending a search turn on it.
        long cost = 8L * size + 4L * entries + model.keys.size();
        if (entries > 65536 || cost > budget.remainingWork() / 16) return false;
        long bytes = 1024L + 192L * size + 64L * model.keys.size();
        if (!budget.tryReserve(bytes)) return false;
        try {
            int[] representative = new int[size], parent = new int[size];
            boolean[] ordinary = new boolean[size], active = new boolean[size];
            var signatures = new HashMap<Signature<K>, Integer>();
            for (int i = 0; i < size; i++) {
                var recipe = model.recipes.get(i);
                budget.charge(1L + recipe.inputs().size() + recipe.outputs().size());
                ordinary[i] = recipe.configurationInputs().isEmpty() && recipe.reusableInputs().isEmpty();
                Integer alias = ordinary[i] ? signatures.putIfAbsent(new Signature<>(recipe.inputs(), recipe.outputs()), i) : null;
                representative[i] = alias == null ? i : alias;
                parent[i] = i;
            }
            int[] producers = new int[model.keys.size()], consumers = new int[model.keys.size()];
            Arrays.fill(producers, -1);
            Arrays.fill(consumers, -1);
            for (int i = 0; i < size; i++) {
                budget.check();
                if (representative[i] != i) continue;
                var recipe = model.recipes.get(i);
                for (K key : recipe.inputs().keySet()) {
                    budget.check();
                    Integer row = model.ids.get(key);
                    if (row == null) continue; // Boundary ports outside the backward closure.
                    consumers[row] = consumers[row] == -1 ? i : -2;
                }
                for (K key : recipe.outputs().keySet()) {
                    budget.check();
                    Integer row = model.ids.get(key);
                    if (row == null) continue;
                    producers[row] = producers[row] == -1 ? i : -2;
                }
            }
            for (int row = 0; row < model.keys.size(); row++) {
                budget.check();
                int first = producers[row], last = consumers[row];
                K key = model.keys.get(row);
                if (first < 0 || last < 0 || first == last || !ordinary[first] || !ordinary[last] ||
                        model.goal(key).signum() != 0 || model.external.contains(key)) continue;
                var start = model.recipes.get(first);
                var finish = model.recipes.get(last);
                // Count only unbranched seams. Joint inputs/outputs stay at the
                // route interface; competing producers/consumers remain choices.
                if (start.outputs().size() != 1 || finish.inputs().size() != 1 ||
                        start.inputs().containsKey(key) || finish.outputs().containsKey(key)) continue;
                int a = root(parent, first), b = root(parent, last);
                if (a != b) parent[Math.max(a, b)] = Math.min(a, b);
            }
            int groups = 0;
            for (int i = 0; i < size; i++) {
                budget.check();
                if (upper[i].equals(lower[i])) continue;
                int group = root(parent, representative[i]);
                if (!active[group]) {
                    active[group] = true;
                    groups++;
                }
            }
            boolean useful = groups >= 4 && groups <= 128 && 4L * groups <= free;
            if (useful) budget.note("count_recovery_shape", "bounded_counts=" + free + "; route_groups=" + groups);
            // This estimate never merges variables or proves a deficit. The
            // full count search builds its own recoverable candidate view and
            // independently verifies every lifted primitive execution prefix.
            return useful;
        } finally {
            budget.release(bytes);
        }
    }

    private static int root(int[] parent, int id) {
        while (parent[id] != id) {
            parent[id] = parent[parent[id]];
            id = parent[id];
        }
        return id;
    }
}
