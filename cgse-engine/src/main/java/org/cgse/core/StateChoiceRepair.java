// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Candidate-local feedback about a later rule that lost eligible entities.
 * Scores only order choices: no entity is reserved, excluded or manufactured,
 * and no precedence, count cut or negative theorem leaves this helper. */
final class StateChoiceRepair<K> implements AutoCloseable {
    private record Choice<K>(String rule, K state) {}

    private final PlanningBudget budget;
    private final long started, allowance;
    private final List<StateFamily<K>> protectedRules = new ArrayList<>();
    private final Map<Choice<K>, Integer> scores = new HashMap<>();
    private long memory = 512;

    static <K> StateChoiceRepair<K> create(PlanningBudget budget) {
        if (budget.remainingWork() < 16_384 || !budget.tryReserve(512)) return null;
        return new StateChoiceRepair<>(budget);
    }

    private StateChoiceRepair(PlanningBudget budget) {
        this.budget = budget; started = budget.nodes();
        allowance = Math.min(32_768, budget.remainingWork() / 16);
    }

    boolean active() { return memory > 0 && budget.nodes() - started < allowance; }

    /** The lifetime allowance includes subsequent restoration and verification. */
    boolean protect(StateFamily<K> failed) {
        budget.checkpoint();
        if (!active() || protectedRules.size() == 8) return false;
        for (var rule : protectedRules) {
            budget.check(); if (rule.id().equals(failed.id())) return false;
        }
        if (!budget.tryReserve(128)) return false;
        memory += 128; protectedRules.add(0, failed);
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
        int score = 0;
        for (var rule : protectedRules) {
            budget.check(); score <<= 1;
            boolean before = rule.accepts(state) && rule.capacity(state) > 0;
            boolean retained = after != null && rule.accepts(after) && rule.capacity(after) > 0;
            if (before && !retained) score++;
        }
        if (scores.size() < 512 && budget.tryReserve(160)) {
            scores.put(choice, score); memory += 160;
        }
        return score;
    }

    @Override public void close() {
        protectedRules.clear(); scores.clear(); budget.release(memory); memory = 0;
    }
}
