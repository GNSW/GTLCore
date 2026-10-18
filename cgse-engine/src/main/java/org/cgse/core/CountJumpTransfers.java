// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;

/** Candidate directions preserving a short equal-coefficient allocation row.
 * These are search moves, not variable identifications or learned constraints. */
final class CountJumpTransfers implements AutoCloseable {
    private final PlanningBudget budget;
    private final long[] pairs;
    private long memory;

    static CountJumpTransfers create(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                                     BigInteger[] upper, PlanningBudget budget) {
        if (lower.length > 1024 || rows.size() > 4096) return null;
        int possible = 0;
        boolean general = false;
        for (var row : rows) {
            budget.check();
            if (!group(row, lower, upper, budget)) continue;
            int n = row.terms().size();
            possible += n * (n - 1) / 2;
            if (possible > 4096) return null;
            for (int id : row.terms().keySet()) {
                budget.check();
                general |= upper[id].subtract(lower[id]).compareTo(BigInteger.ONE) > 0;
            }
        }
        if (!general || possible == 0) return null;
        long bytes = 1024L + 128L * possible;
        if (!budget.tryReserve(bytes)) return null;
        try {
            var unique = new LinkedHashSet<Long>();
            for (var row : rows) {
                budget.check();
                if (!group(row, lower, upper, budget)) continue;
                for (int first : row.terms().keySet()) for (int second : row.terms().keySet()) {
                    budget.check();
                    if (first < second && !lower[first].equals(upper[first]) && !lower[second].equals(upper[second]))
                        unique.add(((long) first << 32) | second);
                }
            }
            if (unique.isEmpty()) return null;
            long[] pairs = new long[unique.size()]; int index = 0;
            for (long pair : unique) { budget.check(); pairs[index++] = pair; }
            long retained = 128L + 8L * pairs.length;
            var result = new CountJumpTransfers(budget, pairs, retained);
            bytes -= retained;
            return result;
        } finally { budget.release(bytes); }
    }

    private static boolean group(ExactLinearProgram.Constraint row, BigInteger[] lower,
                                 BigInteger[] upper, PlanningBudget budget) {
        if (row.terms().size() < 2 || row.terms().size() > 8) return false;
        BigInteger coefficient = null; int free = 0;
        for (var term : row.terms().entrySet()) {
            budget.check(); int id = term.getKey();
            if (term.getValue().signum() <= 0 || upper[id] == null) return false;
            if (coefficient == null) coefficient = term.getValue();
            else if (!coefficient.equals(term.getValue())) return false;
            if (!lower[id].equals(upper[id])) free++;
        }
        return free >= 2;
    }

    private CountJumpTransfers(PlanningBudget budget, long[] pairs, long bytes) {
        this.budget = budget; this.pairs = pairs; memory = bytes;
    }
    int size() { return pairs.length; }
    int first(int index) { return (int) (pairs[index] >>> 32); }
    int second(int index) { return (int) pairs[index]; }
    @Override public void close() { budget.release(memory); memory = 0; }
}
