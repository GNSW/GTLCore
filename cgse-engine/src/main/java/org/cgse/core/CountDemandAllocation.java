// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Request-local capacity propagation and reversible source allocation.
 * Only proposes counts: neither failed choices nor a cutoff prove infeasibility. */
final class CountDemandAllocation implements AutoCloseable {
    private record Edge(int row, BigInteger coefficient) {}
    private record Change(int variable, boolean minimum, BigInteger old, int previousDepth) {}
    private record Choice(int mark, int variable, BigInteger value) {}

    private static final long CHANGE_BYTES = 256;
    private final List<ExactLinearProgram.Constraint> rows;
    private final List<int[]> producers;
    private final PlanningBudget budget;
    private final List<List<Edge>> columns = new ArrayList<>();
    private final ArrayDeque<Integer> queue = new ArrayDeque<>();
    private final BitSet queued = new BitSet();
    private final ArrayList<Change> trail = new ArrayList<>();
    private final ArrayDeque<Choice> choices = new ArrayDeque<>();
    private BigInteger[] low, high, deficits, result;
    private int[] lowDepth, highDepth;
    private long memory, trailMemory;
    private int reversals;
    private boolean done;

    CountDemandAllocation(int variables, List<ExactLinearProgram.Constraint> rows, List<int[]> producers,
                          PlanningBudget budget) {
        this.rows = rows; this.producers = producers; this.budget = budget;
        long terms = 0;
        for (var row : rows) { budget.check(); terms += row.terms().size(); }
        long bytes = 4096L + 648L * variables + 512L * rows.size() + 64L * terms;
        if (!budget.tryReserve(bytes)) { done = true; return; }
        memory = bytes;
        try {
            low = new BigInteger[variables]; high = new BigInteger[variables];
            lowDepth = new int[variables]; highDepth = new int[variables];
            Arrays.fill(low, BigInteger.ZERO);
            deficits = new BigInteger[rows.size()];
            for (int i = 0; i < variables; i++) columns.add(new ArrayList<>());
            for (int r = 0; r < rows.size(); r++) {
                budget.check(); deficits[r] = rows.get(r).upper().negate();
                for (var term : rows.get(r).terms().entrySet()) {
                    budget.check(); columns.get(term.getKey()).add(new Edge(r, term.getValue()));
                }
            }
            enqueueAll();
        } catch (RuntimeException | Error error) { close(); throw error; }
    }

    boolean step() {
        if (done) return true;
        // One sparse row per slice also bounds cancellation latency on long
        // upstream chains. The owner accounts all work against its scout cap.
        budget.check();
        if (!queue.isEmpty()) {
            int r = queue.removeFirst(); queued.clear(r);
            if (!propagate(r) && !done) backtrack();
            return done;
        }
        int row = -1, width = Integer.MAX_VALUE;
        for (int r = 0; r < deficits.length; r++) {
            budget.check();
            if (deficits[r].signum() <= 0) continue;
            int available = 0;
            for (int variable : producers.get(r)) {
                budget.check();
                if (high[variable] == null || high[variable].compareTo(low[variable]) > 0) available++;
            }
            if (available == 0) { backtrack(); return done; }
            if (available < width) { row = r; width = available; }
        }
        if (row < 0) { result = low.clone(); return done = true; }
        return branch(row);
    }

    private boolean propagate(int id) {
        var row = rows.get(id);
        BigInteger minimum = BigInteger.ZERO;
        int infinite = 0;
        for (var term : row.terms().entrySet()) {
            budget.check();
            BigInteger bound = term.getValue().signum() > 0 ? low[term.getKey()] : high[term.getKey()];
            if (bound == null) infinite++;
            else minimum = minimum.add(term.getValue().multiply(bound));
        }
        if (infinite == 0 && minimum.compareTo(row.upper()) > 0) return false;
        BigInteger slack = row.upper().subtract(minimum);
        // Snapshot row minima before tightening. New bounds wake their sparse
        // incidences; using an old minimum below is weaker, never stronger.
        for (var term : row.terms().entrySet()) {
            budget.check();
            int variable = term.getKey(); BigInteger coefficient = term.getValue();
            BigInteger old = coefficient.signum() > 0 ? low[variable] : high[variable];
            if (infinite - (old == null ? 1 : 0) != 0) continue;
            BigInteger residual = slack;
            if (old != null) residual = residual.add(coefficient.multiply(old));
            if (coefficient.signum() > 0) {
                if (residual.signum() < 0) return false;
                // Keep the same bound and work checks when division could only
                // rediscover a capacity that is already at least this tight.
                if (high[variable] != null && residual.compareTo(coefficient.multiply(high[variable])) >= 0) continue;
                if (!tighten(variable, false, residual.divide(coefficient))) return false;
            } else if (residual.signum() < 0) {
                if (residual.compareTo(coefficient.multiply(low[variable])) >= 0) continue;
                if (!tighten(variable, true, CheckedAmounts.ceilDiv(residual.negate(), coefficient.negate()))) return false;
            }
        }
        return true;
    }

    private boolean branch(int row) {
        int best = -1, coverage = -1;
        BigInteger runs = null;
        for (int variable : producers.get(row)) {
            budget.check();
            BigInteger available = high[variable] == null ? null : high[variable].subtract(low[variable]);
            if (available != null && available.signum() <= 0) continue;
            BigInteger gain = rows.get(row).terms().get(variable).negate();
            BigInteger next = CheckedAmounts.ceilDiv(deficits[row], gain);
            if (available != null) next = next.min(available);
            int covers = 0;
            for (var edge : columns.get(variable)) {
                budget.check();
                if (edge.coefficient.signum() < 0 && deficits[edge.row].signum() > 0 &&
                        edge.coefficient.negate().multiply(next).compareTo(deficits[edge.row]) >= 0) covers++;
            }
            // Joint outputs get credit against all live debts, including ones
            // opened by a different path. Stable source order breaks ties.
            if (covers > coverage) { best = variable; runs = next; coverage = covers; }
        }
        if (best < 0) { backtrack(); return done; }
        if (choices.size() == 64) return done = true;
        BigInteger next = low[best].add(runs);
        choices.push(new Choice(trail.size(), best, next));
        if (!tighten(best, true, next) && !done) backtrack();
        return done;
    }

    private boolean tighten(int variable, boolean minimum, BigInteger value) {
        BigInteger old = minimum ? low[variable] : high[variable];
        if (old != null && (minimum ? value.compareTo(old) <= 0 : value.compareTo(old) >= 0)) return true;
        if (value.bitLength() > 1024) { done = true; return false; }
        if (!choices.isEmpty()) {
            int[] depth = minimum ? lowDepth : highDepth;
            // Undo needs the value at this decision's entry, not every
            // intermediate bound reached while propagating around feedback.
            // Restoring the previous depth also permits a sibling decision
            // at the same depth to record its own first change.
            if (depth[variable] != choices.size()) {
                if (trail.size() >= 65536 || !budget.tryReserve(CHANGE_BYTES)) { done = true; return false; }
                trailMemory += CHANGE_BYTES;
                trail.add(new Change(variable, minimum, old, depth[variable]));
                depth[variable] = choices.size();
            }
        }
        if (minimum) setLow(variable, value);
        else high[variable] = value;
        for (var edge : columns.get(variable)) {
            budget.check();
            // Only lower bounds of consumers and upper bounds of producers
            // participate in a row minimum.
            if (minimum == (edge.coefficient.signum() > 0)) enqueue(edge.row);
        }
        return !done && (high[variable] == null || low[variable].compareTo(high[variable]) <= 0);
    }

    private void setLow(int variable, BigInteger value) {
        BigInteger difference = value.subtract(low[variable]);
        low[variable] = value;
        for (var edge : columns.get(variable)) {
            budget.charge(1 + (difference.bitLength() + edge.coefficient.bitLength()) / 64);
            deficits[edge.row] = deficits[edge.row].add(edge.coefficient.multiply(difference));
            if (deficits[edge.row].bitLength() > 2048) done = true;
        }
    }

    private void backtrack() {
        if (choices.isEmpty() || ++reversals > 256) { done = true; return; }
        var choice = choices.pop();
        for (int i = trail.size() - 1; i >= choice.mark; i--) {
            budget.check(); var change = trail.remove(i);
            (change.minimum ? lowDepth : highDepth)[change.variable] = change.previousDepth;
            if (change.minimum) setLow(change.variable, change.old);
            else high[change.variable] = change.old;
            trailMemory -= CHANGE_BYTES; budget.release(CHANGE_BYTES);
        }
        queue.clear(); queued.clear();
        // Reverse the allocation, not the whole recipe. This preserves mixed
        // solutions using fewer runs of this source plus another finite feed.
        if (!tighten(choice.variable, false, choice.value.subtract(BigInteger.ONE))) { done = true; return; }
        enqueueAll();
    }

    private void enqueueAll() {
        // Install finite raw capacities before walking around feedback debts.
        // The second pass retains the original order of all other rows.
        for (int r = 0; r < rows.size(); r++) { budget.check(); if (producers.get(r).length == 0) enqueue(r); }
        for (int r = 0; r < rows.size(); r++) { budget.check(); enqueue(r); }
    }

    /** A rejected physical word may try another allocation, never become UNSAT. */
    boolean reject() {
        if (result == null) return false;
        result = null; done = false; backtrack();
        return !done;
    }

    private void enqueue(int row) {
        if (!queued.get(row)) { queued.set(row); queue.addLast(row); }
    }

    BigInteger[] result() { return result; }

    @Override public void close() {
        budget.release(memory + trailMemory); memory = 0; trailMemory = 0;
        trail.clear(); choices.clear();
    }
}
