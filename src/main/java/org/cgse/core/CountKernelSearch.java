// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Search a reduced integer kernel; only original-coordinate witnesses escape. */
final class CountKernelSearch implements AutoCloseable {
    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }

    private final List<ExactLinearProgram.Constraint> original;
    private final BigInteger[] lower, upper, point;
    private final List<BigInteger[]> basis;
    private final PlanningBudget budget;
    private long allowance;
    private final ExactRational[][] mu;
    private final ExactRational[] norms;
    private ExactRational[][] orthogonal, dual;
    private BigInteger[] low, high, values, restored;
    private final List<ExactLinearProgram.Constraint> projected = new ArrayList<>();
    private ExactRational minimum = ExactRational.ZERO, maximum = ExactRational.ZERO;
    private int phase, row, coordinate;
    private CountJump search;
    private BigInteger[] counts;
    private long memory, work, started, checkpoints;
    private boolean complete, retaining, paused;

    CountKernelSearch(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                      BigInteger[] point, List<BigInteger[]> basis, ExactRational[][] mu, ExactRational[] norms,
                      PlanningBudget budget, long maximumWork) {
        original = rows;
        this.lower = lower;
        this.upper = upper;
        this.point = point;
        this.basis = basis;
        this.budget = budget;
        this.mu = mu;
        this.norms = norms;
        allowance = Math.min(maximumWork, budget.remainingWork() / 16);
        started = budget.threadWork();
        int n = lower.length, d = basis.size();
        long bytes = 4096L + 2048L * n * d + 256L * (rows.size() + 2L * n) * (d + 1L);
        if (d < 2 || d >= n || d > 48 || n > 128 || rows.size() > 512 || allowance < 2048 || !budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
    }

    private void prepareStep() {
        int n = lower.length, d = basis.size();
        if (orthogonal == null) {
            orthogonal = new ExactRational[d][n];
            dual = new ExactRational[d][n];
            low = new BigInteger[d];
            high = new BigInteger[d];
        }
        // Reconstruct B* from the updated LLL factorization. Its cached vectors
        // can be stale after size reductions/swaps, while mu and norms are exact.
        if (phase == 0) {
            int j = row, i = coordinate;
            check();
            var value = ExactRational.of(basis.get(j)[i]);
            for (int k = 0; k < j; k++) {
                check();
                value = subtract(value, multiply(mu[j][k], orthogonal[k][i]));
            }
            orthogonal[j][i] = value;
            checkpoints++;
            if (++coordinate == n) {
                coordinate = 0;
                if (++row == d) { phase = 1; row = d - 1; }
            }
            return;
        }
        if (phase == 1) {
            int j = row, i = coordinate;
            check();
            if (upper[i] == null) throw new Stop();
            rationalCost(orthogonal[j][i], norms[j]);
            var coefficient = orthogonal[j][i].divide(norms[j]);
            for (int k = j + 1; k < d; k++) {
                check();
                coefficient = subtract(coefficient, multiply(mu[k][j], dual[k][i]));
            }
            dual[j][i] = coefficient;
            var first = ExactRational.of(lower[i].subtract(point[i]));
            var last = ExactRational.of(upper[i].subtract(point[i]));
            minimum = add(minimum, multiply(coefficient, coefficient.signum() >= 0 ? first : last));
            maximum = add(maximum, multiply(coefficient, coefficient.signum() >= 0 ? last : first));
            checkpoints++;
            if (++coordinate == n) {
                low[j] = minimum.ceil();
                high[j] = maximum.floor();
                if (low[j].compareTo(high[j]) > 0) throw new Stop();
                minimum = maximum = ExactRational.ZERO;
                coordinate = 0;
                if (--row < 0) { phase = 2; row = 0; }
            }
            return;
        }
        // Store each completed projected row before yielding. A resumed search
        // never constructs its dual basis or repeats already projected rows.
        if (row < original.size() + 2 * n) {
            if (row < original.size()) project(original.get(row), projected);
            else {
                int i = (row - original.size()) / 2;
                boolean positive = (row - original.size()) % 2 == 0;
                project(new ExactLinearProgram.Constraint(Map.of(i, positive ? BigInteger.ONE : BigInteger.ONE.negate()),
                        positive ? upper[i] : lower[i].negate()), projected);
            }
            row++;
            checkpoints++;
            return;
        }
        long remaining = allowance - work - (budget.threadWork() - started);
        if (retaining) remaining = Math.max(2048, remaining);
        else if (remaining < 1024) throw new Stop();
        var initial = new BigInteger[d];
        Arrays.fill(initial, BigInteger.ZERO);
        search = new CountJump(projected, low, high, budget, remaining, initial);
        if (retaining) search.retained();
        phase = 3;
        budget.note("count_kernel", "admitted; original=" + n + "; coordinates=" + d + "; rows=" + projected.size());
    }

    private void project(ExactLinearProgram.Constraint row, List<ExactLinearProgram.Constraint> result) {
        var terms = new LinkedHashMap<Integer, BigInteger>();
        BigInteger rhs = row.upper();
        for (var term : row.terms().entrySet()) {
            check();
            integerCost(term.getValue(), point[term.getKey()]);
            rhs = rhs.subtract(term.getValue().multiply(point[term.getKey()]));
        }
        for (int j = 0; j < basis.size(); j++) {
            BigInteger coefficient = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                check();
                integerCost(term.getValue(), basis.get(j)[term.getKey()]);
                coefficient = coefficient.add(term.getValue().multiply(basis.get(j)[term.getKey()]));
            }
            if (coefficient.signum() != 0) terms.put(j, coefficient);
        }
        if (terms.isEmpty()) {
            if (rhs.signum() < 0) throw new Stop();
        } else result.add(new ExactLinearProgram.Constraint(terms, rhs));
    }

    boolean step() {
        if (complete || paused) return true;
        started = budget.threadWork();
        try {
            budget.checkpoint();
            if (work >= allowance) {
                if (retaining) return paused = true;
                return complete = true;
            }
            if (phase < 3) { prepareStep(); return false; }
            if (phase == 3) {
                if (!search.step()) return false;
                if (search.paused()) {
                    // The parent projection may have used part of an earlier
                    // grant before yielding. Keep its remaining work available
                    // when the inner walk subsequently reaches that deadline.
                    long remaining = allowance - work - (budget.threadWork() - started);
                    if (remaining > 0) { search.resume(remaining); return false; }
                    return paused = true;
                }
                values = search.counts();
                if (values == null) return complete = true;
                restored = point.clone();
                coordinate = 0;
                phase = 4;
                return false;
            }
            if (phase == 4) {
                int i = coordinate++;
                for (int j = 0; j < values.length; j++) {
                    check();
                    integerCost(values[j], basis.get(j)[i]);
                    restored[i] = restored[i].add(values[j].multiply(basis.get(j)[i]));
                }
                if (restored[i].compareTo(lower[i]) < 0 || restored[i].compareTo(upper[i]) > 0) return complete = true;
                if (coordinate == restored.length) { phase = 5; row = 0; }
                return false;
            }
            if (row < original.size()) {
                var constraint = original.get(row++);
                BigInteger sum = BigInteger.ZERO;
                for (var term : constraint.terms().entrySet()) {
                    check();
                    integerCost(term.getValue(), restored[term.getKey()]);
                    sum = sum.add(term.getValue().multiply(restored[term.getKey()]));
                }
                if (sum.compareTo(constraint.upper()) > 0) return complete = true;
                return false;
            }
            counts = restored;
            return complete = true;
        } catch (Stop | ExactRational.PrecisionLimit failure) { return complete = true; }
        finally { work += budget.threadWork() - started; }
    }

    private void check() {
        if (!retaining && work + budget.threadWork() - started >= allowance) throw new Stop();
        budget.check();
    }
    private void integerCost(BigInteger a, BigInteger b) {
        budget.operation(PlanningBudget.Operation.INTEGER, Math.max(a.bitLength(), b.bitLength()));
    }
    private void rationalCost(ExactRational a, ExactRational b) {
        budget.operation(PlanningBudget.Operation.RATIONAL, Math.max(Math.max(a.numerator().bitLength(), a.denominator().bitLength()),
                Math.max(b.numerator().bitLength(), b.denominator().bitLength())));
        if (!retaining && work + budget.threadWork() - started >= allowance) throw new Stop();
    }
    private ExactRational add(ExactRational a, ExactRational b) { rationalCost(a, b); return a.add(b); }
    private ExactRational subtract(ExactRational a, ExactRational b) { rationalCost(a, b); return a.subtract(b); }
    private ExactRational multiply(ExactRational a, ExactRational b) { rationalCost(a, b); return a.multiply(b); }
    BigInteger[] counts() { return counts; }
    CountKernelSearch retained() { retaining = true; return this; }
    boolean paused() { return paused; }
    long progress() { return checkpoints + (search == null ? 0 : search.progress()); }
    void resume(long quantum) {
        if (!retaining || !paused || complete) throw new IllegalStateException("Kernel search is not paused");
        allowance = CountContinuation.deadline(work, quantum, budget);
        if (search != null && search.paused()) search.resume(allowance - work);
        paused = false;
    }
    @Override public void close() {
        if (search != null) search.close();
        search = null;
        orthogonal = dual = null;
        projected.clear();
        budget.release(memory);
        memory = 0;
        complete = true;
        paused = false;
    }
}
