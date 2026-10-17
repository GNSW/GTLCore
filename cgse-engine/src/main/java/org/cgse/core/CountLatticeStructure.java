// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Request-local row pairing shared by lattice attempts; it contains no search conclusions. */
final class CountLatticeStructure implements AutoCloseable {

    record Equation(Map<Integer, BigInteger> terms, BigInteger upper) {}

    private record Interval(ExactLinearProgram.Constraint row, BigInteger lower) {}

    private final List<ExactLinearProgram.Constraint> rows;
    private final PlanningBudget budget;
    private final List<Equation> equations = new ArrayList<>();
    private final List<Interval> intervals = new ArrayList<>();
    private long memory, magnitudeBytes;

    private CountLatticeStructure(List<ExactLinearProgram.Constraint> rows, PlanningBudget budget) {
        this.rows = rows;
        this.budget = budget;
    }

    static CountLatticeStructure create(List<ExactLinearProgram.Constraint> rows, PlanningBudget budget) {
        long bytes = 1024L + 256L * rows.size();
        long magnitudeBytes = 0;
        for (var row : rows) {
            budget.check();
            long upperBytes = (row.upper().bitLength() + 7L) / 8;
            bytes += 16L + upperBytes;
            magnitudeBytes = Math.max(magnitudeBytes, upperBytes);
            for (var coefficient : row.terms().values()) {
                budget.check();
                bytes += 128L + (coefficient.bitLength() + 7L) / 4;
                magnitudeBytes = Math.max(magnitudeBytes, (coefficient.bitLength() + 7L) / 8);
            }
        }
        if (!budget.tryReserve(bytes)) return null;
        CountLatticeStructure result = new CountLatticeStructure(rows, budget);
        result.memory = bytes;
        result.magnitudeBytes = magnitudeBytes;
        try {
            var known = new HashMap<Map<Integer, BigInteger>, BigInteger>();
            for (var row : rows) {
                budget.check();
                budget.charge(row.terms().size());
                known.merge(row.terms(), row.upper(), BigInteger::min);
            }
            Set<Map<Integer, BigInteger>> included = new HashSet<>();
            for (var row : rows) {
                budget.check();
                budget.charge(row.terms().size());
                if (row.terms().size() < 2 || included.contains(row.terms())) continue;
                Map<Integer, BigInteger> opposite = opposite(row, budget);
                if (!row.upper().negate().equals(known.get(opposite))) continue;
                result.equations.add(new Equation(row.terms(), row.upper()));
                included.add(row.terms());
                included.add(opposite);
            }
            // Match the original ordering: exact equations first, then the
            // first orientation of each remaining interval in input order.
            if (result.equations.size() < 2) for (var row : rows) {
                budget.check();
                budget.charge(row.terms().size());
                if (row.terms().size() < 2 || included.contains(row.terms())) continue;
                Map<Integer, BigInteger> opposite = opposite(row, budget);
                BigInteger reverse = known.get(opposite);
                if (reverse == null || row.upper().compareTo(reverse.negate()) < 0) continue;
                result.intervals.add(new Interval(row, reverse.negate()));
                included.add(row.terms());
                included.add(opposite);
            }
            long retained = 256L + 64L * (result.equations.size() + result.intervals.size());
            for (var interval : result.intervals) {
                budget.check();
                retained += (interval.lower().bitLength() + 7L) / 8;
            }
            budget.release(bytes - retained);
            result.memory = retained;
            return result;
        } catch (RuntimeException | Error failure) {
            result.close();
            throw failure;
        }
    }

    private static Map<Integer, BigInteger> opposite(ExactLinearProgram.Constraint row, PlanningBudget budget) {
        Map<Integer, BigInteger> opposite = new HashMap<>();
        for (var term : row.terms().entrySet()) {
            budget.check();
            opposite.put(term.getKey(), term.getValue().negate());
        }
        return opposite;
    }

    boolean matches(List<ExactLinearProgram.Constraint> candidate, PlanningBudget owner) {
        return memory != 0 && rows == candidate && budget == owner;
    }

    boolean usable() {
        return equations.size() + intervals.size() >= 2;
    }

    long workspaceBytes(BigInteger[] lower, BigInteger[] upper) {
        long magnitude = magnitudeBytes;
        for (int i = 0; i < lower.length; i++) {
            budget.check();
            magnitude = Math.max(magnitude, (Math.max(lower[i].bitLength(), upper[i].bitLength()) + 7L) / 8);
        }
        return 2048L + (512L + 8L * magnitude) * lower.length + (64L + 2L * magnitude) * rows.size();
    }

    List<Equation> equations(ExactRational[] point, int attempt) {
        if (intervals.isEmpty()) return equations;
        List<Equation> result = new ArrayList<>(equations);
        for (var interval : intervals) {
            budget.check();
            var row = interval.row();
            BigInteger low = interval.lower(), high = row.upper(), face;
            if (attempt == 1) face = low.add(high).shiftRight(1);
            else if (attempt == 2) face = high;
            else if (attempt == 3) face = low;
            else {
                ExactRational value = ExactRational.ZERO;
                for (var term : row.terms().entrySet()) {
                    budget.check();
                    value = value.add(point[term.getKey()].multiply(ExactRational.of(term.getValue())));
                }
                face = value.floor().max(low).min(high);
            }
            result.add(new Equation(row.terms(), face));
        }
        return result;
    }

    @Override
    public void close() {
        equations.clear();
        intervals.clear();
        budget.release(memory);
        memory = 0;
    }
}
