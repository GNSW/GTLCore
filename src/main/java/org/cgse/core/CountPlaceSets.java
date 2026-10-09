// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** SAT separation of siphons and traps over every original transition. */
final class CountPlaceSets {

    private CountPlaceSets() {}

    static <K> List<List<K>> separate(RecipeCountModel<K> model, PlanningBudget budget) {
        List<K> keys = model.keys.stream().filter(key -> !model.external.contains(key)).toList();
        if (keys.size() < 3 || keys.size() > 96 || model.recipes.size() > 128 || budget.remainingWork() < 32768) return List.of();
        long start = budget.threadWork(), allowance = Math.min(16384, budget.remainingWork() / 32);
        long bytes = 4096L + 256L * keys.size() * model.recipes.size();
        if (!budget.tryReserve(bytes)) return List.of();
        try {
            var ids = new HashMap<K, Integer>();
            for (int i = 0; i < keys.size(); i++) ids.put(keys.get(i), i);
            var result = new ArrayList<List<K>>();
            for (boolean trap : new boolean[] { false, true }) {
                var rows = new ArrayList<ExactLinearProgram.Constraint>();
                for (var recipe : model.recipes) {
                    var premise = trap ? recipe.inputs() : recipe.outputs();
                    var consequence = trap ? recipe.outputs() : recipe.inputs();
                    for (K place : premise.keySet()) {
                        budget.check();
                        Integer id = ids.get(place);
                        if (id == null) continue;
                        var terms = new TreeMap<Integer, BigInteger>();
                        terms.put(id, BigInteger.ONE);
                        for (K next : consequence.keySet()) {
                            Integer other = ids.get(next);
                            if (other != null) terms.merge(other, BigInteger.ONE.negate(), BigInteger::add);
                        }
                        terms.values().removeIf(BigInteger.ZERO::equals);
                        rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.ZERO));
                    }
                }
                var members = new TreeMap<Integer, BigInteger>();
                for (int i = 0; i < keys.size(); i++) members.put(i, BigInteger.ONE);
                rows.add(new ExactLinearProgram.Constraint(members, BigInteger.valueOf(Math.min(16, keys.size() - 1))));
                var negative = new TreeMap<Integer, BigInteger>();
                members.forEach((i, c) -> negative.put(i, c.negate()));
                rows.add(new ExactLinearProgram.Constraint(negative, BigInteger.TWO.negate()));
                BigInteger[] low = new BigInteger[keys.size()], high = new BigInteger[keys.size()];
                Arrays.fill(low, BigInteger.ZERO);
                Arrays.fill(high, BigInteger.ONE);
                for (int attempt = 0; attempt < 4 && budget.threadWork() - start < allowance; attempt++) {
                    BigInteger[] selected;
                    try (var search = new CountCdcl(rows, low, high, budget, Math.min(4096, allowance - (budget.threadWork() - start)))) {
                        while (!search.step()) { /* Small bounded Boolean separation; no arithmetic domain expansion. */ }
                        selected = search.counts();
                    }
                    if (selected == null) break;
                    List<K> pool = new ArrayList<>();
                    var block = new TreeMap<Integer, BigInteger>();
                    for (int i = 0; i < keys.size(); i++) if (selected[i].signum() != 0) {
                        pool.add(keys.get(i));
                        block.put(i, BigInteger.ONE);
                    }
                    if (valid(model.recipes, new HashSet<>(pool), trap, budget)) result.add(List.copyOf(pool));
                    rows.add(new ExactLinearProgram.Constraint(block, BigInteger.valueOf(pool.size() - 1L)));
                }
            }
            budget.note("count_place_sets", "siphon_trap_pools=" + result.size() + "; all_original_sources_checked; work=" + (budget.threadWork() - start));
            return List.copyOf(result);
        } finally {
            budget.release(bytes);
        }
    }

    static <K> boolean valid(List<GraphRecipe<K>> recipes, Set<K> pool, boolean trap, PlanningBudget budget) {
        if (pool.isEmpty()) return false;
        for (var recipe : recipes) {
            budget.check();
            boolean before = (trap ? recipe.inputs() : recipe.outputs()).keySet().stream().anyMatch(pool::contains);
            boolean after = (trap ? recipe.outputs() : recipe.inputs()).keySet().stream().anyMatch(pool::contains);
            if (before && !after) return false;
        }
        return true;
    }
}
