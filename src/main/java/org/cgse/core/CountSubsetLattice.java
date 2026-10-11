// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Bounded, positive-only centered subset embedding and projected block search.
 * A failed/limited lattice attempt is not a certificate about the original box.
 * Basis, Gram factors and enumeration state are private to this retained call.
 */
final class CountSubsetLattice implements AutoCloseable {
    private static final ExactRational HALF = new ExactRational(BigInteger.ONE, BigInteger.TWO);
    private static final ExactRational DELTA = new ExactRational(BigInteger.valueOf(99), BigInteger.valueOf(100));
    private final PlanningBudget budget;
    private final long target, limit;
    private long[] coefficients;
    private int[] order;
    private final List<BigInteger[]> basis = new ArrayList<>();
    private CountIntegralGram gram;
    private ExactRational[][] mu;
    private ExactRational[] norms, centers, distances;
    private BigInteger[] choices, offsets, best;
    private int[] directions;
    private ExactRational radius;
    private int n, phase, pivot = 1, reduceColumn, reduceStage, candidateRow;
    private int block, width = 8, tour, changes, end, depth, nodes;
    private boolean entering, done, complementAll;
    private int insertStage, unit, position, adding;
    private long memory, work, progress, candidate = -1, insertions;

    CountSubsetLattice(long[] coefficients, long target, PlanningBudget budget, long maximumWork) {
        this.budget = budget;
        this.target = target;
        limit = Math.min(maximumWork, budget.remainingWork());
        n = coefficients.length;
        long bytes = 8192L + 4096L * (n + 1) * (n + 1);
        if (n < 4 || n > 52 || limit < 1024 || !budget.tryReserve(bytes)) { done = true; return; }
        memory = bytes;
        try {
            this.coefficients = coefficients.clone();
            order = new int[n];
            centers = new ExactRational[n + 1];
            distances = new ExactRational[n + 2];
            choices = new BigInteger[n + 1];
            offsets = new BigInteger[n + 1];
            directions = new int[n + 1];
            mu = new ExactRational[n + 1][n + 1];
            norms = new ExactRational[n + 1];
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    /** Low density is an admission hint, never a completeness or infeasibility rule. */
    static boolean eligible(long[] values, PlanningBudget budget) {
        if (values.length <= 40 || values.length > 52) return false;
        int bits = 0;
        for (long value : values) {
            budget.check();
            if (value == 0 || value == Long.MIN_VALUE) return false;
            bits = Math.max(bits, 64 - Long.numberOfLeadingZeros(Math.abs(value)));
        }
        if (bits < values.length) return false;
        for (int i = 0; i < values.length; i++) for (int j = 0; j < i; j++) {
            budget.check();
            // Repeated weights favor the exact quarter-representative matcher.
            if (Math.abs(values[i]) == Math.abs(values[j])) return false;
        }
        return true;
    }

    boolean step() {
        if (done) return true;
        budget.checkpoint();
        if (work >= limit) return finish();
        try {
            if (phase == 0) prepare();
            else if (phase == 1) {
                int before = gram.completedRows();
                boolean finished = gram.step();
                if (gram.dependent()) return finish();
                progress += gram.completedRows() - before;
                if (finished) phase = 2;
            } else if (phase == 2) reduce();
            else if (phase == 3) inspect();
            else if (phase == 4) enumerate();
            else insert();
        } catch (ExactRational.PrecisionLimit | PrecisionLimit ignored) { return finish(); }
        return done;
    }

    private void prepare() {
        BigInteger adjusted = BigInteger.valueOf(target), mass = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            charge();
            if (coefficients[i] == Long.MIN_VALUE) throw new PrecisionLimit();
            if (coefficients[i] < 0) adjusted = adjusted.subtract(BigInteger.valueOf(coefficients[i]));
            mass = mass.add(BigInteger.valueOf(Math.abs(coefficients[i])));
            int at = i;
            while (at > 0) {
                charge();
                if (Math.abs(coefficients[order[at - 1]]) <= Math.abs(coefficients[i])) break;
                order[at] = order[at - 1]; at--;
            }
            order[at] = i;
        }
        complementAll = adjusted.shiftLeft(1).compareTo(mass) > 0;
        if (complementAll) adjusted = mass.subtract(adjusted);
        // The final marker keeps the rows independent, including a half-total target.
        // A binary solution gives (2*x-1, 0, -1) in the original integer lattice.
        for (int i = 0; i <= n; i++) {
            BigInteger[] row = new BigInteger[n + 2];
            for (int j = 0; j < row.length; j++) { charge(); row[j] = BigInteger.ZERO; }
            if (i < n) {
                row[i] = BigInteger.TWO;
                row[n] = BigInteger.valueOf(Math.abs(coefficients[order[i]])).shiftLeft(4);
            } else {
                Arrays.fill(row, 0, n, BigInteger.ONE);
                row[n] = adjusted.shiftLeft(4);
                row[n + 1] = BigInteger.ONE;
            }
            basis.add(row);
        }
        beginGram();
    }

    private void beginGram() {
        gram = new CountIntegralGram(basis, this::charge);
        pivot = 1; reduceStage = 0; phase = 1;
    }

    private void reduce() {
        if (pivot == basis.size()) { phase = 3; candidateRow = 0; return; }
        int k = pivot;
        if (reduceStage == 0) {
            sizeReduce(k, k - 1); reduceStage = 1; return;
        }
        if (reduceStage == 2) {
            if (reduceColumn >= 0) sizeReduce(k, reduceColumn--);
            else { pivot++; reduceStage = 0; }
            return;
        }
        charge();
        if (gram.lovasz(k, 99, 100)) {
            reduceColumn = k - 2; reduceStage = 2; return;
        }
        swap(k);
        progress++; pivot = Math.max(1, k - 1); reduceStage = 0;
    }

    private void swap(int k) {
        gram.swap(k);
        Collections.swap(basis, k, k - 1);
    }

    private void sizeReduce(int k, int j) {
        charge();
        if (!gram.needsReduction(k, j)) return;
        BigInteger q = gram.nearest(k, j);
        for (int i = 0; i < n + 2; i++) {
            charge();
            basis.get(k)[i] = bounded(basis.get(k)[i].subtract(q.multiply(basis.get(j)[i])));
        }
        gram.add(k, j, q.negate());
        progress++;
    }

    private void inspect() {
        if (candidateRow < basis.size()) {
            BigInteger[] row = basis.get(candidateRow++);
            charge();
            if (row[n].signum() != 0 || !row[n + 1].abs().equals(BigInteger.ONE)) return;
            long mask = 0;
            for (int i = 0; i < n; i++) {
                charge();
                if (!row[i].abs().equals(BigInteger.ONE)) return;
                boolean one = row[i].signum() != row[n + 1].signum();
                if (coefficients[order[i]] < 0) one = !one;
                if (complementAll) one = !one;
                if (one) mask |= 1L << order[i];
            }
            // The original signed equality remains authoritative after every transform.
            BigInteger sum = BigInteger.ZERO;
            for (int i = 0; i < n; i++) {
                charge(); if ((mask & (1L << i)) != 0) sum = sum.add(BigInteger.valueOf(coefficients[i]));
            }
            if (sum.equals(BigInteger.valueOf(target))) { candidate = mask; finish(); }
            return;
        }
        beginBlock();
    }

    private void beginBlock() {
        if (block >= basis.size() - 1) {
            block = 0;
            if (++tour == 2 || changes == 0) { width += 4; tour = 0; }
            changes = 0;
            if (width > 24) { finish(); return; }
        }
        end = Math.min(basis.size(), block + width);
        // Fractions are needed only while enumerating a frozen projected block.
        // Rebuild that small view after every insertion/reduction, never use
        // cached fractions to update the authoritative integer Gram factors.
        for (int i = block; i < end; i++) {
            charge(); norms[i] = gram.norm(i);
            for (int j = block; j < i; j++) { charge(); mu[i][j] = gram.mu(i, j); }
        }
        depth = end - 1; radius = norms[block].multiply(DELTA);
        distances[end] = ExactRational.ZERO;
        Arrays.fill(choices, block, end, BigInteger.ZERO);
        best = null; nodes = 0; entering = true; phase = 4;
    }

    private void enumerate() {
        if (depth == end || nodes >= 200_000) { phase = 5; return; }
        if (entering) {
            ExactRational center = ExactRational.ZERO;
            for (int j = depth + 1; j < end; j++) {
                charge(); center = center.subtract(mu[j][depth].multiply(ExactRational.of(choices[j])));
            }
            centers[depth] = center;
            choices[depth] = center.add(HALF).floor();
            directions[depth] = center.compareTo(ExactRational.of(choices[depth])) >= 0 ? 1 : -1;
            offsets[depth] = BigInteger.ZERO; entering = false;
        }
        charge(); nodes++;
        var difference = ExactRational.of(choices[depth]).subtract(centers[depth]);
        var distance = distances[depth + 1].add(difference.multiply(difference).multiply(norms[depth]));
        if (distance.compareTo(radius) > 0) { depth++; if (depth < end) next(); return; }
        if (depth == block) {
            boolean nonzero = false;
            for (int j = block; j < end; j++) { charge(); nonzero |= choices[j].signum() != 0; }
            if (nonzero) {
                radius = distance;
                best = Arrays.copyOfRange(choices, block, end);
            }
            next(); return;
        }
        boolean nonzero = false;
        for (int j = depth + 1; j < end; j++) { charge(); nonzero |= choices[j].signum() != 0; }
        if (nonzero || choices[depth].signum() >= 0) {
            distances[depth] = distance; depth--; entering = true;
        } else next();
    }

    private void next() {
        offsets[depth] = offsets[depth].add(BigInteger.ONE);
        choices[depth] = bounded(choices[depth].add(offsets[depth].multiply(BigInteger.valueOf(directions[depth]))));
        directions[depth] = -directions[depth];
    }

    private void insert() {
        if (insertStage == 0) {
            unit = -1;
            if (best != null) for (int i = 0; i < best.length; i++) {
                charge(); if (best[i].abs().equals(BigInteger.ONE)) { unit = i; break; }
            }
            if (unit < 0) { block++; beginBlock(); return; }
            position = block + unit; insertStage = 1;
        }
        if (insertStage == 1) {
            // Rotate the unit-coefficient row behind the other block rows.
            // Every adjacent swap updates exact Gram factors in O(d) work.
            if (position < end - 1) { swap(++position); return; }
            if (best[unit].signum() < 0) {
                for (int i = 0; i < n + 2; i++) { charge(); basis.get(position)[i] = basis.get(position)[i].negate(); }
                gram.negate(position);
            }
            adding = 0; insertStage = 2; return;
        }
        if (insertStage == 2) {
            if (adding < best.length) {
                int old = adding++;
                charge();
                if (old == unit || best[old].signum() == 0) return;
                int j = block + old - (old > unit ? 1 : 0);
                BigInteger q = best[old];
                // Adding earlier rows preserves this row's orthogonal vector
                // and all later ones. Only its earlier mu coefficients change.
                // The +/-1 pivot makes each update unimodular; nonunit-only
                // proposals were declined before touching the retained basis.
                for (int i = 0; i < n + 2; i++) {
                    charge(); basis.get(position)[i] = bounded(basis.get(position)[i].add(q.multiply(basis.get(j)[i])));
                }
                gram.add(position, j, q);
                return;
            }
            insertStage = 3;
        }
        if (position > block) { swap(position--); return; }
        changes++; insertions++; progress++;
        // The earlier prefix is still reduced. Continue at the first changed
        // row, without reconstructing Gram factors or repeating prefix LLL.
        pivot = Math.max(1, block++); reduceStage = 0; insertStage = 0; phase = 2;
    }

    private void charge() { budget.check(); work++; }
    private static BigInteger bounded(BigInteger value) {
        if (value.bitLength() > 512) throw new PrecisionLimit();
        return value;
    }
    private boolean finish() {
        done = true;
        budget.note("count_subset_lattice", "witness=" + (candidate >= 0) + "; block_width=" + width +
                "; insertions=" + insertions + "; work=" + work + "; positive_only");
        return true;
    }
    long candidate() { return candidate; }
    long work() { return work; }
    long remainingWork() { return done ? 0 : Math.max(0, limit - work); }
    long progress() { return progress; }
    public void close() {
        done = true; basis.clear(); gram = null; mu = null; norms = centers = distances = null;
        choices = offsets = best = null; directions = order = null; coefficients = null;
        budget.release(memory); memory = 0;
    }
    private static final class PrecisionLimit extends RuntimeException {}
}
