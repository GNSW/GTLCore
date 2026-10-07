package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.List;

/** Bounded exact structural searches before generic multidimensional enumeration. */
final class CountStructureSearch implements AutoCloseable {

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private long allowance;
    private CountCardinalitySearch cardinality;
    private CountBinPackingSearch packing;
    private long work;
    private int stage;
    private boolean complete, infeasible;
    private boolean retaining, paused;
    private BigInteger[] counts;

    CountStructureSearch(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                         PlanningBudget budget, long maximumWork) {
        this.rows = rows;
        this.lower = lower;
        this.upper = upper;
        this.budget = budget;
        allowance = Math.min(maximumWork, budget.remainingWork() / 3);
        complete = allowance < 1024;
    }

    boolean step() {
        if (complete || paused) return true;
        long before = budget.threadWork();
        try {
            budget.check();
            if (work >= allowance) {
                if (retaining) return paused = true;
                return complete = true;
            }
            if (stage == 0) {
                if (cardinality == null) {
                    cardinality = new CountCardinalitySearch(rows, lower, upper, budget, allowance - work);
                    if (retaining) cardinality.retained();
                }
                if (!cardinality.step()) return false;
                if (cardinality.paused()) return paused = true;
                counts = cardinality.counts();
                infeasible = cardinality.infeasible();
                cardinality.close();
                cardinality = null;
                stage++;
            } else {
                if (packing == null) {
                    packing = new CountBinPackingSearch(rows, lower, upper, budget, allowance - work);
                    if (retaining) packing.retained();
                }
                if (!packing.step()) return false;
                if (packing.paused()) return paused = true;
                counts = packing.counts();
                infeasible = packing.infeasible();
                packing.close();
                packing = null;
                stage++;
            }
            return complete = counts != null || infeasible || stage == 2;
        } finally {
            work += budget.threadWork() - before;
        }
    }

    BigInteger[] counts() {
        return counts;
    }

    boolean infeasible() {
        return infeasible;
    }

    CountStructureSearch retained() {
        retaining = true;
        return this;
    }

    boolean paused() {
        return paused;
    }

    void resume(long quantum) {
        if (!paused || quantum <= 0) throw new IllegalStateException("Structural search is not paused");
        if (budget.remainingWork() == 0) budget.check();
        long next = Math.min(quantum, budget.remainingWork());
        allowance = work + next;
        if (cardinality != null) cardinality.resume(next);
        if (packing != null) packing.resume(next);
        paused = false;
    }

    @Override
    public void close() {
        if (cardinality != null) cardinality.close();
        if (packing != null) packing.close();
        cardinality = null;
        packing = null;
    }
}
