// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Keep both relaxation faces; neither a valid cut nor a heuristic replaces the other view. */
final class CountRepairPortfolio implements AutoCloseable {

    private static final class View {

        final ExactRational[] point;
        CountLatticeRepair repair;
        int attempt;
        long slice;

        View(ExactRational[] point) {
            this.point = point;
        }
    }

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private final List<View> views = new ArrayList<>();
    private CountDiving diving;
    private CountLatticeStructure lattice;
    private boolean latticePrepared;
    private boolean diveDone;
    private long diveSlice;
    private int cursor;
    private BigInteger[] counts;
    private long memory;
    private boolean complete;

    CountRepairPortfolio(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                         ExactRational[] primary, ExactRational[] alternative, PlanningBudget budget) {
        this.rows = rows;
        this.lower = lower;
        this.upper = upper;
        this.budget = budget;
        views.add(new View(primary));
        if (alternative != null && !Arrays.equals(primary, alternative)) {
            long bytes = 256 + 64L * alternative.length;
            if (budget.tryReserve(bytes)) {
                memory = bytes;
                views.add(new View(alternative));
            }
        }
        cursor = views.size();
    }

    boolean step() {
        if (complete) return true;
        if (cursor == views.size()) return stepDive();
        View view = views.get(cursor);
        if (view.attempt == 4) {
            budget.check();
            if (diveDone && views.stream().allMatch(v -> v.attempt == 4)) {
                complete = true;
                return true;
            }
            cursor = (cursor + 1) % (views.size() + 1);
            return false;
        }
        long before = budget.threadWork();
        try {
            if (!latticePrepared) {
                latticePrepared = true;
                if (lower.length <= 64 && budget.remainingWork() >= 8192) {
                    boolean bounded = true;
                    for (var limit : upper) {
                        budget.check();
                        if (limit == null) bounded = false;
                    }
                    if (bounded) lattice = CountLatticeStructure.create(rows, budget);
                }
                if (lattice == null || !lattice.usable()) {
                    for (View candidate : views) candidate.attempt = 4;
                    return false;
                }
            }
            if (view.repair == null) view.repair = new CountLatticeRepair(rows, lower, upper, view.point, view.attempt, budget, lattice);
            if (view.repair.step()) {
                counts = view.repair.counts();
                view.repair.close();
                view.repair = null;
                view.attempt++;
                if (counts != null) {
                    complete = true;
                    budget.note("count_repair_view", "witness_view=" + cursor + "; views=" + views.size() + "; attempt=" + view.attempt);
                    return true;
                }
            }
            return false;
        } finally {
            view.slice += budget.threadWork() - before;
            if (view.slice >= 4096 || view.attempt == 4) {
                view.slice = 0;
                cursor = (cursor + 1) % (views.size() + 1);
            }
        }
    }

    private boolean stepDive() {
        if (diveDone) {
            budget.check();
            complete = views.stream().allMatch(view -> view.attempt == 4);
            cursor = 0;
            return complete;
        }
        long before = budget.threadWork();
        try {
            // This member retains its own bounded search across handoffs. A
            // new quantum neither recreates it nor replenishes its local quota.
            if (diving == null) diving = new CountDiving(rows, lower, upper, views.get(0).point, budget);
            if (!diving.step()) return false;
            counts = diving.counts();
            diving.close();
            diving = null;
            diveDone = true;
            if (counts != null) {
                complete = true;
                budget.note("count_repair_view", "witness_view=diving; lattice_views=" + views.size());
            }
            return complete;
        } finally {
            diveSlice += budget.threadWork() - before;
            if (diveSlice >= 4096 || diveDone) {
                diveSlice = 0;
                cursor = 0;
            }
        }
    }

    BigInteger[] counts() {
        return counts;
    }

    boolean competingViews() {
        return views.size() > 1 && views.stream().anyMatch(v -> v.attempt > 0 && v.attempt < 4);
    }

    @Override
    public void close() {
        if (diving != null) diving.close();
        diving = null;
        for (View view : views) {
            if (view.repair != null) view.repair.close();
            view.repair = null;
        }
        if (lattice != null) lattice.close();
        lattice = null;
        budget.release(memory);
        memory = 0;
    }
}
