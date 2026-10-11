// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/**
 * Fraction-free Gram factors for a retained integer basis. D[j] is the leading
 * j-row Gram determinant; L[i][j] = mu[i][j] * D[j+1] is integral. Row operations
 * update these minors directly, without normalizing a fraction at every cell.
 * The caller owns basis mutations, reservation and cancellation/work charging.
 */
final class CountIntegralGram {
    private final List<BigInteger[]> basis;
    private final Runnable charge;
    private final BigInteger[][] scaled;
    private final BigInteger[] determinants;
    private final int[][] support;
    private int row, column, indexed;
    private boolean dependent;

    CountIntegralGram(List<BigInteger[]> basis, Runnable charge) {
        this.basis = basis;
        this.charge = charge;
        scaled = new BigInteger[basis.size()][basis.size()];
        determinants = new BigInteger[basis.size() + 1];
        determinants[0] = BigInteger.ONE;
        support = new int[basis.size()][];
    }

    /** Retain each completed projection; the basis must stay frozen until done. */
    boolean step() {
        if (dependent || row == basis.size()) return true;
        if (indexed < basis.size()) {
            var vector = basis.get(indexed);
            int[] indices = new int[vector.length];
            int size = 0;
            for (int i = 0; i < vector.length; i++) {
                charge.run();
                if (vector[i].signum() != 0) indices[size++] = i;
            }
            support[indexed++] = Arrays.copyOf(indices, size);
            return false;
        }
        if (column == 0) Arrays.fill(scaled[row], BigInteger.ZERO);
        BigInteger value = BigInteger.ZERO;
        var left = basis.get(row);
        var right = basis.get(column);
        for (int i : support[row].length <= support[column].length ? support[row] : support[column]) {
            charge.run();
            value = value.add(left[i].multiply(right[i]));
        }
        // Fraction-free elimination. Even a zero projection must rescale the
        // accumulated minor, so this loop cannot skip the old sparse factors.
        for (int i = 0; i < column; i++) {
            charge.run();
            value = quotient(determinants[i + 1].multiply(value)
                    .subtract(scaled[row][i].multiply(scaled[column][i])), determinants[i]);
        }
        value = bounded(value);
        if (column < row) scaled[row][column++] = value;
        else {
            if (value.signum() <= 0) return dependent = true;
            determinants[++row] = value;
            column = 0;
        }
        return row == basis.size();
    }

    boolean dependent() { return dependent; }
    int completedRows() { return row; }
    ExactRational mu(int i, int j) { return new ExactRational(scaled[i][j], determinants[j + 1]); }
    ExactRational norm(int i) { return new ExactRational(determinants[i + 1], determinants[i]); }

    boolean needsReduction(int k, int j) {
        return scaled[k][j].abs().shiftLeft(1).compareTo(determinants[j + 1]) > 0;
    }

    /** Nearest integer with ties toward positive infinity, including negative mu. */
    BigInteger nearest(int k, int j) {
        BigInteger divisor = determinants[j + 1];
        BigInteger[] parts = scaled[k][j].divideAndRemainder(divisor);
        int side = parts[1].signum();
        int half = parts[1].abs().shiftLeft(1).compareTo(divisor);
        if (side > 0 && half >= 0) return parts[0].add(BigInteger.ONE);
        if (side < 0 && half > 0) return parts[0].subtract(BigInteger.ONE);
        return parts[0];
    }

    /** Check norm[k] >= (numerator/denominator - mu^2) * norm[k-1]. */
    boolean lovasz(int k, int numerator, int denominator) {
        BigInteger m = scaled[k][k - 1], d = determinants[k];
        return determinants[k - 1].multiply(determinants[k + 1]).add(m.multiply(m))
                .multiply(BigInteger.valueOf(denominator))
                .compareTo(d.multiply(d).multiply(BigInteger.valueOf(numerator))) >= 0;
    }

    /** Swap adjacent basis rows k-1 and k. The caller swaps the integer vectors. */
    void swap(int k) {
        charge.run();
        BigInteger a = determinants[k - 1], b = determinants[k], c = determinants[k + 1];
        BigInteger m = scaled[k][k - 1];
        determinants[k] = bounded(quotient(a.multiply(c).add(m.multiply(m)), b));
        for (int i = 0; i < k - 1; i++) {
            charge.run();
            var previous = scaled[k][i]; scaled[k][i] = scaled[k - 1][i]; scaled[k - 1][i] = previous;
        }
        for (int i = k + 1; i < basis.size(); i++) {
            charge.run();
            BigInteger left = scaled[i][k - 1], right = scaled[i][k];
            scaled[i][k - 1] = bounded(quotient(a.multiply(right).add(m.multiply(left)), b));
            scaled[i][k] = bounded(quotient(c.multiply(left).subtract(m.multiply(right)), b));
        }
    }

    /** Add q times earlier row j to row k. Orthogonal vectors stay unchanged. */
    void add(int k, int j, BigInteger q) {
        for (int i = 0; i < j; i++) {
            charge.run();
            scaled[k][i] = bounded(scaled[k][i].add(q.multiply(scaled[j][i])));
        }
        scaled[k][j] = bounded(scaled[k][j].add(q.multiply(determinants[j + 1])));
    }

    void negate(int k) {
        for (int i = 0; i < k; i++) { charge.run(); scaled[k][i] = scaled[k][i].negate(); }
        for (int i = k + 1; i < basis.size(); i++) { charge.run(); scaled[i][k] = scaled[i][k].negate(); }
    }

    private static BigInteger bounded(BigInteger value) {
        if (value.bitLength() > 2048) throw new ExactRational.PrecisionLimit();
        return value;
    }

    private static BigInteger quotient(BigInteger value, BigInteger divisor) {
        if (divisor.equals(BigInteger.ONE)) return value;
        BigInteger[] parts = value.divideAndRemainder(divisor);
        // Truncation here could corrupt every subsequent projection. This is
        // an invariant check, never an approximation or a negative certificate.
        if (parts[1].signum() != 0) throw new ArithmeticException("Nonintegral Gram minor");
        return parts[0];
    }
}
