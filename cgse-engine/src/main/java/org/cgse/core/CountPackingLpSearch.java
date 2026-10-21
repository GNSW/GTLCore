// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Private Boolean candidate search with a retained LP basis at each depth.
 * Numerical failures and exhausted branches never prove the face infeasible.
 * Only exact checks of every input row can publish a candidate.
 */
final class CountPackingLpSearch implements AutoCloseable {
    private static final long MAGNITUDE_LIMIT = 1L << 60;
    private static final BigInteger PRICE_SCALE = BigInteger.valueOf(65536);
    private final long[][] rows, capacity;
    private final long[] bounds, rhs, profit;
    private final long target, objectiveMagnitude;
    private final PlanningBudget budget;
    private final int n, m, total;
    private final double[] scales;
    private final double objectiveScale;
    private final Frame[] frames;
    private final long[] priceWeights, reducedCosts, rowMagnitudes;
    private final BigInteger[] weights, exactReducedCosts;
    private long weightedRhs, priceBuilds, priceReuses;
    private BigInteger exactWeightedRhs;
    private int certificate;
    private long memory, assignment, nodes, pivots, refusals, implications, narrowBounds, wideBounds;
    private int depth;
    private boolean initialized, complete, feasible, warm;

    /** Borrowed only during construction. Identity binds the basis proposal to
     * its paid projection and request; it is never a shared cut or proof. */
    record Root(long[][] rows, long[] bounds, PlanningBudget budget,
                double[][] tableau, double[] point, double[] cost, int[] basis,
                double[] scales, double objectiveScale, int[] free, boolean[] seed) {}

    private static final class Frame {
        final double[][] tableau;
        final double[] point, cost;
        final int[] basis;
        final boolean[] basic;
        long fixed, ones;
        int branch = -1, turn, iterations;
        boolean first;

        Frame(int rows, int columns) {
            tableau = new double[rows][columns];
            point = new double[columns]; cost = new double[columns];
            basis = new int[rows]; basic = new boolean[columns];
        }
    }

    static CountPackingLpSearch create(long[][] rows, long[] bounds, PlanningBudget budget) {
        if (rows.length == 0 || rows.length != bounds.length || rows.length > 35) return null;
        int n = rows[0].length;
        if (n < 1 || n > 64) return null;
        int demand = -1, capacities = 0;
        for (int r = 0; r < rows.length; r++) {
            if (rows[r].length != n || bounds[r] <= -MAGNITUDE_LIMIT || bounds[r] >= MAGNITUDE_LIMIT) return null;
            long magnitude = Math.abs(bounds[r]);
            boolean positive = false, negative = false;
            for (long value : rows[r]) {
                budget.check();
                if (value <= -MAGNITUDE_LIMIT || value >= MAGNITUDE_LIMIT) return null;
                long absolute = Math.abs(value);
                if (absolute >= MAGNITUDE_LIMIT - magnitude) return null;
                magnitude += absolute;
                positive |= value > 0; negative |= value < 0;
            }
            if (negative && !positive && demand < 0) demand = r;
            else if (positive && !negative) capacities++;
        }
        if (demand < 0 || capacities == 0) return null;
        // Reserve all depth buffers and bounded exact-integer scratch before
        // allocation. Revisited siblings reuse their depth's existing arrays.
        int total = n + capacities;
        long bytes = 16384L + (n + 1L) * (512 + 24L * total + 8L * capacities * total + 48L * capacities);
        if (!budget.tryReserve(bytes)) return null;
        try {
            var result = new CountPackingLpSearch(rows, bounds, demand, capacities, budget, bytes);
            bytes = 0;
            return result;
        } finally { budget.release(bytes); }
    }

    private CountPackingLpSearch(long[][] rows, long[] bounds, int demand, int capacities,
                                 PlanningBudget budget, long memory) {
        this.rows = rows; this.bounds = bounds; this.budget = budget; this.memory = memory;
        n = rows[0].length; m = capacities; total = n + m;
        capacity = new long[m][]; rhs = new long[m]; profit = new long[n];
        scales = new double[m]; frames = new Frame[n + 1]; weights = new BigInteger[m];
        priceWeights = new long[m]; reducedCosts = new long[n]; rowMagnitudes = new long[m];
        exactReducedCosts = new BigInteger[n];
        long largest = 1, magnitude = Math.abs(bounds[demand]);
        for (int i = 0; i < n; i++) {
            profit[i] = -rows[demand][i]; largest = Math.max(largest, profit[i]); magnitude += profit[i];
        }
        objectiveMagnitude = magnitude <= Long.MAX_VALUE / 65536 ? magnitude * 65536 : -1;
        objectiveScale = largest; target = -bounds[demand];
        var root = frames[0] = new Frame(m, total);
        for (int i = 0; i < n; i++) root.cost[i] = profit[i] / objectiveScale;
        int at = 0;
        for (int r = 0; r < rows.length; r++) {
            boolean positive = false, negative = false;
            for (long value : rows[r]) { budget.check(); positive |= value > 0; negative |= value < 0; }
            if (!positive || negative) continue;
            capacity[at] = rows[r]; rhs[at] = bounds[r];
            if (bounds[r] < 0) { complete = true; return; }
            long scale = 1; rowMagnitudes[at] = bounds[r];
            for (long value : rows[r]) { scale = Math.max(scale, value); rowMagnitudes[at] += value; }
            scales[at] = scale;
            budget.charge((total + 3L) / 4);
            for (int i = 0; i < n; i++) root.tableau[at][i] = rows[r][i] / scales[at];
            root.tableau[at][n + at] = 1; root.point[n + at] = bounds[r] / scales[at];
            root.basic[n + at] = true; root.basis[at] = n + at;
            at++;
        }
    }

    boolean warm(Root source) {
        if (initialized || complete || source == null || source.rows != rows ||
                source.bounds != bounds || source.budget != budget ||
                source.free.length != n || source.basis.length != m ||
                source.tableau.length != m || source.scales.length != m) return false;
        int width = source.seed.length;
        if (width < n || source.point.length != width + m || source.cost.length != width + m ||
                !Double.isFinite(source.objectiveScale) || source.objectiveScale <= 0) return false;
        long bytes = 256L + 4L * width + 16L * total;
        if (!budget.tryReserve(bytes)) return false;
        try {
            var mapping = new int[width];
            budget.charge(width);
            Arrays.fill(mapping, -1);
            var factors = new double[total];
            Arrays.fill(factors, 1);
            for (int j = 0; j < n; j++) {
                budget.check();
                int column = source.free[j];
                if (column < 0 || column >= width || mapping[column] >= 0) return false;
                mapping[column] = j;
            }
            for (int r = 0; r < m; r++) {
                budget.check();
                // The parent capacities are the projection's leading rows.
                // Equal row counts alone also admit a dropped empty row plus
                // an added aggregate, which is a different relaxation.
                if (capacity[r] != rows[r] || source.tableau[r].length != width + m ||
                        !Double.isFinite(source.scales[r]) || source.scales[r] <= 0) return false;
                factors[n + r] = scales[r] / source.scales[r];
                if (!Double.isFinite(factors[n + r]) || factors[n + r] <= 0) return false;
                int column = source.basis[r];
                // Removing a basic column requires another factorization.
                // Keep the cold entry for that structure, without guessing.
                if (column < 0 || column >= width + m || column < width && mapping[column] < 0) return false;
            }
            // One spare depth is already reserved. Prepare there so an invalid
            // numerical proposal cannot partially overwrite the cold root.
            var next = frames[1];
            if (next == null) next = frames[1] = new Frame(m, total);
            Arrays.fill(next.basic, false);
            for (int j = 0; j < total; j++) {
                budget.check();
                int from = j < n ? source.free[j] : width + j - n;
                double point = source.point[from] / factors[j];
                double cost = source.cost[from] * factors[j] * source.objectiveScale / objectiveScale;
                if (!Double.isFinite(point) || !Double.isFinite(cost)) return false;
                next.point[j] = point; next.cost[j] = cost;
            }
            for (int r = 0; r < m; r++) {
                int from = source.basis[r], id = from < width ? mapping[from] : n + from - width;
                if (next.basic[id]) return false;
                next.basis[r] = id; next.basic[id] = true;
                double point = source.point[from];
                // Exterior substitutions use the actual proposed seed, including
                // small numerical departures from a nonbasic bound.
                for (int j = 0; j < width; j++) {
                    budget.check();
                    if (mapping[j] < 0)
                        point -= source.tableau[r][j] * ((source.seed[j] ? 1 : 0) - source.point[j]);
                }
                point /= factors[id];
                if (!Double.isFinite(point)) return false;
                next.point[id] = point;
                for (int j = 0; j < total; j++) {
                    budget.check();
                    int column = j < n ? source.free[j] : width + j - n;
                    // Full and projected slacks may have different row scales.
                    double value = source.tableau[r][column] * factors[j] / factors[id];
                    if (!Double.isFinite(value)) return false;
                    next.tableau[r][j] = value;
                }
            }
            next.fixed = next.ones = 0;
            next.branch = -1; next.turn = next.iterations = 0; next.first = false;
            frames[1] = frames[0]; frames[0] = next;
            initialized = warm = true;
            return true;
        } finally { budget.release(bytes); }
    }

    boolean step() {
        if (complete) return true;
        budget.checkpoint();
        if (depth < 0) { complete = true; return true; }
        var frame = frames[depth];
        if (!initialized) {
            if (++frame.iterations > 8192) { refusals++; complete = true; return true; }
            int state = primal(frame);
            if (state < 0) { refusals++; complete = true; return true; }
            if (state == 0) return false;
            initialized = true; frame.iterations = 0;
        }
        if (frame.branch >= 0) {
            if (frame.turn == 2) { depth--; return false; }
            boolean value = frame.turn++ == 0 ? frame.first : !frame.first;
            var child = copy(frame);
            fix(child, frame.branch, value);
            depth++;
            return false;
        }
        if (++frame.iterations > 512) { refusals++; depth--; return false; }
        int state = dual(frame);
        if (state < 0) { refusals++; depth--; return false; }
        if (state == 0) return false;
        nodes++;
        long fixed = frame.fixed;
        if (!possible(frame)) { depth--; return false; }
        // A priced implication changes this node's box. Reoptimize the retained
        // basis before branching or rounding; siblings inherit only their parent.
        if (frame.fixed != fixed) return false;
        int branch = -1; double nearest = 1;
        long mask = frame.ones;
        for (int i = 0; i < n; i++) {
            budget.check(); double value = frame.point[i];
            if (!Double.isFinite(value)) { refusals++; depth--; return false; }
            if ((frame.fixed & (1L << i)) != 0) continue;
            if (value >= 0.5) mask |= 1L << i;
            if (value > 1e-6 && value < 1 - 1e-6 && Math.abs(value - 0.5) < nearest) {
                nearest = Math.abs(value - 0.5); branch = i;
            }
        }
        if (valid(mask)) { assignment = mask; feasible = complete = true; return true; }
        // Extra mixed rows need not belong to this numerical relaxation.
        // An integral LP point violating one is a declined proposal, not UNSAT.
        if (branch < 0) { refusals++; depth--; return false; }
        frame.branch = branch; frame.first = frame.point[branch] >= 0.5;
        return false;
    }

    private Frame copy(Frame source) {
        var target = frames[depth + 1];
        if (target == null) target = frames[depth + 1] = new Frame(m, total);
        for (int r = 0; r < m; r++) {
            budget.charge((total + 3L) / 4);
            System.arraycopy(source.tableau[r], 0, target.tableau[r], 0, total);
        }
        budget.charge(total + m);
        System.arraycopy(source.point, 0, target.point, 0, total);
        System.arraycopy(source.cost, 0, target.cost, 0, total);
        System.arraycopy(source.basis, 0, target.basis, 0, m);
        System.arraycopy(source.basic, 0, target.basic, 0, total);
        target.fixed = source.fixed; target.ones = source.ones;
        target.branch = -1; target.turn = target.iterations = 0; target.first = false;
        return target;
    }

    private int primal(Frame frame) {
        int enter = -1; double gain = 1e-8;
        budget.charge((total + 3L) / 4);
        for (int i = 0; i < total; i++) if (!frame.basic[i]) {
            double improvement = frame.point[i] > 0.5 ? -frame.cost[i] : frame.cost[i];
            if (improvement > gain) { enter = i; gain = improvement; }
        }
        if (enter < 0) return 1;
        double direction = frame.point[enter] > 0.5 ? -1 : 1;
        double delta = enter < n ? 1 : Double.POSITIVE_INFINITY;
        int leave = -1;
        budget.charge(m);
        for (int r = 0; r < m; r++) {
            double coefficient = frame.tableau[r][enter] * direction, value = frame.point[frame.basis[r]];
            double step = coefficient > 1e-10 ? value / coefficient :
                    coefficient < -1e-10 && frame.basis[r] < n ? (1 - value) / -coefficient : Double.POSITIVE_INFINITY;
            if (step < delta - 1e-9 || Math.abs(step - delta) <= 1e-9 && leave >= 0 && frame.basis[r] < frame.basis[leave]) {
                delta = Math.max(0, step); leave = r;
            }
        }
        if (!Double.isFinite(delta)) return -1;
        for (int r = 0; r < m; r++) frame.point[frame.basis[r]] -= frame.tableau[r][enter] * direction * delta;
        frame.point[enter] += direction * delta;
        if (leave < 0) return 0;
        int outgoing = frame.basis[leave];
        frame.point[outgoing] = outgoing < n && frame.point[outgoing] > 0.5 ? 1 : 0;
        return pivot(frame, leave, enter) ? 0 : -1;
    }

    private int dual(Frame frame) {
        int leave = -1; double violation = 1e-7, required = 0;
        budget.charge(m);
        for (int r = 0; r < m; r++) {
            int id = frame.basis[r];
            double low = id < n && (frame.fixed & (1L << id)) != 0 ? ((frame.ones & (1L << id)) != 0 ? 1 : 0) : 0;
            double high = id < n ? ((frame.fixed & (1L << id)) != 0 ? low : 1) : Double.POSITIVE_INFINITY;
            double need = frame.point[id] < low ? low - frame.point[id] : frame.point[id] > high ? high - frame.point[id] : 0;
            if (Math.abs(need) > violation) { violation = Math.abs(need); leave = r; required = need; }
        }
        if (leave < 0) return 1;
        int enter = -1; double best = Double.POSITIVE_INFINITY;
        budget.charge((total + 3L) / 4);
        for (int i = 0; i < total; i++) if (!frame.basic[i] && (i >= n || (frame.fixed & (1L << i)) == 0)) {
            double direction = i < n && frame.point[i] > 0.5 ? -1 : 1;
            double improve = -frame.tableau[leave][i] * direction * Math.signum(required);
            if (improve <= 1e-10) continue;
            double ratio = Math.max(0, -frame.cost[i] * direction) / improve;
            if (ratio < best - 1e-10) { best = ratio; enter = i; }
        }
        if (enter < 0) return -1;
        double delta = -required / frame.tableau[leave][enter];
        budget.charge(m + 1);
        for (int r = 0; r < m; r++) frame.point[frame.basis[r]] -= frame.tableau[r][enter] * delta;
        frame.point[enter] += delta;
        frame.point[frame.basis[leave]] = Math.rint(frame.point[frame.basis[leave]]);
        return pivot(frame, leave, enter) ? 0 : -1;
    }

    private boolean pivot(Frame frame, int leave, int enter) {
        double pivot = frame.tableau[leave][enter];
        if (!Double.isFinite(pivot) || Math.abs(pivot) < 1e-12) return false;
        budget.charge((total + 3L) / 4);
        for (int i = 0; i < total; i++) frame.tableau[leave][i] /= pivot;
        for (int r = 0; r < m; r++) if (r != leave) {
            budget.charge((total + 3L) / 4); double factor = frame.tableau[r][enter];
            for (int i = 0; i < total; i++) frame.tableau[r][i] -= factor * frame.tableau[leave][i];
        }
        budget.charge((total + 3L) / 4); double factor = frame.cost[enter];
        for (int i = 0; i < total; i++) frame.cost[i] -= factor * frame.tableau[leave][i];
        frame.basic[frame.basis[leave]] = false; frame.basic[enter] = true; frame.basis[leave] = enter;
        pivots++;
        return true;
    }

    private boolean possible(Frame frame) {
        // Any nonnegative prices give a valid upper bound over this fixed
        // Boolean box. Round only the prices; build the certificate with integers.
        // No floating LP status, tableau value or reduced-cost tolerance proves it.
        budget.charge(m);
        for (int r = 0; r < m; r++) {
            double price = -frame.cost[n + r] * objectiveScale / scales[r];
            long weight = Double.isFinite(price) && price > 0 ? Math.min(1_000_000_000_000L, Math.round(price * 65536)) : 0;
            if (priceWeights[r] != weight) { certificate = 0; priceWeights[r] = weight; }
        }
        // These products depend only on the immutable projection and the
        // complete integer price vector, not on this node's fixed box. Keep
        // them through implications/sibling handoffs; never keep the box gap
        // or its inferred literals. Publish only after every product completes.
        if (certificate == 0) {
            if (narrowCertificate()) buildNarrowCertificate();
            if (certificate == 0) buildExactCertificate();
            priceBuilds++;
        } else priceReuses++;
        if (certificate == 2) return possibleExact(frame);
        long upper = weightedRhs;
        for (int j = 0; j < n; j++) {
            budget.operation(PlanningBudget.Operation.SCAN, 64);
            long reduced = reducedCosts[j];
            if ((frame.fixed & (1L << j)) == 0 ? reduced > 0 : (frame.ones & (1L << j)) != 0)
                upper = Math.addExact(upper, reduced);
        }
        long gap = Math.subtractExact(upper, Math.multiplyExact(target, 65536));
        narrowBounds++;
        if (gap < 0) return false;
        for (int j = 0; j < n; j++) {
            budget.operation(PlanningBudget.Operation.SCAN, 64);
            if ((frame.fixed & (1L << j)) != 0) continue;
            if (reducedCosts[j] > gap) { fix(frame, j, true); implications++; }
            else if (reducedCosts[j] < -gap) { fix(frame, j, false); implications++; }
        }
        return true;
    }

    private void buildNarrowCertificate() {
        try {
            long upper = 0;
            for (int r = 0; r < m; r++) {
                budget.operation(PlanningBudget.Operation.INTEGER, 64);
                upper = Math.addExact(upper, Math.multiplyExact(priceWeights[r], rhs[r]));
            }
            for (int j = 0; j < n; j++) {
                long reduced = Math.multiplyExact(profit[j], 65536);
                for (int r = 0; r < m; r++) {
                    budget.operation(PlanningBudget.Operation.INTEGER, 64);
                    reduced = Math.subtractExact(reduced, Math.multiplyExact(priceWeights[r], capacity[r][j]));
                }
                reducedCosts[j] = reduced;
            }
            weightedRhs = upper; certificate = 1;
        } catch (ArithmeticException overflow) {
            // No domain changes or reusable state have been published.
            // Retry at wider precision, never with saturated/partial products.
            certificate = 0;
        }
    }

    private boolean narrowCertificate() {
        // A cheap absolute envelope covers every product, partial sum and gap.
        // Large rows skip the narrow attempt instead of throwing at each node.
        if (objectiveMagnitude < 0) return false;
        long remaining = Long.MAX_VALUE - objectiveMagnitude;
        for (int r = 0; r < m; r++) {
            budget.operation(PlanningBudget.Operation.SCAN, 64);
            long weight = priceWeights[r];
            if (weight == 0) continue;
            if (rowMagnitudes[r] > remaining / weight) return false;
            remaining -= weight * rowMagnitudes[r];
        }
        return true;
    }

    private void buildExactCertificate() {
        var upper = BigInteger.ZERO;
        for (int r = 0; r < m; r++) {
            budget.operation(PlanningBudget.Operation.INTEGER, 128);
            weights[r] = BigInteger.valueOf(priceWeights[r]);
            upper = upper.add(weights[r].multiply(BigInteger.valueOf(rhs[r])));
        }
        for (int j = 0; j < n; j++) {
            var reduced = BigInteger.valueOf(profit[j]).multiply(PRICE_SCALE);
            for (int r = 0; r < m; r++) {
                budget.operation(PlanningBudget.Operation.INTEGER, 128);
                reduced = reduced.subtract(weights[r].multiply(BigInteger.valueOf(capacity[r][j])));
            }
            exactReducedCosts[j] = reduced;
        }
        exactWeightedRhs = upper; certificate = 2;
    }

    private boolean possibleExact(Frame frame) {
        wideBounds++;
        var upper = exactWeightedRhs;
        for (int j = 0; j < n; j++) {
            budget.operation(PlanningBudget.Operation.INTEGER, 128);
            var reduced = exactReducedCosts[j];
            if ((frame.fixed & (1L << j)) == 0 ? reduced.signum() > 0 : (frame.ones & (1L << j)) != 0)
                upper = upper.add(reduced);
        }
        var gap = upper.subtract(BigInteger.valueOf(target).multiply(PRICE_SCALE));
        if (gap.signum() < 0) return false;
        var negativeGap = gap.negate();
        for (int j = 0; j < n; j++) {
            budget.operation(PlanningBudget.Operation.INTEGER, 128);
            if ((frame.fixed & (1L << j)) != 0) continue;
            var reduced = exactReducedCosts[j];
            if (reduced.compareTo(gap) > 0) { fix(frame, j, true); implications++; }
            else if (reduced.compareTo(negativeGap) < 0) { fix(frame, j, false); implications++; }
        }
        return true;
    }

    private void fix(Frame frame, int column, boolean value) {
        frame.fixed |= 1L << column;
        if (value) frame.ones |= 1L << column; else frame.ones &= ~(1L << column);
        if (!frame.basic[column]) {
            double delta = (value ? 1 : 0) - frame.point[column];
            budget.charge(1);
            if (delta == 0) return;
            budget.charge(m);
            for (int r = 0; r < m; r++) frame.point[frame.basis[r]] -= frame.tableau[r][column] * delta;
            frame.point[column] = value ? 1 : 0;
        }
    }

    private boolean valid(long mask) {
        // Admission bounds the absolute row activity, so primitive sums are exact.
        for (int r = 0; r < rows.length; r++) {
            long sum = 0; budget.charge(n);
            for (int i = 0; i < n; i++) if ((mask & (1L << i)) != 0) sum += rows[r][i];
            if (sum > bounds[r]) return false;
        }
        return true;
    }

    boolean feasible() { return feasible; }
    long assignment() {
        if (!feasible) throw new IllegalStateException("No LP candidate");
        return assignment;
    }
    String statistics() {
        return "nodes=" + nodes + "; pivots=" + pivots + "; refusals=" + refusals +
                "; implications=" + implications + "; narrow=" + narrowBounds + "; wide=" + wideBounds +
                "; price_builds=" + priceBuilds + "; price_reuses=" + priceReuses + "; warm=" + warm + "; witness=" + feasible;
    }

    @Override public void close() {
        complete = true;
        Arrays.fill(frames, null); Arrays.fill(weights, null); Arrays.fill(exactReducedCosts, null);
        certificate = 0; exactWeightedRhs = null;
        budget.release(memory); memory = 0;
    }
}
