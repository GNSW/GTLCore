// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** A small two-sided integer neighborhood of a rejected numerical proposal.
 * Box bounds restrict this positive candidate only; they are never UNSAT evidence. */
final class CountDemandResidual implements AutoCloseable {
    private final PlanningBudget budget;
    private final List<ExactLinearProgram.Constraint> original;
    private final long allowance;
    private CountDemandAllocation allocation;
    private BigInteger[] candidate, result;
    private long work, memory, atomicBound;
    private int validation;
    private boolean done;

    CountDemandResidual(List<ExactLinearProgram.Constraint> rows, BigInteger[] center,
                        PlanningBudget budget, long remaining) {
        this.budget = budget; original = rows;
        allowance = Math.min(131072, Math.max(0, remaining / 4));
        if (center == null || center.length == 0 || center.length > 128 || rows.size() > 1024 || allowance < 8192) {
            done = true; return;
        }
        long start = budget.threadWork();
        try {
            long terms = 0;
            for (var row : rows) { budget.check(); terms += row.terms().size(); }
            if (terms > 8192) { done = true; return; }
            // Allocation pauses between complete propagation/backtrack steps.
            // Keep room for the largest admitted sparse step and validation,
            // without borrowing the projection's remaining account.
            atomicBound = 64L * (terms + rows.size() + 5L * center.length) + 4096;
            if (atomicBound * 2 > allowance) { done = true; return; }
            long bytes = 4096L + 768L * center.length + 64L * (terms + rows.size());
            if (!budget.tryReserve(bytes)) { done = true; return; }
            memory = bytes;
            var box = new ArrayList<>(rows);
            for (int v = 0; v < center.length; v++) {
                budget.check();
                if (center[v] == null || center[v].signum() < 0 || center[v].bitLength() > 1024) { done = true; return; }
                box.add(new ExactLinearProgram.Constraint(Map.of(v, BigInteger.ONE), center[v].add(BigInteger.TWO)));
                box.add(new ExactLinearProgram.Constraint(Map.of(v, BigInteger.ONE.negate()),
                        center[v].subtract(BigInteger.TWO).max(BigInteger.ZERO).negate()));
            }
            var producers = new ArrayList<int[]>();
            for (var row : box) {
                var ids = new ArrayList<Integer>();
                for (var term : row.terms().entrySet()) { budget.check(); if (term.getValue().signum() < 0) ids.add(term.getKey()); }
                producers.add(ids.stream().mapToInt(Integer::intValue).toArray());
            }
            allocation = new CountDemandAllocation(center.length, box, producers, budget);
            budget.note("count_demand_residual", "bounded_neighborhood; variables=" + center.length + "; radius=2");
        } catch (RuntimeException | Error error) { close(); throw error; }
        finally { work += budget.threadWork() - start; }
    }

    boolean step() {
        if (done) return true;
        long start = budget.threadWork();
        try {
            budget.checkpoint();
            if (work + atomicBound >= allowance) return done = true;
            if (candidate == null) {
                if (!allocation.step()) return false;
                candidate = allocation.result();
                if (candidate == null) return done = true;
                return false;
            }
            if (validation < original.size()) {
                var row = original.get(validation++); BigInteger sum = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) {
                    budget.check(); sum = sum.add(term.getValue().multiply(candidate[term.getKey()]));
                }
                if (sum.compareTo(row.upper()) > 0) return done = true;
                return false;
            }
            result = candidate; done = true;
            budget.note("count_demand_residual", "verified; variables=" + result.length + "; work=" + work);
            return true;
        } finally { work += budget.threadWork() - start; }
    }

    BigInteger[] result() { return result; }

    @Override public void close() {
        if (allocation != null) allocation.close(); allocation = null;
        budget.release(memory); memory = 0; candidate = null; done = true;
    }
}
