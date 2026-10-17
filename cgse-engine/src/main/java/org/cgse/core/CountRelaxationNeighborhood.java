// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Retained LP proposal followed by exact restricted integer repair. Never proves infeasibility. */
final class CountRelaxationNeighborhood implements CountContinuation {
    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private final CountLpHint.Session relaxation = new CountLpHint.Session();
    private final long maximumWork;
    private BigInteger[] seed, counts;
    private double[] point;
    private int[] free;
    private CountQuickSolve repair;
    private long memory, repairMemory, work, until, lpWork, trialStarted, visits;
    private int attempt;
    private boolean complete, paused;

    static CountRelaxationNeighborhood create(CountModelViews.View view, CountModelViews.Domains domains,
                                              PlanningBudget budget, long quantum) {
        int n = domains.lower().length;
        if (n < 64 || n > 512 || view.rows().size() > 1024 || view.shape().terms() > 32768 ||
                budget.remainingWork() < 262144) return null;
        boolean wide = false;
        for (int i = 0; i < n; i++) {
            budget.check();
            var low = domains.lower()[i]; var high = domains.upper()[i];
            if (low == null || high == null || low.signum() < 0 || high.bitLength() > 31 || low.compareTo(high) > 0) return null;
            wide |= high.subtract(low).compareTo(BigInteger.valueOf(64)) > 0;
        }
        // Large multiplicities can leave a tiny arithmetic repair around the
        // LP vertex. Narrow/Boolean models retain their existing core searches.
        if (!wide) return null;
        long bytes = 4096L + 256L * n + 16L * view.rows().size();
        if (!budget.tryReserve(bytes)) return null;
        return new CountRelaxationNeighborhood(view.rows(), domains.lower(), domains.upper(), budget, quantum, bytes);
    }

    private CountRelaxationNeighborhood(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                                        BigInteger[] upper, PlanningBudget budget, long quantum, long bytes) {
        this.rows = List.copyOf(rows); this.lower = lower.clone(); this.upper = upper.clone();
        this.budget = budget; memory = bytes;
        maximumWork = Math.min(4_194_304, budget.remainingWork() / 8);
        until = quantum;
    }

    @Override public boolean step() {
        if (complete || paused) return true;
        budget.checkpoint();
        if (work >= maximumWork) return finish("local_limit");
        if (work >= until) { paused = true; return true; }
        long before = budget.threadWork();
        try {
            if (point == null) {
                if (lpWork >= maximumWork / 2) return finish("relaxation_limit");
                var admission = new CountLpHint.Attempt();
                long grant = Math.min(Math.min(262144, maximumWork / 2 - lpWork), budget.remainingWork());
                // A retained hint declines smaller visits without charging any
                // work. Spending the last fragment by retrying cannot advance it.
                if (grant < 1024) return finish("relaxation_limit");
                try (var result = relaxation.pending() ? relaxation.resume(grant, admission) :
                        relaxation.start(rows, lower, upper, budget, grant, admission)) {
                    if (result == null) {
                        if (!relaxation.pending()) return finish("no_relaxation_point");
                        // Another worker can consume the shared allowance after
                        // the grant check. Do not spin on a retained refusal.
                        if (budget.threadWork() == before) return finish("relaxation_stalled");
                        return false;
                    }
                    point = result.point.clone();
                    seed = new BigInteger[lower.length];
                    for (int i = 0; i < seed.length; i++) {
                        budget.check();
                        if (!Double.isFinite(point[i]) || point[i] < lower[i].doubleValue() - 1e-5 ||
                                point[i] > upper[i].doubleValue() + 1e-5) return finish("invalid_point");
                        seed[i] = BigInteger.valueOf(Math.round(point[i])).max(lower[i]).min(upper[i]);
                    }
                    visits++;
                    if (valid(seed)) { counts = seed; return finish("rounded_witness"); }
                    return false;
                } finally { lpWork += budget.threadWork() - before; }
            }
            if (repair == null && !beginRepair()) return finish("unsupported_core");
            boolean done = repair.step();
            if (done) {
                var local = repair.counts();
                if (local != null) {
                    var restored = seed.clone();
                    for (int i = 0; i < free.length; i++) { budget.check(); restored[free[i]] = local[i]; }
                    if (valid(restored)) { counts = restored; return finish("verified_candidate"); }
                }
            }
            long allowance = Math.min(524288, maximumWork / 4);
            if (done || work + budget.threadWork() - before - trialStarted >= allowance) {
                clearRepair();
                if (++attempt >= 4) return finish("cores_unresolved");
            }
            return false;
        } finally { work += budget.threadWork() - before; }
    }

    private boolean beginRepair() {
        BitSet chosen = new BitSet(lower.length);
        for (int i = 0; i < point.length; i++) {
            budget.check();
            if (!lower[i].equals(upper[i]) && Math.abs(point[i] - Math.rint(point[i])) > 1e-6) chosen.set(i);
        }
        // Keep small allocation constraints intact when widening the rounding
        // neighborhood. This uses the compiler's original capacity view.
        if (attempt > 0) for (var row : rows) {
            if (row.terms().size() < 2 || row.terms().size() > 8) continue;
            boolean touched = false;
            for (int id : row.terms().keySet()) { budget.check(); touched |= chosen.get(id); }
            if (touched) for (int id : row.terms().keySet()) if (!lower[id].equals(upper[id])) chosen.set(id);
        }
        int extra = attempt == 0 ? 0 : 4 << (attempt - 1);
        var ranked = new ArrayList<Integer>();
        for (int i = 0; i < point.length; i++) if (!chosen.get(i) && !lower[i].equals(upper[i])) ranked.add(i);
        // Numerical distance ranks optional fixings only, never a domain fact.
        ranked.sort(Comparator.<Integer>comparingDouble(i -> Math.min(Math.abs(point[i] - lower[i].doubleValue()),
                Math.abs(upper[i].doubleValue() - point[i]))).reversed());
        for (int j = 0; j < Math.min(extra, ranked.size()); j++) chosen.set(ranked.get(j));
        if (chosen.isEmpty() || chosen.cardinality() > 64) return false;
        free = chosen.stream().toArray();
        long bytes = 4096L + 256L * lower.length + 384L * rows.stream().mapToLong(r -> r.terms().size()).sum();
        if (!budget.tryReserve(bytes)) return false;
        repairMemory = bytes;
        int[] map = new int[lower.length]; Arrays.fill(map, -1);
        var lo = new BigInteger[free.length]; var hi = new BigInteger[free.length];
        for (int i = 0; i < free.length; i++) {
            budget.check(); int id = free[i]; map[id] = i;
            lo[i] = attempt == 0 ? BigInteger.valueOf((long) Math.floor(point[id])).max(lower[id]) : lower[id];
            hi[i] = attempt == 0 ? BigInteger.valueOf((long) Math.ceil(point[id])).min(upper[id]) : upper[id];
        }
        var projected = new ArrayList<ExactLinearProgram.Constraint>();
        for (var row : rows) {
            var terms = new TreeMap<Integer, BigInteger>(); var rhs = row.upper();
            for (var term : row.terms().entrySet()) {
                budget.check(); int id = term.getKey(); var a = term.getValue();
                if (map[id] >= 0) terms.put(map[id], a);
                else {
                    budget.operation(PlanningBudget.Operation.INTEGER, Math.max(rhs.bitLength(), a.bitLength() + seed[id].bitLength()));
                    rhs = rhs.subtract(a.multiply(seed[id]));
                }
            }
            projected.add(new ExactLinearProgram.Constraint(terms, rhs));
        }
        trialStarted = work;
        repair = new CountQuickSolve(projected, lo, hi, budget, false, true, 16384);
        visits++;
        return true;
    }

    private boolean valid(BigInteger[] values) {
        for (int i = 0; i < values.length; i++) {
            budget.check();
            if (values[i].compareTo(lower[i]) < 0 || values[i].compareTo(upper[i]) > 0) return false;
        }
        for (var row : rows) {
            var total = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check();
                budget.operation(PlanningBudget.Operation.INTEGER, Math.max(total.bitLength(), term.getValue().bitLength() + values[term.getKey()].bitLength()));
                total = total.add(term.getValue().multiply(values[term.getKey()]));
            }
            if (total.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private boolean finish(String reason) {
        complete = true; paused = false;
        budget.note("count_relaxation_neighborhood", reason + "; variables=" + lower.length + "; attempts=" +
                (attempt + 1) + "; lp_work=" + lpWork + "; work=" + work);
        return true;
    }
    private void clearRepair() {
        if (repair != null) { repair.close(); repair = null; }
        budget.release(repairMemory); repairMemory = 0;
    }
    @Override public boolean paused() { return paused; }
    @Override public void resume(long quantum) { until = CountContinuation.deadline(work, quantum, budget); paused = false; }
    @Override public BigInteger[] counts() { return counts; }
    @Override public long work() { return work; }
    @Override public long progress() { return visits; }
    @Override public boolean matches(List<ExactLinearProgram.Constraint> other, BigInteger[] low, BigInteger[] high) {
        return rows.equals(other) && Arrays.equals(lower, low) && Arrays.equals(upper, high);
    }
    @Override public void close() {
        clearRepair(); relaxation.close(); budget.release(memory); memory = 0;
    }
}
