// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.List;

/** Immutable original-row data reused while a numerical LP session changes bounds. */
final class CountLpStructure implements AutoCloseable {

    final int variables, termCount, objective;
    final int[] offsets, columns;
    final BigInteger[] coefficients, positiveSums;
    private final List<ExactLinearProgram.Constraint> rows;
    private final PlanningBudget budget;
    private long memory;

    private CountLpStructure(int variables, int termCount, int objective, int[] offsets, int[] columns,
                             BigInteger[] coefficients, BigInteger[] positiveSums,
                             List<ExactLinearProgram.Constraint> rows, PlanningBudget budget, long memory) {
        this.variables = variables;
        this.termCount = termCount;
        this.objective = objective;
        this.offsets = offsets;
        this.columns = columns;
        this.coefficients = coefficients;
        this.positiveSums = positiveSums;
        this.rows = rows;
        this.budget = budget;
        this.memory = memory;
    }

    static CountLpStructure create(List<ExactLinearProgram.Constraint> rows, int variables,
                                   PlanningBudget budget, Runnable check) {
        long terms = 0;
        for (var row : rows) {
            check.run();
            terms += row.terms().size();
            if (row.upper().bitLength() > 1024 || terms > (long) rows.size() * variables) return null;
        }
        // Includes the retained source row references, CSR arrays, and exact
        // row sums, not just the primitive array payloads.
        long bytes = 4096L + 256L * terms + 512L * rows.size() + 64L * variables;
        if (bytes > budget.availableBytes() / 8) return null;
        if (!budget.tryReserve(bytes)) return null;
        try {
            var offsets = new int[rows.size() + 1];
            var columns = new int[(int) terms];
            var coefficients = new BigInteger[(int) terms];
            var positiveSums = new BigInteger[rows.size()];
            int cursor = 0, objective = -1;
            for (int r = 0; r < rows.size(); r++) {
                var row = rows.get(r);
                offsets[r] = cursor;
                boolean positive = true;
                BigInteger sum = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) {
                    check.run();
                    int column = term.getKey();
                    var value = term.getValue();
                    if (column < 0 || column >= variables || value.bitLength() > 1024) return null;
                    columns[cursor] = column;
                    coefficients[cursor++] = value;
                    if (value.signum() <= 0) positive = false;
                    else {
                        check.run();
                        budget.operation(PlanningBudget.Operation.INTEGER, Math.max(sum.bitLength(), value.bitLength()));
                        sum = sum.add(value);
                    }
                }
                positiveSums[r] = sum;
                if (positive && row.terms().size() >= variables / 2 &&
                        (objective < 0 || row.terms().size() > rows.get(objective).terms().size()))
                    objective = r;
            }
            offsets[rows.size()] = cursor;
            var result = new CountLpStructure(variables, cursor, objective, offsets, columns, coefficients,
                    positiveSums, List.copyOf(rows), budget, bytes);
            bytes = 0;
            return result;
        } finally {
            budget.release(bytes);
        }
    }

    boolean matches(List<ExactLinearProgram.Constraint> next, int variables, Runnable check) {
        if (this.variables != variables || rows.size() != next.size()) return false;
        // Constraint and its coefficient map are immutable. Identity therefore
        // checks RHS, coefficients, order and row provenance without rescanning
        // every BigInteger on every trail update.
        for (int r = 0; r < rows.size(); r++) {
            check.run();
            if (rows.get(r) != next.get(r)) return false;
        }
        return true;
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
