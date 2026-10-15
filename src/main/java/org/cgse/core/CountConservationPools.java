// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Boolean separation of non-increasing resource sums that disable a first firing. */
final class CountConservationPools {
    private CountConservationPools() {}

    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }

    static <K> List<List<K>> separate(RecipeCountModel<K> model, PlanningBudget budget) {
        if (model.keys.size() > 96 || model.recipes.size() > 128 ||
                model.recipes.stream().anyMatch(GraphRecipe::batchSensitiveInputs)) return List.of();
        long allowance = Math.min(65536, budget.remainingWork() / 32);
        if (allowance < 2048) return List.of();
        var keys = model.keys.stream().filter(key -> !model.external.contains(key)).toList();
        if (keys.size() < 2) return List.of();
        long incidences = model.recipes.stream().mapToLong(r -> (long) r.inputs().size() + r.outputs().size()).sum();
        long bytes = 4096L + 512L * keys.size() + 256L * model.recipes.size() +
                192L * incidences + 96L * keys.size() * model.recipes.size();
        if (!budget.tryReserve(bytes)) return List.of();
        long started = budget.threadWork();
        Runnable tick = () -> {
            if (budget.threadWork() - started >= allowance) throw new Stop();
            budget.check();
        };
        var result = new ArrayList<List<K>>();
        try {
            var ids = new HashMap<K, Integer>();
            for (int i = 0; i < keys.size(); i++) { tick.run(); ids.put(keys.get(i), i); }
            var base = new ArrayList<ExactLinearProgram.Constraint>();
            for (var recipe : model.recipes) {
                var row = new TreeMap<Integer, BigInteger>();
                for (var e : recipe.inputs().entrySet()) {
                    tick.run();
                    Integer id = ids.get(e.getKey());
                    if (id != null) row.merge(id, BigInteger.valueOf(e.getValue()).negate(), BigInteger::add);
                }
                for (var e : recipe.outputs().entrySet()) {
                    tick.run();
                    Integer id = ids.get(e.getKey());
                    if (id != null) row.merge(id, BigInteger.valueOf(e.getValue()), BigInteger::add);
                }
                row.values().removeIf(BigInteger.ZERO::equals);
                // y >= 0 and y.delta_r <= 0 for EVERY original recipe.
                base.add(new ExactLinearProgram.Constraint(row, BigInteger.ZERO));
            }
            BigInteger[] low = new BigInteger[keys.size()], high = new BigInteger[keys.size()];
            Arrays.fill(low, BigInteger.ZERO);
            Arrays.fill(high, BigInteger.ONE);
            for (var recipe : model.recipes) {
                tick.run();
                if (recipe.inputs().size() < 2) continue;
                boolean explained = false;
                for (var pool : result) {
                    BigInteger deficit = BigInteger.ZERO;
                    for (K key : pool) {
                        tick.run();
                        deficit = deficit.add(BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L)))
                                .subtract(BigInteger.valueOf(model.stock.getOrDefault(key, 0L)));
                    }
                    if (deficit.signum() > 0) { explained = true; break; }
                }
                if (explained) continue;
                var terms = new TreeMap<Integer, BigInteger>();
                for (int i = 0; i < keys.size(); i++) {
                    tick.run();
                    K key = keys.get(i);
                    var a = BigInteger.valueOf(model.stock.getOrDefault(key, 0L))
                            .subtract(BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L)));
                    if (a.signum() != 0) terms.put(i, a);
                }
                if (terms.values().stream().noneMatch(a -> a.signum() < 0)) continue;
                var rows = new ArrayList<>(base);
                rows.add(new ExactLinearProgram.Constraint(terms, BigInteger.ONE.negate()));
                tick.run();
                long grant = Math.min(8192, allowance - (budget.threadWork() - started));
                if (grant < 256) break;
                try (var search = new CountCdcl(rows, low, high, budget, grant)) {
                    while (!search.step()) { /* bounded separator; UNKNOWN contributes no pool */ }
                    var selected = search.counts();
                    if (selected == null) continue;
                    // Independently check the complete support before publication.
                    // A heuristic/projection never removes an original transition.
                    for (var row : rows) {
                        BigInteger value = BigInteger.ZERO;
                        for (var term : row.terms().entrySet()) {
                            tick.run();
                            value = value.add(term.getValue().multiply(selected[term.getKey()]));
                        }
                        if (value.compareTo(row.upper()) > 0) throw new IllegalStateException("Invalid conservation pool");
                    }
                    var pool = new ArrayList<K>();
                    for (int i = 0; i < keys.size(); i++) {
                        tick.run();
                        if (selected[i].signum() > 0) pool.add(keys.get(i));
                    }
                    result.add(List.copyOf(pool));
                    if (result.size() == 8) break;
                }
            }
        } catch (Stop ignored) {
            // Keep only completed certificates; a partial separator is not a proof.
        } finally {
            budget.note("count_conservation_pools", "verified=" + result.size() + "; work=" + (budget.threadWork() - started));
            budget.release(bytes);
        }
        return List.copyOf(result);
    }
}
