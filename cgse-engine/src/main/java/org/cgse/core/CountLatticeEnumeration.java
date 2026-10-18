// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Bounded candidate enumeration in a borrowed, reduced integer basis. Numerical
 * sphere/box pruning guides this private search only; every returned vector is
 * checked against all original integer bounds and rows. Exhaustion, nonfinite
 * hints and precision refusal never prove the original model infeasible.
 */
final class CountLatticeEnumeration implements AutoCloseable {
    private static final long MAX_COORDINATE = 1L << 40;
    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper, point;
    private final List<BigInteger[]> basis;
    private final ExactRational[][] mu;
    private final ExactRational[] norms;
    private final PlanningBudget budget;
    private ExactRational[] residual, projections;
    private double[] projectedValues, centerValues, distances, normValues;
    private double[][] muValues;
    private double[][] orthogonal, reach, offsets;
    private double[] halfWidths;
    private long[] leftValues, rightValues;
    private double radiusValue;
    private BigInteger[] values, counts;
    private ExactRational radius = ExactRational.ZERO;
    private long memory, work, nodes;
    private int prepared, geometry, level;
    private boolean complete, entered, ready;

    CountLatticeEnumeration(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                            BigInteger[] point, List<BigInteger[]> basis, ExactRational[][] mu,
                            ExactRational[] norms, PlanningBudget budget) {
        this.rows = rows; this.lower = lower; this.upper = upper; this.point = point;
        this.basis = basis; this.mu = mu; this.norms = norms; this.budget = budget;
        long bytes = 4096L + 2048L * (lower.length + basis.size()) + 32L * lower.length * basis.size();
        if (basis.size() < 2 || basis.size() > 48 || lower.length > 128 || !budget.tryReserve(bytes)) {
            complete = true; return;
        }
        memory = bytes;
    }

    boolean step() {
        if (complete) return true;
        long before = budget.threadWork();
        try {
            budget.checkpoint(); budget.check();
            if (residual == null) {
                int n = point.length, d = basis.size();
                residual = new ExactRational[n]; projections = new ExactRational[d];
                values = new BigInteger[d];
                for (int i = 0; i < n; i++) {
                    budget.check();
                    var width = new ExactRational(upper[i].subtract(lower[i]), BigInteger.TWO);
                    radius = add(radius, multiply(width, width));
                    residual[i] = add(ExactRational.of(lower[i].subtract(point[i])), width);
                }
                return false;
            }
            if (prepared < basis.size()) {
                int j = prepared++;
                var projected = ExactRational.ZERO;
                for (int i = 0; i < point.length; i++) {
                    budget.check(); projected = add(projected, multiply(residual[i], ExactRational.of(basis.get(j)[i])));
                }
                for (int i = 0; i < j; i++) projected = subtract(projected, multiply(mu[j][i], projections[i]));
                projections[j] = projected;
                return false;
            }
            if (projectedValues == null) {
                var outside = ExactRational.ZERO;
                for (var x : residual) outside = add(outside, multiply(x, x));
                for (int j = 0; j < basis.size(); j++)
                    outside = subtract(outside, divide(multiply(projections[j], projections[j]), norms[j]));
                radius = subtract(radius, outside);
                if (radius.signum() < 0) return complete = true;
                for (int j = 0; j < basis.size(); j++) projections[j] = divide(projections[j], norms[j]);
                int d = basis.size();
                projectedValues = new double[d]; centerValues = new double[d]; distances = new double[d + 1]; normValues = new double[d];
                leftValues = new long[d]; rightValues = new long[d]; muValues = new double[d][d];
                radiusValue = numeric(radius);
                if (!Double.isFinite(radiusValue)) return complete = true;
                for (int j = 0; j < d; j++) {
                    budget.check();
                    projectedValues[j] = numeric(projections[j]); normValues[j] = numeric(norms[j]);
                    if (!Double.isFinite(projectedValues[j]) || !Double.isFinite(normValues[j]) || normValues[j] <= 0) return complete = true;
                    for (int k = 0; k < j; k++) {
                        budget.check(); muValues[j][k] = numeric(mu[j][k]);
                        if (!Double.isFinite(muValues[j][k])) return complete = true;
                    }
                }
                int n = point.length;
                orthogonal = new double[d][n]; reach = new double[d][n]; offsets = new double[d + 1][n]; halfWidths = new double[n];
                for (int i = 0; i < n; i++) {
                    budget.check();
                    halfWidths[i] = upper[i].subtract(lower[i]).doubleValue() / 2;
                    offsets[d][i] = -numeric(residual[i]);
                    if (!Double.isFinite(halfWidths[i]) || !Double.isFinite(offsets[d][i])) return complete = true;
                }
                return false;
            }
            if (geometry < basis.size()) {
                int j = geometry++;
                // Reconstruct B* from the current factors. Original Gram
                // vectors may predate the parent's size reductions/swaps.
                for (int i = 0; i < point.length; i++) {
                    budget.check(); double value = basis.get(j)[i].doubleValue();
                    for (int k = 0; k < j; k++) { budget.check(); value -= muValues[j][k] * orthogonal[k][i]; }
                    orthogonal[j][i] = value;
                    reach[j][i] = (j == 0 ? 0 : reach[j - 1][i]) + value * value / normValues[j];
                    offsets[basis.size()][i] += projectedValues[j] * value;
                    if (!Double.isFinite(reach[j][i]) || !Double.isFinite(offsets[basis.size()][i])) return complete = true;
                }
                return false;
            }
            if (!ready) {
                ready = true; level = basis.size() - 1;
                return false;
            }
            if (level == basis.size()) return complete = true;
            if (!entered) {
                double center = projectedValues[level];
                for (int k = level + 1; k < basis.size(); k++) {
                    budget.check(); center -= muValues[k][level] * values[k].longValue();
                }
                if (!Double.isFinite(center) || Math.abs(center) > MAX_COORDINATE) return complete = true;
                centerValues[level] = center; leftValues[level] = (long) Math.floor(center); rightValues[level] = leftValues[level] + 1;
                entered = true;
            }
            double a = centerValues[level] - leftValues[level], b = rightValues[level] - centerValues[level];
            double delta = Math.min(a, b), total = distances[level + 1] + delta * delta * normValues[level];
            if (!Double.isFinite(total) || total > radiusValue + 1e-9 * Math.max(1, radiusValue)) { level++; entered = true; return false; }
            if (Math.abs(a <= b ? leftValues[level] : rightValues[level]) > MAX_COORDINATE) return complete = true;
            values[level] = BigInteger.valueOf(a <= b ? leftValues[level]-- : rightValues[level]++);
            distances[level] = total; nodes++;
            boolean boxPossible = true;
            double coefficient = values[level].longValue() - centerValues[level];
            double available = Math.max(0, radiusValue - total);
            double boxDistance = 0;
            // The remaining orthogonal span can change coordinate i by at
            // most sqrt(remaining radius * its squared projection length).
            // Include the fixed component outside the lattice span. This
            // rejects sphere branches that cannot meet the original box,
            // before constructing all of their complete integer vectors.
            for (int i = 0; i < point.length; i++) {
                budget.check();
                offsets[level][i] = offsets[level + 1][i] + coefficient * orthogonal[level][i];
                double deficit = Math.max(0, Math.abs(offsets[level][i]) - halfWidths[i]);
                boxDistance += deficit * deficit;
                double allowance = level == 0 ? 0 : available * reach[level - 1][i];
                if (deficit * deficit > allowance + 1e-7 * Math.max(1, radiusValue)) boxPossible = false;
            }
            // Satisfying every interval also requires enough total movement:
            // separate coordinate bounds can all pass while their squared
            // distances together exceed the remaining orthogonal radius.
            if (!boxPossible || boxDistance > available + 1e-7 * Math.max(1, radiusValue)) return false;
            if (level > 0) { level--; entered = false; return false; }
            var candidate = point.clone();
            for (int i = 0; i < point.length; i++) {
                for (int j = 0; j < basis.size(); j++) {
                    budget.check(); integer(candidate[i], values[j], basis.get(j)[i]);
                    candidate[i] = candidate[i].add(values[j].multiply(basis.get(j)[i]));
                }
                if (candidate[i].compareTo(lower[i]) < 0 || candidate[i].compareTo(upper[i]) > 0) return false;
            }
            for (var row : rows) {
                var sum = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) {
                    budget.check(); integer(sum, term.getValue(), candidate[term.getKey()]);
                    sum = sum.add(term.getValue().multiply(candidate[term.getKey()]));
                }
                if (sum.compareTo(row.upper()) > 0) return false;
            }
            counts = candidate;
            return complete = true;
        } catch (ExactRational.PrecisionLimit refusal) { return complete = true; }
        finally { work += budget.threadWork() - before; }
    }

    private void integer(BigInteger... values) {
        int bits = 1; for (var value : values) bits = Math.max(bits, value.bitLength());
        budget.operation(PlanningBudget.Operation.INTEGER, bits);
    }
    private static double numeric(ExactRational value) { return value.numerator().doubleValue() / value.denominator().doubleValue(); }
    private void rational(ExactRational a, ExactRational b) {
        budget.operation(PlanningBudget.Operation.RATIONAL, Math.max(Math.max(a.numerator().bitLength(), a.denominator().bitLength()),
                Math.max(b.numerator().bitLength(), b.denominator().bitLength())));
    }
    private ExactRational add(ExactRational a, ExactRational b) { rational(a, b); return a.add(b); }
    private ExactRational subtract(ExactRational a, ExactRational b) { rational(a, b); return a.subtract(b); }
    private ExactRational multiply(ExactRational a, ExactRational b) { rational(a, b); return a.multiply(b); }
    private ExactRational divide(ExactRational a, ExactRational b) { rational(a, b); return a.divide(b); }
    BigInteger[] counts() { return counts; }
    long work() { return work; }
    long progress() { return prepared + geometry + nodes; }
    @Override public void close() {
        residual = projections = null;
        projectedValues = centerValues = distances = normValues = halfWidths = null;
        muValues = orthogonal = reach = offsets = null;
        leftValues = rightValues = null; values = null;
        budget.release(memory); memory = 0; complete = true;
    }
}
