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
    private long[] predecessors;
    private int[] unhintedOrder;
    private boolean preferredWitness, ordinaryDone;
    private boolean feedbackTried, hintsTried, hintedWitness;
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
            if (witness != null) {
                preferredWitness = true; hintedWitness = unhintedOrder != null;
                if (!hintedWitness) candidates++;
                return true;
            }
            preferred.close(); preferred = null;
            if (unhintedOrder != null) {
                order = unhintedOrder; unhintedOrder = null;
                preferred = CountSchedule.preferred(model, counts, order, budget);
                return false;
            }
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
        if (hintedWitness) {
            // The single structural word cannot discard the plain preferred
            // word it interrupted. Both consume the same lifetime allowance.
            hintedWitness = false;
            order = unhintedOrder; unhintedOrder = null;
            preferred = CountSchedule.preferred(model, counts, order, budget);
            return true;
        }
        // Promote the newly blocked rule, then retain compatible earlier
        // interference hints. Neither preference removes a legal execution.
        boolean changed = false;
        for (int i = 1; blocked != null && i < order.length; i++) {
            budget.check();
            if (!model.recipes.get(order[i]).id().equals(blocked)) continue;
            int index = order[i];
            System.arraycopy(order, 0, order, 1, i); order[0] = index;
            changed = true; break;
        }
        if (rankWithHints()) changed = true;
        if (changed) {
            preferred = CountSchedule.preferred(model, counts, order, budget);
            return true;
        }
        return !ordinaryDone;
    }

    /** Exact single-step interference suggests trying another variant first.
     * This is a candidate preference, never a precedence constraint or proof. */
    boolean preferBefore(String before, String after) {
        if (!available() || order.length > 64 || before.equals(after)) return false;
        int from = -1, to = -1;
        for (int i = 0; i < model.recipes.size(); i++) {
            budget.check(); String id = model.recipes.get(i).id();
            if (id.equals(before)) from = i;
            if (id.equals(after)) to = i;
        }
        if (from < 0 || to < 0) return false;
        if (predecessors == null) {
            // Includes the bounded temporary ranking array and array headers.
            long bytes = 128L + 32L * order.length;
            if (!budget.tryReserve(bytes)) return false;
            memory += bytes; predecessors = new long[order.length];
        }
        long bit = 1L << from;
        if ((predecessors[to] & bit) != 0) return false;
        predecessors[to] |= bit;
        return true;
    }

    /** A single bounded structural pass after repeated plain promotion failures.
     * Its work is part of this continuation's original lifetime allowance. */
    long beginFeedback(int minimumCandidates) {
        if (feedbackTried || !available() || candidates < minimumCandidates || candidates >= 8 || order.length > 64) return 0;
        feedbackTried = true;
        long limit = Math.min(4096, (allowance - (budget.nodes() - started)) / 4);
        return limit >= 256 ? limit : 0;
    }

    private boolean rankWithHints() {
        if (predecessors == null || hintsTried || !available()) return false;
        hintsTried = true;
        int[] ranked = new int[order.length];
        long remaining = order.length == 64 ? -1L : (1L << order.length) - 1;
        boolean changed = false;
        for (int position = 0; position < order.length; position++) {
            if (!available()) return false;
            int next = -1;
            for (int index : order) {
                budget.check();
                if ((remaining & (1L << index)) != 0 && (predecessors[index] & remaining) == 0) { next = index; break; }
            }
            // Conflicting hints can reflect interleaving or different tool
            // choices. Keep the ordinary promotion; do not infer a deadlock.
            if (next < 0) return false;
            ranked[position] = next; remaining &= ~(1L << next);
            changed |= next != order[position];
        }
        if (changed) { unhintedOrder = order; order = ranked; }
        return changed;
    }

    private boolean available() { return model != null && budget.nodes() - started < allowance; }
    PlanStep witness() { return witness; }

    @Override public void close() {
        if (schedule != null) schedule.close(); schedule = null;
        if (preferred != null) preferred.close(); preferred = null;
        if (model != null) model.close(); model = null;
        predecessors = null; unhintedOrder = null;
        budget.release(memory); memory = 0;
        witness = null;
    }
}
