// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/** Optional alternative orders of one capacity proposal, never a state-family proof. */
final class StateScheduleRepair<K> implements AutoCloseable {
    private final PlanningBudget budget;
    private final long started, allowance;
    private long memory;
    private RecipeCountModel<K> model;
    private CountSchedule<K> schedule, preferred;
    private BigInteger[] counts;
    private int[] order;
    private boolean preferredWitness, ordinaryDone;
    private int candidates;
    private PlanStep witness;

    private StateScheduleRepair(PlanningBudget budget) {
        this.budget = budget;
        started = budget.nodes();
        allowance = Math.min(32_768, budget.remainingWork() / 16);
    }

    static <K> StateScheduleRepair<K> create(Map<String, GraphRecipe<K>> recipes, Map<String, BigInteger> counts,
            Map<K, Long> stock, Set<K> external, String preferred, PlanningBudget budget) {
        if (counts.size() > 128 || budget.remainingWork() < 16_384) return null;
        var work = new StateScheduleRepair<K>(budget);
        boolean retained = false;
        try {
            long bytes = 512L + 128L * counts.size();
            for (var count : counts.values()) { budget.check(); bytes += (count.bitLength() + 7L) / 8; }
            if (!budget.tryReserve(bytes)) return null;
            work.memory = bytes;
            long ports = 0;
            var active = new ArrayList<GraphRecipe<K>>();
            for (var value : counts.entrySet()) {
                budget.check();
                if (value.getValue().signum() <= 0) continue;
                var recipe = recipes.get(value.getKey());
                ports += recipe.inputs().size() + recipe.outputs().size();
                if (ports > 2048) return null;
                if (recipe.id().equals(preferred)) active.add(0, recipe); else active.add(recipe);
            }
            if (active.size() < 2) return null;
            // Original physical stock and every auxiliary port stay in this
            // view. No boundary input becomes free, and no count is changed.
            work.model = RecipeCountModel.forShell(active, Map.of(), stock, external, budget);
            if (work.model == null) return null;
            work.counts = new BigInteger[active.size()];
            work.order = new int[active.size()];
            for (int i = 0; i < work.counts.length; i++) {
                budget.check(); work.counts[i] = counts.get(active.get(i).id()); work.order[i] = i;
            }
            work.preferred = CountSchedule.preferred(work.model, work.counts, work.order, budget);
            retained = true;
            return work;
        } finally { if (!retained) work.close(); }
    }

    boolean step() {
        budget.checkpoint();
        if (!available()) { witness = null; return true; }
        if (preferred != null) {
            if (!preferred.step()) return false;
            witness = preferred.result() == CountSchedule.Result.WITNESS ? preferred.witness() : null;
            if (witness != null) { preferredWitness = true; candidates++; return true; }
            preferred.close(); preferred = null;
        }
        if (ordinaryDone) { witness = null; return true; }
        if (schedule == null) schedule = new CountSchedule<>(model, counts, budget).retainWitnessForAssembly();
        if (!schedule.step()) return false;
        witness = schedule.result() == CountSchedule.Result.WITNESS ? schedule.witness() : null;
        preferredWitness = false;
        if (witness != null) candidates++;
        return true;
    }

    boolean reject(String blocked) {
        witness = null;
        // Include downstream restoration cost in the lifetime allowance. An
        // unsuccessful lift cannot renew this scout or the enclosing arm.
        if (!available() || candidates >= 8) return false;
        if (preferredWitness) { preferred.close(); preferred = null; }
        else if (schedule != null) ordinaryDone = !schedule.retryAfterRejectedWitness();
        // Promote the newly blocked rule, preserving previous failure hints.
        // This only orders a positive scout; no precedence removes other words.
        for (int i = 1; blocked != null && i < order.length; i++) {
            budget.check();
            if (!model.recipes.get(order[i]).id().equals(blocked)) continue;
            int index = order[i];
            System.arraycopy(order, 0, order, 1, i); order[0] = index;
            preferred = CountSchedule.preferred(model, counts, order, budget);
            return true;
        }
        return !ordinaryDone;
    }

    private boolean available() { return model != null && budget.nodes() - started < allowance; }
    PlanStep witness() { return witness; }

    @Override public void close() {
        if (schedule != null) schedule.close(); schedule = null;
        if (preferred != null) preferred.close(); preferred = null;
        if (model != null) model.close(); model = null;
        budget.release(memory); memory = 0;
        witness = null;
    }
}
