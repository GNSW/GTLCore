// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;

/** Request-local execution search over all original sources, without count-branch assumptions. */
final class CountReachability<K> implements AutoCloseable {
    private final RecipeCountModel<K> model;
    private final ForwardCoverability<K> search;
    private long memory;
    private PlanCountComputation counting;
    private BigInteger[] counts;
    private boolean complete;

    static <K> CountReachability<K> create(RecipeCountModel<K> model, long maximumWork) {
        if (model.recipes.size() > 64 || maximumWork < 1024 ||
                model.budget.remainingWork() < 32768 || model.recipes.stream().anyMatch(GraphRecipe::batchSensitiveInputs))
            return null;
        // Covers summaries, dense proof vectors, counts and construction scratch;
        // the retained state frontier makes its own incremental reservations.
        long bytes = 4096L + 512L * (model.keys.size() + model.recipes.size()) +
                512L * Math.min(32, model.keys.size()) * model.recipes.size();
        if (!model.budget.tryReserve(bytes)) return null;
        try {
            // Many ordinary cycles have a small changing state, surrounded by
            // dozens of base inputs and waste outputs. Admit that proof support
            // rather than the complete material catalogue. Removing a coordinate
            // relaxes its enabling conditions; it can never remove a real firing.
            // Keep every original recipe, including alternative producers.
            var produced = new HashSet<K>();
            var consumed = new HashSet<K>();
            int finite = 0;
            for (var key : model.keys) {
                model.budget.check();
                if (!model.external.contains(key)) finite++;
            }
            if (finite > 32) for (var recipe : model.recipes) {
                model.budget.charge(1L + recipe.inputs().size() + recipe.outputs().size());
                produced.addAll(recipe.outputs().keySet());
                consumed.addAll(recipe.inputs().keySet());
            }
            var keys = new ArrayList<K>();
            var initial = new ArrayList<BigInteger>();
            var goal = new ArrayList<BigInteger>();
            for (var key : model.keys) {
                model.budget.check();
                // Infinite supply is projected away. This relaxes reachability,
                // so failure of the projected problem is still a sound proof.
                if (model.external.contains(key)) continue;
                if (finite > 32 && model.goal(key).signum() == 0 &&
                        !(produced.contains(key) && consumed.contains(key))) continue;
                keys.add(key);
                initial.add(BigInteger.valueOf(model.stock.getOrDefault(key, 0L)));
                goal.add(model.goal(key));
            }
            if (keys.size() > 32) {
                model.budget.release(bytes);
                return null;
            }
            model.budget.note("count_reachability_support", "materials=" + model.keys.size() +
                    "; tracked=" + keys.size() + "; recipes=" + model.recipes.size() + "; relaxed=" + (finite > keys.size()));
            var actions = new ArrayList<BackwardCoverability.Action<K>>();
            for (var recipe : model.recipes) {
                model.budget.charge(1L + recipe.inputs().size() + recipe.outputs().size());
                actions.add(new BackwardCoverability.Action<>(new PlanStep.Batch(recipe.id(), 1), SequenceSummary.recipe(recipe)));
            }
            var search = new ForwardCoverability<>(model.recipes, keys, initial, goal, actions, model.budget, maximumWork, false);
            return new CountReachability<>(model, search, bytes);
        } catch (RuntimeException | Error failure) {
            model.budget.release(bytes);
            throw failure;
        }
    }

    private CountReachability(RecipeCountModel<K> model, ForwardCoverability<K> search, long memory) {
        this.model = model;
        this.search = search;
        this.memory = memory;
    }

    boolean step() {
        if (complete) return true;
        if (!search.step()) return false;
        if (search.result() != BackwardCoverability.Result.WITNESS) return complete = true;
        if (counting == null) counting = new PlanCountComputation(search.witness());
        // Count a retained program cooperatively and charge repeated call edges,
        // not just the number of distinct recipes in the finished count vector.
        if (!counting.step(model.budget)) return false;
        var values = counting.result();
        counts = new BigInteger[model.recipes.size()];
        for (int i = 0; i < counts.length; i++) {
            model.budget.check();
            counts[i] = values.getOrDefault(model.recipes.get(i).id(), BigInteger.ZERO);
        }
        counting = null;
        return complete = true;
    }

    BackwardCoverability.Result result() { return complete ? search.result() : null; }

    PlanStep witness() { return search.witness(); }

    BigInteger[] counts() { return counts; }

    @Override
    public void close() {
        search.close();
        counting = null;
        counts = null;
        model.budget.release(memory);
        memory = 0;
    }
}
