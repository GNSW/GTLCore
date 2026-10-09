// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Request-local deterministic suffix, expressed in original recipe coordinates.
 * Its fixed counts select one completion, not all solutions of the source model.
 * Only a separate witness probe may use them; rejection never proves infeasibility.
 */
final class CountShellCompilation<K> implements AutoCloseable {

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private final PlanningBudget budget;
    private final long started, allowance;
    private final BigInteger[] fixed;
    private final List<Integer> suffix = new ArrayList<>();
    private Map<K, BigInteger> remainingGoals;
    private boolean targetPeeled;
    private long memory;
    private int peeled;

    private CountShellCompilation(RecipeCountModel<K> model, long bytes) {
        budget = model.budget;
        started = budget.threadWork();
        allowance = Math.min(131072, budget.remainingWork() / 32);
        fixed = new BigInteger[model.recipes.size()];
        memory = bytes;
    }

    static <K> CountShellCompilation<K> create(RecipeCountModel<K> model, K target) {
        var budget = model.budget;
        if (budget.remainingWork() < 65536) return null;
        long entries = 0;
        for (var recipe : model.recipes) entries += recipe.inputs().size() + recipe.outputs().size();
        // Include incidence maps, pending demands and 1024-bit fixed counts.
        long bytes = 2048L + 1024L * model.recipes.size() + 256L * entries;
        if (!budget.tryReserve(bytes)) return null;
        CountShellCompilation<K> result = null;
        try {
            result = new CountShellCompilation<>(model, bytes);
            result.compile(model, target);
            return result;
        } catch (Stop stopped) {
            if (result != null) result.close();
            else budget.release(bytes);
            return null;
        } catch (RuntimeException | Error failure) {
            if (result != null) result.close();
            else budget.release(bytes);
            throw failure;
        }
    }

    private void compile(RecipeCountModel<K> model, K target) {
        Map<K, Integer> producer = new HashMap<>(), consumers = new HashMap<>();
        Map<K, BigInteger> demand = new HashMap<>(model.goals);
        for (int i = 0; i < model.recipes.size(); i++) {
            var recipe = model.recipes.get(i);
            for (K key : recipe.outputs().keySet()) {
                check();
                producer.merge(key, i, (first, second) -> -1);
            }
            for (K key : recipe.inputs().keySet()) {
                check();
                consumers.merge(key, 1, Integer::sum);
            }
        }
        Deque<Integer> pending = new ArrayDeque<>();
        for (int i = 0; i < model.recipes.size(); i++) {
            check();
            var recipe = model.recipes.get(i);
            if (eligible(recipe) && consumers.getOrDefault(recipe.outputs().keySet().iterator().next(), 0) == 0)
                pending.add(i);
        }
        while (!pending.isEmpty()) {
            check();
            int id = pending.removeFirst();
            if (fixed[id] != null) continue;
            var recipe = model.recipes.get(id);
            if (!eligible(recipe)) continue;
            K output = recipe.outputs().keySet().iterator().next();
            if (producer.getOrDefault(output, -1) != id || consumers.getOrDefault(output, 0) != 0 ||
                    model.external.contains(output) || !output.equals(target) &&
                            (model.goal(output).signum() > 0 || model.stock.getOrDefault(output, 0L) > 0))
                continue;
            BigInteger produced = BigInteger.valueOf(recipe.outputs().get(output));
            BigInteger needed = demand.getOrDefault(output, BigInteger.ZERO)
                    .subtract(BigInteger.valueOf(model.stock.getOrDefault(output, 0L))).max(BigInteger.ZERO);
            BigInteger runs = CheckedAmounts.ceilDiv(needed, produced).max(CheckedAmounts.ceilDiv(
                    model.productionGoals.getOrDefault(output, BigInteger.ZERO), produced));
            bounded(runs);
            fixed[id] = runs;
            peeled++;
            targetPeeled |= output.equals(target);
            suffix.add(id);
            demand.remove(output);
            for (var input : recipe.inputs().entrySet()) {
                check();
                K key = input.getKey();
                BigInteger next = demand.getOrDefault(key, BigInteger.ZERO)
                        .add(BigInteger.valueOf(input.getValue()).multiply(runs));
                bounded(next);
                demand.put(key, next);
                int remaining = consumers.merge(key, -1, Integer::sum);
                Integer upstream = producer.get(key);
                if (remaining == 0 && upstream != null && upstream >= 0) pending.addLast(upstream);
            }
        }
        remainingGoals = Map.copyOf(demand);
        budget.note("count_shell_compile", "recipes=" + fixed.length + "; peeled=" + peeled +
                "; kernel_recipes=" + (fixed.length - peeled) + "; scope=optional_original_coordinates");
    }

    private static boolean eligible(GraphRecipe<?> recipe) {
        return recipe.outputs().size() == 1 && recipe.configurationInputs().isEmpty() &&
                recipe.reusableInputs().isEmpty() && Collections.disjoint(recipe.inputs().keySet(), recipe.outputs().keySet());
    }

    private void check() {
        budget.check();
        if (budget.threadWork() - started >= allowance) throw new Stop();
    }

    private void bounded(BigInteger value) {
        if (value.bitLength() > 1024) throw new Stop();
        budget.operation(PlanningBudget.Operation.INTEGER, value.bitLength());
    }

    int peeled() {
        return peeled;
    }

    boolean targetPeeled() {
        return targetPeeled;
    }

    RecipeCountModel<K> residual(RecipeCountModel<K> source) {
        List<GraphRecipe<K>> recipes = new ArrayList<>();
        for (int i = 0; i < fixed.length; i++) {
            budget.check();
            if (fixed[i] == null) recipes.add(source.recipes.get(i));
        }
        return RecipeCountModel.forShell(recipes, remainingGoals, source.stock, source.external, budget);
    }

    BigInteger[] restoreAndCheck(RecipeCountModel<K> source, BigInteger[] candidate) {
        if (candidate == null || candidate.length != fixed.length - peeled) return null;
        BigInteger[] result = fixed.clone();
        int next = 0;
        for (int i = 0; i < result.length; i++) {
            budget.check();
            if (result[i] == null) result[i] = candidate[next++];
            if (result[i].signum() < 0) return null;
        }
        for (var row : source.constraints) {
            BigInteger total = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.operation(PlanningBudget.Operation.INTEGER, term.getValue().bitLength());
                total = total.add(term.getValue().multiply(result[term.getKey()]));
            }
            if (total.compareTo(row.upper()) > 0) return null;
        }
        return result;
    }

    PlanStep lift(RecipeCountModel<K> source, PlanStep prefix) {
        List<PlanStep> children = new ArrayList<>();
        children.add(prefix);
        for (int i = suffix.size() - 1; i >= 0; i--) {
            budget.check();
            int id = suffix.get(i);
            if (fixed[id].signum() > 0) children.add(PlanStep.batch(source.recipes.get(id).id(), fixed[id]));
        }
        return new PlanStep.Sequence(children);
    }

    BigInteger fixed(int recipe) {
        return fixed[recipe];
    }

    @Override
    public void close() {
        suffix.clear();
        remainingGoals = Map.of();
        budget.release(memory);
        memory = 0;
    }
}
