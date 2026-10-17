// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;

/**
 * Exact PotLLL insertion choice over frozen Gram factors. This optional scan
 * changes neither the basis nor the feasible region and never proves anything
 * about the count model. One projected prefix is retained per step.
 */
final class CountLatticePotential implements AutoCloseable {
    private static final int MAX_BITS = 16_384;
    private static final int FRACTION_BITS = 128;
    private static final BigInteger UNIT = BigInteger.ONE.shiftLeft(FRACTION_BITS);
    private static final long SCRATCH_BYTES = 65_536;
    private final ExactRational[] coefficients, norms;
    private final PlanningBudget budget;
    private ExactRational projected;
    private BigInteger numerator = BigInteger.ONE, denominator = BigInteger.ONE;
    private BigInteger bestNumerator = BigInteger.ONE, bestDenominator = BigInteger.ONE;
    private int next, best = -1;
    private boolean complete, declined;
    private final int pivot;
    private final Workspace workspace;
    private final boolean ownsWorkspace;
    private BigInteger projectedLow, projectedHigh, productLow = UNIT, productHigh = UNIT;
    private BigInteger bestLow = UNIT, bestHigh = UNIT;
    private boolean exact;

    CountLatticePotential(ExactRational[][] mu, ExactRational[] norms, int pivot, PlanningBudget budget) {
        this(mu, norms, pivot, budget, new Workspace(norms, budget), true);
    }

    CountLatticePotential(ExactRational[][] mu, ExactRational[] norms, int pivot, PlanningBudget budget, Workspace workspace) {
        this(mu, norms, pivot, budget, workspace, false);
    }

    private CountLatticePotential(ExactRational[][] mu, ExactRational[] norms, int pivot, PlanningBudget budget,
                                  Workspace workspace, boolean ownsWorkspace) {
        this.coefficients = mu[pivot];
        this.norms = norms;
        this.projected = norms[pivot];
        this.next = pivot - 1;
        this.pivot = pivot;
        this.budget = budget;
        this.workspace = workspace;
        this.ownsWorkspace = ownsWorkspace;
        if (workspace.norms != norms || workspace.budget != budget) throw new IllegalArgumentException("Different lattice scope");
        if (workspace.memory == 0) complete = declined = true;
    }

    boolean step() {
        if (complete) return true;
        budget.checkpoint();
        if (!exact) return boundedStep();
        return exactStep();
    }

    private boolean exactStep() {
        try {
            if (next < 0) {
                integer(Math.max(bestNumerator.bitLength(), bestDenominator.bitLength()) + 2, 3);
                if (bestNumerator.shiftLeft(2).compareTo(bestDenominator.multiply(BigInteger.valueOf(3))) >= 0) best = -1;
                return complete = true;
            }
            int j = next;
            int bits = Math.max(width(projected), Math.max(width(coefficients[j]), width(norms[j])));
            for (int i = 0; i < 4; i++) budget.operation(PlanningBudget.Operation.RATIONAL, bits);
            projected = projected.add(coefficients[j].multiply(coefficients[j]).multiply(norms[j]));
            var ratio = projected.divide(norms[j]);
            int nBits = numerator.bitLength() + ratio.numerator().bitLength();
            int dBits = denominator.bitLength() + ratio.denominator().bitLength();
            if (Math.max(nBits, dBits) > MAX_BITS) return complete = declined = true;
            integer(nBits, 1);
            var n = numerator.multiply(ratio.numerator());
            integer(dBits, 1);
            var d = denominator.multiply(ratio.denominator());
            integer(Math.max(nBits, dBits), 3);
            var common = n.gcd(d);
            numerator = n.divide(common);
            denominator = d.divide(common);
            integer(Math.max(numerator.bitLength() + bestDenominator.bitLength(),
                    bestNumerator.bitLength() + denominator.bitLength()), 3);
            if (numerator.multiply(bestDenominator).compareTo(bestNumerator.multiply(denominator)) < 0) {
                bestNumerator = numerator;
                bestDenominator = denominator;
                best = j;
            }
            next--;
            return false;
        } catch (ExactRational.PrecisionLimit limit) {
            // No basis mutation occurred: the caller may keep its ordinary
            // nearest-plane/kernel path when this optional comparison refuses.
            return complete = declined = true;
        }
    }

    private boolean boundedStep() {
        // Integer outward rounding encloses every potential ratio. Disjoint
        // intervals certify the exact ordering; overlap restarts the bounded
        // exact scan. Approximation alone can never authorize an insertion.
        if (!workspace.prepare()) return false;
        if (projectedLow == null) {
            if (!workspace.bounds(pivot)) return useExact();
            projectedLow = workspace.low[pivot]; projectedHigh = workspace.high[pivot];
        }
        if (next < 0) {
            integer(Math.max(bestLow.bitLength(), bestHigh.bitLength()) + 2, 2);
            var threshold = UNIT.multiply(BigInteger.valueOf(3));
            if (bestHigh.shiftLeft(2).compareTo(threshold) < 0) return complete = true;
            if (bestLow.shiftLeft(2).compareTo(threshold) >= 0) { best = -1; return complete = true; }
            return useExact();
        }
        int j = next;
        if (!workspace.bounds(j)) return useExact();
        var coefficient = coefficients[j];
        var square = scaled(multiply(coefficient.numerator(), coefficient.numerator()),
                multiply(coefficient.denominator(), coefficient.denominator()), FRACTION_BITS);
        integer(Math.max(projectedHigh.bitLength(), FRACTION_BITS) + 1, 2);
        projectedLow = projectedLow.add(down(multiply(square[0], workspace.low[j])));
        projectedHigh = projectedHigh.add(up(multiply(square[1], workspace.high[j])));
        var ratioLow = scaled(projectedLow, workspace.high[j], FRACTION_BITS)[0];
        var ratioHigh = scaled(projectedHigh, workspace.low[j], FRACTION_BITS)[1];
        if (Math.max(productLow.bitLength() + ratioLow.bitLength(), productHigh.bitLength() + ratioHigh.bitLength()) > MAX_BITS)
            return useExact();
        productLow = down(multiply(productLow, ratioLow));
        productHigh = up(multiply(productHigh, ratioHigh));
        integer(Math.max(productHigh.bitLength(), bestHigh.bitLength()), 2);
        if (productHigh.compareTo(bestLow) < 0) {
            bestLow = productLow; bestHigh = productHigh; best = j;
        } else if (productLow.compareTo(bestHigh) < 0) return useExact();
        next--;
        return false;
    }

    private BigInteger multiply(BigInteger a, BigInteger b) {
        integer(a.bitLength() + b.bitLength(), 1);
        return a.multiply(b);
    }

    private BigInteger[] scaled(BigInteger n, BigInteger d, int shift) {
        return scaled(n, d, shift, budget);
    }

    private static BigInteger[] scaled(BigInteger n, BigInteger d, int shift, PlanningBudget budget) {
        integer(Math.max(n.bitLength() + Math.max(0, shift), d.bitLength() + Math.max(0, -shift)), 2, budget);
        if (shift >= 0) n = n.shiftLeft(shift); else d = d.shiftLeft(-shift);
        var value = n.divideAndRemainder(d);
        return new BigInteger[] {value[0], value[1].signum() == 0 ? value[0] : value[0].add(BigInteger.ONE)};
    }

    private BigInteger down(BigInteger value) {
        integer(value.bitLength(), 1);
        return value.shiftRight(FRACTION_BITS);
    }

    private BigInteger up(BigInteger value) {
        integer(value.bitLength(), 1);
        var floor = value.shiftRight(FRACTION_BITS);
        int lowest = value.getLowestSetBit();
        return lowest >= 0 && lowest < FRACTION_BITS ? floor.add(BigInteger.ONE) : floor;
    }

    private boolean useExact() {
        exact = true;
        next = pivot - 1;
        best = -1;
        return false;
    }

    private static int width(ExactRational value) {
        return Math.max(value.numerator().bitLength(), value.denominator().bitLength());
    }

    private void integer(int bits, int operations) {
        integer(bits, operations, budget);
    }

    private static void integer(int bits, int operations, PlanningBudget budget) {
        // PlanningBudget's ordinary arithmetic tier stops at 2048 bits. Split
        // this bounded product into tiers instead of silently discounting its
        // wider accumulator. Temporary cross-products are at most 2*MAX_BITS.
        for (int i = 0; i < operations; i++)
            for (int remaining = Math.max(1, bits); remaining > 0; remaining -= 2048)
                budget.operation(PlanningBudget.Operation.INTEGER, Math.min(remaining, 2048));
    }

    boolean declined() { return declined; }
    int insertion() {
        if (!complete) throw new IllegalStateException("Potential scan is incomplete");
        return declined ? -1 : best;
    }

    @Override
    public void close() {
        if (ownsWorkspace) workspace.close();
        complete = true;
    }

    /** Reuse only bounds of identical immutable norms in this one factor array. */
    static final class Workspace implements AutoCloseable {
        private final ExactRational[] norms;
        private final PlanningBudget budget;
        private ExactRational[] cached;
        private BigInteger[] low, high;
        private int prepared, scale, exponent = Integer.MIN_VALUE;
        private long memory;

        Workspace(ExactRational[] norms, PlanningBudget budget) {
            this.norms = norms;
            this.budget = budget;
            // Covers wide comparison temporaries plus cached outward bounds
            // and retained immutable norm objects, including later rescaling.
            long bytes = SCRATCH_BYTES + 4096L * norms.length;
            if (budget.tryReserve(bytes)) {
                memory = bytes;
                cached = new ExactRational[norms.length];
                low = new BigInteger[norms.length]; high = new BigInteger[norms.length];
            }
        }

        private boolean prepare() {
            if (prepared == norms.length) return true;
            budget.operation(PlanningBudget.Operation.SCAN, 0);
            var norm = norms[prepared++];
            exponent = Math.max(exponent, norm.numerator().bitLength() - norm.denominator().bitLength() + 1);
            if (prepared == norms.length) scale = FRACTION_BITS - exponent;
            return false;
        }

        private boolean bounds(int index) {
            budget.operation(PlanningBudget.Operation.SCAN, 0);
            var norm = norms[index];
            if (cached[index] != norm) {
                var value = scaled(norm.numerator(), norm.denominator(), scale, budget);
                low[index] = value[0]; high[index] = value[1]; cached[index] = norm;
            }
            // A later swap may widen the range. The same scale still encloses
            // its norms; an underflow simply sends this scan to exact arithmetic.
            return low[index].signum() > 0;
        }

        @Override public void close() {
            budget.release(memory);
            memory = 0;
            cached = null; low = high = null;
        }
    }
}
