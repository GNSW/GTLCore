// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/** Exact Gram factors from integer inner products; the caller owns the frozen basis and workspace. */
final class CountGramSchmidt {
    private final List<BigInteger[]> basis;
    private final Runnable charge;
    private final ExactRational[][] mu;
    private final ExactRational[] norms;
    private final int[][] support, factorSupport;
    private final int[] factorSize;
    private int row, column, indexed;
    private boolean dependent;

    CountGramSchmidt(List<BigInteger[]> basis, Runnable charge) {
        this.basis = basis;
        this.charge = charge;
        mu = new ExactRational[basis.size()][basis.size()];
        norms = new ExactRational[basis.size()];
        support = new int[basis.size()][];
        factorSupport = new int[basis.size()][basis.size()];
        factorSize = new int[basis.size()];
    }

    /** Each completed projection is retained; local scheduling cannot restart a row. */
    boolean step() {
        if (dependent || row == basis.size()) return true;
        // Charge and retain one support scan at a time. Sparse exact bases can
        // contain many untouched unit columns between dense eliminated ones.
        if (indexed < basis.size()) {
            BigInteger[] vector = basis.get(indexed);
            int[] indices = new int[vector.length];
            int size = 0;
            for (int i = 0; i < vector.length; i++) {
                charge.run();
                if (vector[i].signum() != 0) indices[size++] = i;
            }
            support[indexed++] = Arrays.copyOf(indices, size);
            return false;
        }
        if (column == 0) Arrays.fill(mu[row], ExactRational.ZERO);
        BigInteger product = BigInteger.ZERO;
        BigInteger[] left = basis.get(row), right = basis.get(column);
        for (int i : support[row].length <= support[column].length ? support[row] : support[column]) {
            charge.run();
            product = product.add(left[i].multiply(right[i]));
        }
        ExactRational value = ExactRational.of(product);
        // G = L D L^T. Integer dot products avoid constructing and repeatedly
        // updating rational vectors in the larger ambient coordinate space.
        // Both supports contain only completed factors before this column.
        // Visiting the shorter support includes every possible nonzero product,
        // even when cancellation made a previously dense projection exactly zero.
        int[] common = factorSize[row] <= factorSize[column] ? factorSupport[row] : factorSupport[column];
        int count = Math.min(factorSize[row], factorSize[column]);
        for (int at = 0; at < count; at++) {
            int i = common[at];
            charge.run();
            value = value.subtract(mu[row][i].multiply(mu[column][i]).multiply(norms[i]));
        }
        if (column < row) {
            mu[row][column] = value.divide(norms[column]);
            if (mu[row][column].signum() != 0) factorSupport[row][factorSize[row]++] = column;
            column++;
        } else {
            if (value.signum() <= 0) return dependent = true;
            norms[row++] = value;
            column = 0;
        }
        return row == basis.size();
    }

    boolean dependent() { return dependent; }
    int completedRows() { return row; }
    ExactRational[][] mu() { return mu; }
    ExactRational[] norms() { return norms; }
}
