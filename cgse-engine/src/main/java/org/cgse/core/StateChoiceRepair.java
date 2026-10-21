// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Candidate-local feedback about a later rule that lost eligible entities or uses.
 * Scores only order choices: no entity is reserved, excluded or manufactured,
 * and no precedence, count cut or negative theorem leaves this helper. */
final class StateChoiceRepair<K> implements AutoCloseable {
    private record Choice<K>(String rule, K state) {}

    private final PlanningBudget budget;
    private final long started, allowance;
    private final List<StateFamily<K>> protectedRules = new ArrayList<>();
    private final Map<Choice<K>, Integer> scores = new HashMap<>();
    private StateFamily<K> costRule, witnessedCostRule;
    private boolean costTried;
    private long memory = 512;

    static <K> StateChoiceRepair<K> create(PlanningBudget budget) {
        if (budget.remainingWork() < 16_384 || !budget.tryReserve(512)) return null;
        return new StateChoiceRepair<>(budget);
    }

    private StateChoiceRepair(PlanningBudget budget) {
        this(budget, budget.nodes(), Math.min(32_768, budget.remainingWork() / 16));
    }

    private StateChoiceRepair(PlanningBudget budget, long started, long allowance) {
        this.budget = budget; this.started = started; this.allowance = allowance;
    }

    boolean active() { return memory > 0 && budget.nodes() - started < allowance; }

    long remainingWork() { return memory == 0 ? 0 : Math.max(0, allowance - (budget.nodes() - started)); }

    /** Isolated preferences for a stock-support replay, sharing the original
     * lifetime. Failed feedback must not mutate the parent's retained prefix. */
    StateChoiceRepair<K> fork() {
        long bytes = 512L + 128L * protectedRules.size();
        if (!active() || !budget.tryReserve(bytes)) return null;
        try {
            var copy = new StateChoiceRepair<K>(budget, started, allowance);
            copy.memory = bytes; copy.protectedRules.addAll(protectedRules);
            // A quantified preference that failed on the old support may work
            // with new physical tools. Reapply it without renewing that trial.
            copy.costRule = costRule != null ? costRule : witnessedCostRule;
            copy.witnessedCostRule = witnessedCostRule; copy.costTried = costTried;
            return copy;
        } catch (RuntimeException | Error failure) { budget.release(bytes); throw failure; }
    }

    /** The lifetime bounds choices and speculative restoration. A fully restored
     * support word may finish verification under its original parent deadline. */
    boolean protect(StateFamily<K> failed) {
        return protect(failed, false);
    }

    boolean protectSecondary(StateFamily<K> affected) { return protect(affected, true); }

    private boolean protect(StateFamily<K> failed, boolean secondary) {
        budget.checkpoint();
        if (!active() || protectedRules.size() == 8) return false;
        for (var rule : protectedRules) {
            budget.check(); if (rule.id().equals(failed.id())) return false;
        }
        if (!budget.tryReserve(128)) return false;
        memory += 128;
        if (secondary) protectedRules.add(failed); else protectedRules.add(0, failed);
        long bytes = 160L * scores.size(); scores.clear(); memory -= bytes; budget.release(bytes);
        return true;
    }

    /** Computed before allocation sorting, so a limit cannot change comparator
     * ordering halfway through a sort. Exact returns are captured immutable data. */
    int score(StateFamily<K> used, K state) {
        budget.check();
        var choice = new Choice<>(used.id(), state);
        Integer old = scores.get(choice);
        if (old != null) return old;
        K after = used.nominalAfterOne(state);
        int lost = 0, worn = 0;
        for (var rule : protectedRules) {
            budget.check(); lost <<= 1; worn <<= 1;
            long before = rule.accepts(state) ? rule.capacity(state) : 0;
            long retained = after != null && rule.accepts(after) ? rule.capacity(after) : 0;
            if (before > 0 && retained <= 0) lost++;
            else if (retained > 0 && retained < before) worn++;
        }
        // Preserve the old eligibility ordering before breaking its ties with
        // guaranteed-use loss. This is one exact transition, not a forecast of
        // arbitrary state trajectories or a reservation of future capacity.
        int score = (lost << 8) | worn;
        if (scores.size() < 512 && budget.tryReserve(160)) {
            scores.put(choice, score); memory += 160;
        }
        return score;
    }

    /** One additional same-word trial when qualitative feedback cannot
     * distinguish unequal damage to a repeatedly blocked rule. Selected
     * activation keys are samples for admission, never free inventory. */
    boolean refineCosts(StateFamily<K> failed, List<StateFamily<K>> used, List<K> states) {
        if (costTried || !active() || used.size() > 8 || states.size() > 256) return false;
        boolean protectedAlready = false;
        for (var rule : protectedRules) {
            budget.check(); protectedAlready |= rule.id().equals(failed.id());
        }
        if (!protectedAlready) return false;
        costTried = true;
        long limit = Math.min(1024, (allowance - (budget.nodes() - started)) / 8);
        if (limit < 64) return false;
        long bytes = 256L + 96L * states.size();
        if (!budget.tryReserve(bytes)) return false;
        long start = budget.nodes();
        try {
            var seen = new HashMap<Integer, Long>();
            for (var rule : used) {
                if (rule.id().equals(failed.id())) continue;
                seen.clear();
                for (K state : states) {
                    // Leave enough room for a complete immutable score.
                    if (budget.nodes() - start + protectedRules.size() + 2 > limit) return false;
                    budget.check();
                    if (!rule.accepts(state) || rule.capacity(state) <= 0) continue;
                    int category = score(rule, state);
                    long loss = loss(failed, rule, state);
                    Long previous = seen.putIfAbsent(category, loss);
                    if (previous != null && previous.longValue() != loss) {
                        costRule = failed; witnessedCostRule = failed; return true;
                    }
                }
            }
            return false;
        } finally { budget.release(bytes); }
    }

    boolean costsEnabled() { return costRule != null; }

    boolean canRefineCosts() { return !costTried && active(); }

    /** An unsuccessful cost trial must leave the old preference path intact. */
    void finishCosts() { costRule = null; }

    /** Exact nonnegative marginal loss; no sum, int narrowing or floating point.
     * This only breaks ties in the existing eligibility/wear preference. */
    long cost(StateFamily<K> used, K state) {
        budget.check();
        return costRule == null ? 0 : loss(costRule, used, state);
    }

    private long loss(StateFamily<K> protectedRule, StateFamily<K> used, K state) {
        long before = protectedRule.accepts(state) ? Math.max(0, protectedRule.capacity(state)) : 0;
        K after = used.nominalAfterOne(state);
        long retained = after != null && protectedRule.accepts(after) ? Math.max(0, protectedRule.capacity(after)) : 0;
        return before > retained ? before - retained : 0;
    }

    @Override public void close() {
        protectedRules.clear(); scores.clear(); costRule = null; witnessedCostRule = null; budget.release(memory); memory = 0;
    }
}
