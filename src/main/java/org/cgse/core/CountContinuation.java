// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.List;

/** An owned specialist frontier. Pausing does not finish the finite search or prove anything. */
interface CountContinuation extends AutoCloseable {
    boolean step();
    boolean paused();
    void resume(long quantum);
    BigInteger[] counts();
    boolean matches(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper);
    long work();
    default long progress() { return 0; }
    @Override void close();

    /** Never replenish the request budget, and never overflow a cumulative local deadline. */
    static long deadline(long work, long quantum, PlanningBudget budget) {
        if (quantum <= 0) throw new IllegalArgumentException("Nonpositive continuation quantum");
        budget.checkpoint();
        if (budget.remainingWork() == 0) budget.check();
        long next = Math.min(quantum, budget.remainingWork());
        return next > Long.MAX_VALUE - work ? Long.MAX_VALUE : work + next;
    }
}
