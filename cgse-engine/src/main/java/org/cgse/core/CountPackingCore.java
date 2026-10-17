// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded-variable LP prices followed by retained, exact Boolean core enumeration.
 * Fixings outside each core are proposals only. Closing a core proves nothing globally. */
final class CountPackingCore implements CountContinuation {
    enum Phase { ENUMERATION, CONFLICT_REPAIR, DISTANCE_REPAIR }
    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private final CountPackingStructure structure;
    private final boolean ownsStructure;
    private final long maximumWork, normalWork;
    private Phase phase = Phase.ENUMERATION;
    private final long learningLimit;
    private final int[] columns;
    private final long[][] matrix;
    private final long[] rhs, profit;
    private final long target;
    private final Relaxation lp;
    private long memory, work, until, nodes, relaxationSteps;
    private int[] ranked, free;
    private boolean[] seed;
    private int coreSize, fractional, compiledSize;
    private long[][] coreCoefficients;
    private long[] coreBounds;
    private long corePreferred;
    private boolean scouting, flexibleScout, flexiblePending;
    private long scoutLimit, scoutAt, flexibleLimit, flexibleAt, flexibleAllowance;
    private long roundingStart, failedRoundingWork, stepStart = -1;
    private boolean scoutAttempted, wideScoutAttempted, wideScout;
    private long wideScoutAt, wideScoutStart, wideScoutWork;
    private Frontier scoutBase;
    private record Frontier(CountPackingNeighborhood search, int[] free, int size, int compiledSize,
                            long[][] coefficients, long[] bounds, long preferred) {}
    private CountPackingNeighborhood search;
    private long forcedValues;
    private boolean complete, paused;
    private BigInteger[] counts;
    private CountLpLearning.Cut explanation;
    private long[] aggregate;
    private long aggregateBound;

    static CountPackingCore create(CountModelViews.View view, CountModelViews.Domains domains,
                                   PlanningBudget budget, long quantum) {
        var structure = CountPackingStructure.create(view, domains, budget);
        if (structure == null) return null;
        try {
            var result = create(structure, budget, quantum, true);
            if (result != null) structure = null;
            return result;
        } finally { if (structure != null) structure.close(); }
    }

    static CountPackingCore create(CountPackingStructure structure, PlanningBudget budget, long quantum) {
        return create(structure, budget, quantum, false);
    }

    private static CountPackingCore create(CountPackingStructure structure, PlanningBudget budget, long quantum, boolean ownsStructure) {
        if (budget.remainingWork() < 524288) return null;
        int n = structure.columns.length, m = structure.matrix.length;
        long bytes = 8192L + 128L * structure.lower.length + 64L * (m + 3) * (n + m + 2);
        if (!budget.tryReserve(bytes)) return null;
        long started = budget.threadWork();
        try {
            var result = new CountPackingCore(structure, budget, quantum, bytes, ownsStructure);
            result.work = budget.threadWork() - started;
            bytes = 0;
            return result;
        } finally { budget.release(bytes); }
    }

    private CountPackingCore(CountPackingStructure structure, PlanningBudget budget, long quantum, long memory, boolean ownsStructure) {
        this.structure = structure; this.ownsStructure = ownsStructure;
        rows = structure.rows; lower = structure.lower; upper = structure.upper;
        columns = structure.columns; matrix = structure.matrix; rhs = structure.rhs;
        profit = structure.profit; target = structure.target;
        this.budget = budget; this.memory = memory;
        // Capture every ceiling once. Phase changes and worker handoffs consume
        // the same request budget and cannot replenish these local allowances.
        normalWork = Math.min(12_582_912, budget.remainingWork() / 3);
        learningLimit = normalWork + Math.min(2_097_152, budget.remainingWork() / 16);
        maximumWork = normalWork + Math.min(8_388_608, budget.remainingWork() / 4);
        until = quantum;
        lp = new Relaxation(matrix, rhs, profit, budget);
    }

    @Override public boolean step() {
        if (complete || paused) return true;
        budget.checkpoint();
        if (work >= maximumWork) return finish("local_limit");
        if (work >= until) { paused = true; return true; }
        long before = budget.threadWork();
        stepStart = before;
        try {
            if (scouting && work >= scoutLimit) endScout();
            // Other retained arms spend the same request account. Once this
            // enumeration has had half its fixed opportunity, do not wait for
            // its entire local ceiling if that would leave less than the
            // originally sized repair opportunity in the request. This moves
            // a phase boundary only; no local or global allowance is renewed.
            if (phase == Phase.ENUMERATION && (work >= normalWork ||
                    work >= normalWork / 2 && budget.remainingWork() <= maximumWork - normalWork)) {
                flexiblePending = false;
                // The request can shrink while an optional scout is live.
                // Restore the original face before constructing its PB repair.
                if (scouting) endScout();
                if (ranked == null || search == null) return finish("local_limit");
                phase = Phase.CONFLICT_REPAIR;
                beginCore();
                // Give PB its initial sample before the larger exchange.
                wideScoutAt = work + Math.min(262144, (learningLimit - work) / 4);
            }
            // The scout uses the same total allowance, but its work must not
            // expire the saved PB frontier's own learning window.
            if (phase == Phase.CONFLICT_REPAIR && !scouting && work - wideScoutWork >= learningLimit) {
                phase = Phase.DISTANCE_REPAIR;
                beginCore();
            }
            if (!lp.done) {
                if (++relaxationSteps > 8192 || !lp.step()) return finish("numeric_declined");
                return false;
            }
            if (ranked == null && !prepare()) return finish("point_declined");
            if (search == null) beginCore();
            if (flexiblePending && phase == Phase.ENUMERATION && work >= flexibleAt) beginDeferredScout();
            if (!scoutAttempted && phase == Phase.ENUMERATION && work >= scoutAt && normalWork - work >= 262144)
                beginScout();
            if (!wideScoutAttempted && phase == Phase.CONFLICT_REPAIR && work >= wideScoutAt && maximumWork - work >= 524288)
                beginWideScout();

            // Whole-core propagation and the DFS stack survive each handoff.
            // Closing this core never exports its outside fixings as facts.
            nodes++;
            if (!search.step()) return false;
            if (search.feasible()) {
                long assignment = search.assignment();
                var candidate = lower.clone();
                for (int i = 0; i < columns.length; i++) candidate[columns[i]] = seed[i] ? BigInteger.ONE : BigInteger.ZERO;
                for (int i = 0; i < free.length; i++)
                    candidate[columns[free[i]]] = (assignment & (1L << i)) != 0 ? BigInteger.ONE : BigInteger.ZERO;
                if (valid(candidate)) { counts = candidate; return finish("verified_candidate"); }
                return finish("candidate_rejected");
            }
            if (scouting) { if (!scheduleFlexibleScout()) endScout(); return false; }
            if (coreSize >= Math.min(64, columns.length)) return finish("cores_closed");
            coreSize = Math.min(Math.min(64, columns.length), coreSize + 8);
            beginCore();
            return false;
        } finally { work += budget.threadWork() - before; stepStart = -1; }
    }

    private boolean prepare() {
        int n = columns.length;
        seed = new boolean[n];
        var ordering = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            budget.check(); double x = lp.point[i];
            if (!Double.isFinite(x) || x < -1e-6 || x > 1 + 1e-6) return false;
            seed[i] = x >= 0.5;
            if (x > 1e-6 && x < 1 - 1e-6) fractional++;
            ordering.add(i);
        }
        // Fractional coordinates have zero reduced cost. Remaining coordinates
        // are released by price, not by their arbitrary original recipe index.
        ordering.sort((a, b) -> {
            budget.operation(PlanningBudget.Operation.SCAN, 0);
            double first = lp.point[a] > 1e-6 && lp.point[a] < 1 - 1e-6 ? -1 : Math.abs(lp.cost[a]);
            double second = lp.point[b] > 1e-6 && lp.point[b] < 1 - 1e-6 ? -1 : Math.abs(lp.cost[b]);
            int compared = Double.compare(first, second);
            return compared != 0 ? compared : Integer.compare(structure.ranks[a], structure.ranks[b]);
        });
        ranked = ordering.stream().mapToInt(Integer::intValue).toArray();
        coreSize = Math.min(n, Math.max(16, fractional + 8));
        double[] prices = new double[matrix.length];
        for (int r = 0; r < prices.length; r++) prices[r] = lp.price(r);
        explanation = structure.explain(prices);
        // Shift the certified row into this compact view once. Its full-row
        // certificate remains available to other engines in the same model.
        var bound = explanation.row().upper(); var vector = new BigInteger[n];
        for (var term : explanation.row().terms().entrySet()) {
            budget.check(); bound = bound.subtract(term.getValue().multiply(lower[term.getKey()]));
        }
        var magnitude = bound.abs();
        for (int i = 0; i < n; i++) {
            budget.check(); vector[i] = explanation.row().terms().getOrDefault(columns[i], BigInteger.ZERO);
            magnitude = magnitude.add(vector[i].abs());
        }
        if (magnitude.bitLength() < 60) {
            aggregate = Arrays.stream(vector).mapToLong(BigInteger::longValueExact).toArray();
            aggregateBound = bound.longValueExact();
        }
        // Preserve the same bounded first sample at smaller request budgets.
        // Dividing this sample by the total allowance interrupted easy cores
        // earlier precisely when less work was available to recover them.
        // The normal phase ceiling still ends the sample if it is smaller.
        scoutAt = work + Math.min(262144, normalWork);
        return coreSize <= 64;
    }

    private void beginScout() {
        scoutAttempted = scouting = true;
        scoutBase = new Frontier(search, free, coreSize, compiledSize, coreCoefficients, coreBounds, corePreferred);
        search = null;
        coreSize = Math.min(columns.length, Math.max(40, coreSize));
        compiledSize = 0;
        // This scout consumes the existing normal allowance. Neither a worker
        // handoff nor restoring the saved frontier renews any phase ceiling.
        // Admission already requires this minimum normal-phase remainder.
        // Leave room to query the exchange index after paying to build it.
        scoutLimit = Math.min(normalWork, work + Math.min(1_048_576, Math.max(262144, maximumWork / 16)));
        // Reserve one additional equal sample for LP rounding; unused first
        // sample work can carry forward inside this fixed shared ceiling.
        // Resuming either index cannot increase this work or the total cap.
        flexibleLimit = Math.min(normalWork, scoutLimit + (scoutLimit - work));
        beginCore();
    }

    private void beginWideScout() {
        wideScoutAttempted = scouting = wideScout = true;
        wideScoutStart = work;
        scoutBase = new Frontier(search, free, coreSize, compiledSize, coreCoefficients, coreBounds, corePreferred);
        search = null; coreSize = Math.min(columns.length, Math.max(40, coreSize)); compiledSize = 0;
        // Reuse the LP seed and ranking. This single late attempt consumes
        // the existing repair allowance; the original frontier stays retained.
        scoutLimit = work + Math.min(3_145_728, (maximumWork - work) / 2);
        beginCore();
    }

    private boolean beginFlexibleScout() {
        if (wideScout || flexibleScout || fractional == 0 || work >= scoutLimit) return false;
        // Fractional coordinates lead the ranking; alternating its positions
        // distributes them evenly. Refuse an oversized all-roundings domain
        // instead of silently omitting some of its assignments.
        int leftFree = fractional / 2, rightFree = fractional - leftFree;
        int cap = CountPackingExchange.combinations((coreSize + 1) / 2, 4, 0, budget);
        if (CountPackingExchange.combinations(coreSize / 2, 0, leftFree, budget) > cap ||
                CountPackingExchange.combinations(coreSize - coreSize / 2, 0, rightFree, budget) > cap) return false;
        roundingStart = measuredWork();
        flexibleScout = true; scoutLimit = flexibleLimit; compiledSize = 0; beginCore(); return true;
    }

    private boolean scheduleFlexibleScout() {
        if (wideScout || flexibleScout || fractional == 0 || work >= scoutLimit) return false;
        long mask = 0;
        for (int i = 0; i < free.length; i++) {
            budget.operation(PlanningBudget.Operation.SCAN, 0);
            if (lp.point[free[i]] > 1e-6 && lp.point[free[i]] < 1 - 1e-6) mask |= 1L << i;
        }
        var analysis = CountPackingSchedule.analyze(coreCoefficients, coreBounds, matrix.length,
                corePreferred, mask, Math.min(1_048_576, normalWork - work), budget);
        budget.note("count_packing_schedule", "fractional=" + analysis.fractional() + "; coupled=" + analysis.coupled() +
                "; covered=" + analysis.covered() + "; roundings=" + analysis.roundings() +
                "; left_points=" + analysis.leftPoints() + "; right_points=" + analysis.rightPoints() +
                "; resume_work=" + analysis.resumeWork() + "; admitted=" + analysis.admitted());
        if (!analysis.admitted()) return false;
        if (analysis.resumeWork() == 0) return beginFlexibleScout();
        // Save one finite allowance, not an absolute deadline that expires
        // while the original frontier receives its structural preference.
        flexiblePending = true;
        flexibleAllowance = flexibleLimit - work;
        flexibleAt = work + analysis.resumeWork();
        return false;
    }

    private void beginDeferredScout() {
        flexiblePending = false;
        if (normalWork - work <= 0) return;
        scoutBase = new Frontier(search, free, coreSize, compiledSize, coreCoefficients, coreBounds, corePreferred);
        search = null; scouting = true;
        coreSize = Math.min(columns.length, Math.max(40, coreSize));
        flexibleLimit = Math.min(normalWork, work + flexibleAllowance);
        scoutLimit = flexibleLimit;
        if (!beginFlexibleScout()) endScout();
    }
    private void endScout() {
        // A restricted miss is feedback about this attempt, never a proof.
        // Include the final atomic step, even when the frontier moved workers.
        if (flexibleScout) failedRoundingWork += Math.max(0, measuredWork() - roundingStart);
        if (search != null) search.close();
        if (wideScout) wideScoutWork += work - wideScoutStart;
        scouting = wideScout = flexibleScout = false;
        search = scoutBase.search(); free = scoutBase.free();
        coreSize = scoutBase.size(); compiledSize = scoutBase.compiledSize();
        coreCoefficients = scoutBase.coefficients(); coreBounds = scoutBase.bounds();
        corePreferred = scoutBase.preferred(); scoutBase = null;
        budget.note("count_packing_core", "resume_original; free=" + coreSize + "; work=" + work);
    }

    private void beginCore() {
        if (search != null) { forcedValues += search.forcedValues(); search.close(); search = null; }
        if (compiledSize != coreSize) compileCore();
        var coefficients = coreCoefficients;
        var residual = coreBounds;
        // Only the separate distance frontier carries a six-coordinate bound.
        if (phase == Phase.DISTANCE_REPAIR && !wideScout) {
            // A search hint, not a valid cut: sum |x - seed| <= radius.
            // The original face and the certified aggregate remain unchanged.
            coefficients = Arrays.copyOf(coefficients, coefficients.length + 1);
            residual = Arrays.copyOf(residual, residual.length + 1);
            var distance = new long[coreSize];
            for (int d = 0; d < coreSize; d++) {
                budget.check(); distance[d] = (corePreferred & (1L << d)) != 0 ? -1 : 1;
            }
            coefficients[coefficients.length - 1] = distance;
            residual[residual.length - 1] = 6 - Long.bitCount(corePreferred);
        }
        // The parent reserves the bounded 35-by-64 coefficients, threshold
        // masks, suffix maxima and retained activity stack together. PB owns
        // its separate, optional reconstruction and conflict-learning storage.
        var mode = wideScout ? CountPackingNeighborhood.Mode.EXCHANGE_FOUR :
                scouting ? CountPackingNeighborhood.Mode.EXCHANGE :
                phase == Phase.CONFLICT_REPAIR ? CountPackingNeighborhood.Mode.LEARNING : CountPackingNeighborhood.Mode.PROPAGATION;
        long flexible = 0;
        if (flexibleScout) for (int i = 0; i < free.length; i++) {
            budget.operation(PlanningBudget.Operation.SCAN, 0);
            if (lp.point[free[i]] > 1e-6 && lp.point[free[i]] < 1 - 1e-6) flexible |= 1L << i;
        }
        search = new CountPackingNeighborhood(coefficients, residual, corePreferred, budget, mode, flexible);
        budget.note("count_packing_core", "begin; variables=" + columns.length + "; capacities=" + matrix.length +
                "; fractional=" + fractional + "; free=" + coreSize + "; phase=" + phase + "; scout=" + scouting +
                "; flexible=" + flexibleScout + "; work=" + work);
    }

    /** Reuse this projection across repair tools; only a different free set
     * needs another substitution of the proposed exterior. */
    private void compileCore() {
        int n = columns.length, m = matrix.length;
        free = Arrays.copyOf(ranked, coreSize);
        // Branch on expensive deviations first; the preferred LP rounding is
        // visited first. Fractional choices remain available in both directions.
        // Sparse exchange enumerates cheap LP deviations first. DFS keeps its
        // expensive-first branching order; both restore to the original columns.
        if (flexibleScout) {
            // Give each half both cheap and expensive deviations. Putting all
            // cheap choices in one half exhausts its radius while leaving the
            // other half's allowance unusable. Original DFS/scouts keep their order.
            int split = free.length / 2;
            for (int i = 0; i < split; i++) { budget.operation(PlanningBudget.Operation.SCAN, 0); free[i] = ranked[2 * i + 1]; }
            for (int i = split; i < free.length; i++) { budget.operation(PlanningBudget.Operation.SCAN, 0); free[i] = ranked[2 * (i - split)]; }
        } else if (!wideScout) for (int i = 0; i < free.length / 2; i++) { int x = free[i]; free[i] = free[free.length - 1 - i]; free[free.length - 1 - i] = x; }
        var full = new ArrayList<long[]>(); var bounds = new ArrayList<Long>();
        for (int r = 0; r < m; r++) { full.add(matrix[r]); bounds.add(rhs[r]); }
        var demand = new long[n]; for (int i = 0; i < n; i++) demand[i] = -profit[i];
        full.add(demand); bounds.add(-target);
        if (aggregate != null) { full.add(aggregate); bounds.add(aggregateBound); }
        var coefficients = new long[full.size()][coreSize];
        var residual = new long[full.size()];
        boolean[] released = new boolean[n]; for (int id : free) released[id] = true;
        long preferred = 0;
        for (int d = 0; d < coreSize; d++) if (seed[free[d]]) preferred |= 1L << d;
        for (int r = 0; r < full.size(); r++) {
            residual[r] = bounds.get(r); long[] a = full.get(r);
            for (int i = 0; i < n; i++) { budget.check(); if (!released[i] && seed[i]) residual[r] -= a[i]; }
            for (int d = coreSize - 1; d >= 0; d--) {
                budget.check(); coefficients[r][d] = a[free[d]];
            }
        }
        coreCoefficients = coefficients; coreBounds = residual;
        corePreferred = preferred; compiledSize = coreSize;
    }

    private boolean valid(BigInteger[] candidate) {
        for (var row : rows) {
            var sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check(); sum = sum.add(term.getValue().multiply(candidate[term.getKey()]));
            }
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private boolean finish(String reason) {
        complete = true; paused = false;
        budget.note("count_packing_core", reason + "; free=" + coreSize + "; lp_steps=" + relaxationSteps + "; nodes=" + nodes +
                "; forced=" + (forcedValues + (search == null ? 0 : search.forcedValues())) + "; phase=" + phase + "; scout=" + scouting + "; work=" + work);
        return true;
    }
    @Override public boolean paused() { return paused; }
    @Override public void resume(long quantum) { until = CountContinuation.deadline(work, quantum, budget); paused = false; }
    @Override public BigInteger[] counts() { return counts; }
    List<CountLpLearning.Cut> sharedRows() { return explanation == null ? List.of() : List.of(explanation); }
    @Override public long work() { return work; }
    Phase phase() { return phase; }
    /** The first exchange owns the early companion service. A new rounding
     * domain uses the ordinary retained portfolio; it cannot renew the first
     * domain's extra service or postpone the original branch's other tools. */
    boolean initialOpportunity() { return phase == Phase.ENUMERATION && (!scoutAttempted || scouting && !flexibleScout); }
    // One completed, owned scout; neither resuming nor observing adds work.
    long restoredScoutWork() { return wideScoutWork; }
    int completionEpoch() { return phase.ordinal() + (wideScoutWork > 0 ? 1 : 0); }
    long remainingAllowance() { return Math.max(0, maximumWork - work); }
    long failedRoundingWork() { return failedRoundingWork; }
    private long measuredWork() { return work + (stepStart < 0 ? 0 : budget.threadWork() - stepStart); }
    // Completed numerical pivots/bound flips are retained progress too. Waiting
    // for the final LP point hid this frontier from the bounded completion grant.
    @Override public long progress() { return relaxationSteps + nodes; }
    @Override public boolean matches(List<ExactLinearProgram.Constraint> other, BigInteger[] low, BigInteger[] high) {
        return rows.equals(other) && Arrays.equals(lower, low) && Arrays.equals(upper, high);
    }
    @Override public void close() {
        if (search != null) search.close();
        if (scoutBase != null) scoutBase.search().close();
        scoutBase = null;
        search = null;
        coreCoefficients = null; coreBounds = null;
        budget.release(memory); memory = 0;
        if (ownsStructure) structure.close();
    }

    /** Primal simplex with variable bounds; the tableau has capacity rows only. */
    private static final class Relaxation {
        final double[][] tableau;
        final double[] point, cost;
        final double[] scales;
        final double objectiveScale;
        final int[] basis;
        final boolean[] basic;
        final int n, m;
        final PlanningBudget budget;
        boolean done;
        int degeneracies;

        Relaxation(long[][] a, long[] b, long[] c, PlanningBudget budget) {
            n = c.length; m = b.length; this.budget = budget;
            tableau = new double[m][n + m]; point = new double[n + m]; cost = new double[n + m];
            scales = new double[m];
            basis = new int[m]; basic = new boolean[n + m];
            objectiveScale = Math.max(1, Arrays.stream(c).max().orElse(1));
            for (int i = 0; i < n; i++) cost[i] = c[i] / objectiveScale;
            for (int r = 0; r < m; r++) {
                scales[r] = Math.max(1, Arrays.stream(a[r]).max().orElse(1));
                for (int i = 0; i < n; i++) tableau[r][i] = a[r][i] / scales[r];
                tableau[r][n + r] = 1; point[n + r] = b[r] / scales[r]; basis[r] = n + r; basic[n + r] = true;
            }
            budget.charge((long) m * (n + m) / 4 + n + m);
        }

        double price(int row) { return -cost[n + row] * objectiveScale / scales[row]; }

        boolean step() {
            int enter = -1; double gain = 1e-7;
            budget.charge((n + m + 3L) / 4);
            for (int i = 0; i < n + m; i++) if (!basic[i]) {
                double improvement = point[i] > 0.5 ? -cost[i] : cost[i];
                if (improvement > gain) { enter = i; gain = improvement; if (degeneracies > m + n) break; }
            }
            if (enter < 0) { done = true; return true; }
            double direction = point[enter] > 0.5 ? -1 : 1;
            double delta = enter < n ? 1 : Double.POSITIVE_INFINITY; int leave = -1;
            budget.charge(m);
            for (int r = 0; r < m; r++) {
                double a = tableau[r][enter] * direction, x = point[basis[r]];
                double step = a > 1e-10 ? x / a : a < -1e-10 && basis[r] < n ? (1 - x) / -a : Double.POSITIVE_INFINITY;
                if (step < delta - 1e-9 || Math.abs(step - delta) <= 1e-9 && leave >= 0 && basis[r] < basis[leave]) {
                    delta = Math.max(0, step); leave = r;
                }
            }
            if (!Double.isFinite(delta)) return false;
            if (delta < 1e-9) degeneracies++; else degeneracies = 0;
            for (int r = 0; r < m; r++) point[basis[r]] -= tableau[r][enter] * direction * delta;
            point[enter] += direction * delta;
            if (leave < 0) return true;
            int outgoing = basis[leave];
            point[outgoing] = outgoing < n && point[outgoing] > 0.5 ? 1 : 0;
            double pivot = tableau[leave][enter];
            if (!Double.isFinite(pivot) || Math.abs(pivot) < 1e-12) return false;
            // A complete pivot is the continuation boundary. Charge every row
            // before mutating it, with cancellation checks between rows.
            budget.charge((n + m + 3L) / 4);
            for (int i = 0; i < n + m; i++) tableau[leave][i] /= pivot;
            for (int r = 0; r < m; r++) if (r != leave) {
                budget.charge((n + m + 3L) / 4); double a = tableau[r][enter];
                for (int i = 0; i < n + m; i++) tableau[r][i] -= a * tableau[leave][i];
            }
            budget.charge((n + m + 3L) / 4); double a = cost[enter];
            for (int i = 0; i < n + m; i++) cost[i] -= a * tableau[leave][i];
            basic[outgoing] = false; basic[enter] = true; basis[leave] = enter;
            return true;
        }
    }
}
