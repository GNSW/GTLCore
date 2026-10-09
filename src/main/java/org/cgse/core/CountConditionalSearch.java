// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.List;

/** A scoped local portfolio: retain the proving frontier while repairing its integer kernel. */
final class CountConditionalSearch implements AutoCloseable {
    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private CountLcg lcg;
    private CountAffineLattice lattice;
    private BigInteger[] counts;
    private long work, allowance, memory;
    private int phase;
    private boolean complete, paused, infeasible;

    CountConditionalSearch(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                           PlanningBudget budget, long quantum) {
        this.rows = rows;
        this.lower = lower;
        this.upper = upper;
        this.budget = budget;
        allowance = quantum;
        long before = budget.threadSearchWork();
        try {
            if (!budget.tryReserve(256)) { complete = true; return; }
            memory = 256;
            boolean wide = false, finite = lower.length <= 48 && rows.size() <= 512;
            for (int i = 0; i < lower.length && finite; i++) {
                budget.check();
                finite &= upper[i] != null;
                wide |= upper[i] != null && upper[i].subtract(lower[i]).compareTo(BigInteger.ONE) > 0;
            }
            phase = finite && wide && rows.size() >= 4 ? 0 : 2;
            long first = phase == 0 ? Math.max(1024, Math.min(4096, quantum / 8)) : quantum;
            lcg = new CountLcg(rows, lower, upper, budget, first);
        } catch (RuntimeException | Error failure) { close(); throw failure; }
        finally { work += budget.threadSearchWork() - before; }
    }

    boolean step() {
        if (complete) return true;
        budget.checkpoint();
        if (work >= allowance) { paused = true; return complete = true; }
        long before = budget.threadSearchWork();
        try {
            if (phase == 1) {
                if (!lattice.step()) return false;
                if (lattice.paused()) {
                    // Give the proving frontier a turn before continuing the
                    // same numeric factorization. Both consume this scope's
                    // unchanged parent allowance.
                    phase = 2;
                    if (lcg.paused()) lcg.resume(Math.max(1, Math.min(32768, allowance - work)));
                    return false;
                }
                counts = lattice.counts();
                lattice.close();
                lattice = null;
                phase = 2;
                if (counts != null) return complete = true;
                return false;
            }
            if (lcg.paused()) {
                long remaining = allowance - work;
                if (phase == 0) {
                    // Original local domains, never the current speculative
                    // LCG trail. Only a revalidated positive witness crosses.
                    // Admission is structural, not a consequence of the first
                    // interface quantum. The wrapper yields between steps and
                    // retains this factorization across later local turns.
                    lattice = new CountAffineLattice(rows, lower, upper, budget, 32768, true).retained();
                    phase = 1;
                    return false;
                }
                if (lattice != null && lattice.paused()) {
                    lattice.resume(Math.max(1, Math.min(32768, remaining)));
                    phase = 1;
                    return false;
                }
                phase = 2;
                lcg.resume(remaining);
            }
            if (!lcg.step()) return false;
            counts = lcg.counts();
            infeasible = lcg.infeasible();
            if (counts != null || infeasible || !lcg.paused()) return complete = true;
            if (phase == 0) return false;
            paused = true;
            return complete = true;
        } finally { work += budget.threadSearchWork() - before; }
    }

    boolean paused() { return paused; }
    boolean infeasible() { return infeasible; }
    BigInteger[] counts() { return counts; }

    void resume(long quantum) {
        if (!paused || quantum <= 0) throw new IllegalStateException("Conditional search is not paused");
        if (budget.remainingWork() == 0) budget.check();
        allowance = work + Math.min(quantum, budget.remainingWork());
        complete = paused = false;
    }

    @Override public void close() {
        if (lcg != null) lcg.close();
        if (lattice != null) lattice.close();
        lcg = null;
        lattice = null;
        budget.release(memory);
        memory = 0;
    }
}
