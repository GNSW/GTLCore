// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded private LCG search with exact short-equality supports. No proof export. */
final class CountSupportedSearch implements CountContinuation {
    private static final long LIMIT = 1_048_576, EXPAND_AFTER = LIMIT / 4;
    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private CountEqualitySupport support;
    private CountLcg search;
    private BigInteger[] counts;
    private long work, allowance;
    private boolean complete, paused, expandPending;

    private CountSupportedSearch(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                                 PlanningBudget budget) {
        this.rows = rows; this.lower = lower; this.upper = upper; this.budget = budget;
    }

    static CountSupportedSearch create(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                                       PlanningBudget budget, long quantum) {
        return create(rows, lower, upper, budget, quantum, false);
    }

    static CountSupportedSearch create(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                                       PlanningBudget budget, long quantum, boolean domains) {
        // Its private discrete explanations do not have an exported proof
        // format. Proof tasks retain the existing independently checked arms.
        if (budget.proofJournal() != null || quantum < 1024 || budget.remainingWork() < 16384) return null;
        var result = new CountSupportedSearch(rows, lower, upper, budget);
        long before = budget.threadWork();
        try {
            result.support = CountEqualitySupport.create(rows, lower, upper, budget);
            if (result.support == null) return null;
            result.search = new CountLcg(rows, lower, upper, budget, quantum);
            result.search.supportEqualities(result.support);
            if (domains && !result.search.supportDomains()) { result.close(); return null; }
            result.expandPending = domains;
            result.allowance = Math.min(LIMIT, quantum);
            result.paused = true;
            return result;
        } catch (RuntimeException | Error failure) {
            result.close();
            throw failure;
        } finally { result.work += budget.threadWork() - before; }
    }

    @Override public boolean step() {
        if (complete || paused) return true;
        budget.checkpoint();
        if (work >= LIMIT) { complete = true; close(); return true; }
        if (work >= allowance) return paused = true;
        long before = budget.threadWork();
        try {
            if (search.paused()) search.resume(Math.max(1, allowance - work));
            if (!search.step()) return false;
            if (search.paused()) return false;
            counts = search.counts();
            complete = true;
            close();
            return true;
        } finally { work += budget.threadWork() - before; }
    }

    @Override public boolean paused() { return paused; }
    @Override public void resume(long quantum) {
        if (!paused || complete) throw new IllegalStateException("Support search is not paused");
        // Strengthen only at a handoff, after this frontier's initial owned
        // work. Keep phase checks out of the common atomic step. Capping the
        // first grant also makes one large grant observe the same transition.
        if (expandPending && work >= EXPAND_AFTER) {
            long before = budget.threadWork();
            try {
                expandPending = false;
                search.expandEqualityDomains();
            } finally { work += budget.threadWork() - before; }
        }
        allowance = Math.min(expandPending ? EXPAND_AFTER : LIMIT, CountContinuation.deadline(work, quantum, budget));
        paused = false;
    }
    @Override public BigInteger[] counts() { return counts == null ? null : counts.clone(); }
    @Override public boolean matches(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {
        return this.rows == rows && Arrays.equals(lower, low) && Arrays.equals(upper, high);
    }
    @Override public long work() { return work; }
    @Override public long progress() { return search == null ? 0 : search.progress(); }
    @Override public void close() {
        complete = true;
        paused = false;
        if (search != null) search.close();
        if (support != null) support.close();
        search = null; support = null;
    }
}
