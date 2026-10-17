// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Necessary integer equalities: sparse unimodular elimination, independently checked. */
final class CountCongruence implements AutoCloseable {

    private static final int MAX_COLUMNS = 512, MAX_EQUATIONS = 256, MAX_BITS = 512;
    private static final long ENTRY_BYTES = 128;
    private final PlanningBudget budget;
    private final int variables;
    private final long allowance;
    private final List<ExactLinearProgram.Constraint> equations = new ArrayList<>();
    private final List<CountProof.Row> axioms = new ArrayList<>();
    private BigInteger[][] matrix, explanations;
    private BitSet[] rowColumns, columnRows, rowWeights;
    private int pivot, columns;
    private long workTicks, memory;
    private boolean complete, infeasible, positioned;

    CountCongruence(List<ExactLinearProgram.Constraint> rows, int variables, PlanningBudget budget) {
        this.budget = budget;
        this.variables = variables;
        allowance = Math.min(262_144, budget.remainingWork() / 32);
        if (variables > 16384 || rows.size() > 1024 || allowance < 1024) {
            complete = true;
            return;
        }
        try {
            long terms = 0;
            for (var row : rows) terms += row.terms().size();
            // Pairing and original-coordinate proof rows are accounted before
            // they are copied. Dense reference grids contain shared ZERO values;
            // only live nonzero values receive the additional magnitude charge.
            reserve(4096L + 192L * rows.size() + 192L * terms);
            Map<Map<Integer, BigInteger>, BigInteger> known = new HashMap<>();
            for (var row : rows) {
                scan();
                known.merge(row.terms(), row.upper(), BigInteger::min);
            }
            Set<Map<Integer, BigInteger>> used = new HashSet<>();
            var active = new TreeSet<Integer>();
            for (var row : rows) {
                scan();
                if (row.terms().size() < 2 || used.contains(row.terms())) continue;
                var opposite = new TreeMap<Integer, BigInteger>();
                for (var term : row.terms().entrySet()) {
                    scan();
                    if (term.getKey() < 0 || term.getKey() >= variables || oversized(term.getValue())) throw new Stop();
                    opposite.put(term.getKey(), term.getValue().negate());
                }
                if (!row.upper().negate().equals(known.get(opposite))) continue;
                if (oversized(row.upper())) throw new Stop();
                used.add(row.terms());
                used.add(opposite);
                equations.add(row);
                axioms.add(CountProof.row(row));
                axioms.add(new CountProof.Row(opposite, row.upper().negate()));
                active.addAll(row.terms().keySet());
                if (equations.size() > MAX_EQUATIONS || active.size() > MAX_COLUMNS) throw new Stop();
            }
            if (equations.size() < 2) throw new Stop();
            columns = active.size();
            int size = equations.size();
            reserve(4096L + 16L * size * (columns + size + 1L) + 192L * (columns + size));
            matrix = new BigInteger[size][columns + 1];
            explanations = new BigInteger[size][size];
            rowColumns = bits(size);
            columnRows = bits(columns);
            rowWeights = bits(size);
            var ids = new HashMap<Integer, Integer>();
            int next = 0;
            for (int id : active) { scan(); ids.put(id, next++); }
            for (int i = 0; i < size; i++) {
                Arrays.fill(matrix[i], BigInteger.ZERO);
                Arrays.fill(explanations[i], BigInteger.ZERO);
                var row = equations.get(i);
                for (var term : row.terms().entrySet()) {
                    scan();
                    set(i, ids.get(term.getKey()), term.getValue());
                }
                set(i, columns, row.upper());
                setWeight(i, i, BigInteger.ONE);
            }
        } catch (Stop declined) {
            complete = true;
            releaseWorkspace();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private static BitSet[] bits(int size) {
        var result = new BitSet[size];
        for (int i = 0; i < size; i++) result[i] = new BitSet();
        return result;
    }

    boolean step() {
        if (complete) return true;
        budget.checkpoint();
        try {
            if (work() >= allowance || pivot == equations.size()) return complete = true;
            if (!positioned) {
                int r = -1, c = -1;
                search:
                for (int i = pivot; i < matrix.length; i++) {
                    scan();
                    for (int j = rowColumns[i].nextSetBit(pivot); j >= 0; j = rowColumns[i].nextSetBit(j + 1)) {
                        scan();
                        if (r < 0 || matrix[i][j].abs().compareTo(matrix[r][c].abs()) < 0) {
                            r = i;
                            c = j;
                            if (matrix[i][j].abs().equals(BigInteger.ONE)) break search;
                        }
                    }
                }
                if (r < 0) {
                    for (int i = pivot; i < matrix.length; i++) {
                        scan();
                        if (matrix[i][columns].signum() != 0) return reject(i);
                    }
                    return complete = true;
                }
                swapRows(pivot, r);
                swapColumns(pivot, c);
                positioned = true;
            }
            // Only the pivot support changes in a row/column operation. In
            // particular, absent coefficients never allocate a new BigInteger
            // or cause a scan through the whole ambient variable grid.
            int other = columnRows[pivot].nextSetBit(pivot + 1);
            if (other >= 0) {
                BigInteger q = quotient(matrix[other][pivot], matrix[pivot][pivot]);
                if (q.signum() != 0) {
                    for (int j = rowColumns[pivot].nextSetBit(pivot); j >= 0; j = rowColumns[pivot].nextSetBit(j + 1))
                        set(other, j, subtract(matrix[other][j], q, matrix[pivot][j]));
                    set(other, columns, subtract(matrix[other][columns], q, matrix[pivot][columns]));
                    for (int j = rowWeights[pivot].nextSetBit(0); j >= 0; j = rowWeights[pivot].nextSetBit(j + 1))
                        setWeight(other, j, subtract(explanations[other][j], q, explanations[pivot][j]));
                }
                if (matrix[other][pivot].signum() != 0) swapRows(other, pivot);
                return false;
            }
            int column = rowColumns[pivot].nextSetBit(pivot + 1);
            if (column >= 0) {
                BigInteger q = quotient(matrix[pivot][column], matrix[pivot][pivot]);
                if (q.signum() != 0)
                    for (int i = columnRows[pivot].nextSetBit(0); i >= 0; i = columnRows[pivot].nextSetBit(i + 1))
                        set(i, column, subtract(matrix[i][column], q, matrix[i][pivot]));
                if (matrix[pivot][column].signum() != 0) swapColumns(column, pivot);
                return false;
            }
            integer(Math.max(matrix[pivot][columns].bitLength(), matrix[pivot][pivot].bitLength()));
            if (matrix[pivot][columns].remainder(matrix[pivot][pivot]).signum() != 0) return reject(pivot);
            pivot++;
            positioned = false;
            return false;
        } catch (Stop declined) {
            // A local work, magnitude or memory refusal is never a negative.
            return complete = true;
        }
    }

    private BigInteger quotient(BigInteger numerator, BigInteger denominator) {
        integer(Math.max(numerator.bitLength(), denominator.bitLength()));
        return numerator.divide(denominator);
    }

    private BigInteger subtract(BigInteger value, BigInteger q, BigInteger source) {
        scan();
        if (source.signum() == 0) return value;
        integer(q.bitLength() + source.bitLength());
        integer(Math.max(value.bitLength(), q.bitLength() + source.bitLength()) + 1);
        var result = value.subtract(q.multiply(source));
        if (oversized(result)) throw new Stop();
        return result;
    }

    private boolean reject(int id) {
        var weights = new ArrayList<BigInteger>();
        for (var value : explanations[id]) {
            scan();
            weights.add(value);
            weights.add(BigInteger.ZERO);
        }
        var proof = new CountProof.Divisibility("compiled_integer_equalities", variables, axioms, weights);
        // Unimodular column operations preserve coefficient GCDs. Rebuild the
        // signed combination of paired ORIGINAL rows; no transformed matrix or
        // numerical status is trusted by this independent checker.
        long remaining = Math.max(0, allowance - work());
        if (remaining > 0) infeasible = CountProof.verify(proof, remaining, used -> {
            budget.charge(used);
            workTicks += used * PlanningBudget.WORK_SCALE;
        }) == CountProof.Verdict.VERIFIED;
        if (infeasible) {
            if (budget.proofJournal() != null) budget.proofJournal().add(proof);
            budget.note("count_congruence", "verified_infeasible; equalities=" + equations.size() +
                    "; support=" + columns + "; work=" + work());
        }
        return complete = true;
    }

    private void set(int row, int column, BigInteger value) {
        boolean present = matrix[row][column].signum() != 0, next = value.signum() != 0;
        accountEntry(present, next);
        matrix[row][column] = next ? value : BigInteger.ZERO;
        if (column < columns && present != next) {
            rowColumns[row].set(column, next);
            columnRows[column].set(row, next);
        }
    }

    private void setWeight(int row, int column, BigInteger value) {
        accountEntry(explanations[row][column].signum() != 0, value.signum() != 0);
        explanations[row][column] = value.signum() != 0 ? value : BigInteger.ZERO;
        rowWeights[row].set(column, value.signum() != 0);
    }

    private void accountEntry(boolean present, boolean next) {
        if (!present && next) reserve(ENTRY_BYTES);
        else if (present && !next) { budget.release(ENTRY_BYTES); memory -= ENTRY_BYTES; }
    }

    private void swapRows(int a, int b) {
        if (a == b) return;
        var touched = (BitSet) rowColumns[a].clone(); touched.or(rowColumns[b]);
        for (int j = touched.nextSetBit(0); j >= 0; j = touched.nextSetBit(j + 1)) {
            scan();
            columnRows[j].set(a, rowColumns[b].get(j));
            columnRows[j].set(b, rowColumns[a].get(j));
        }
        var row = matrix[a]; matrix[a] = matrix[b]; matrix[b] = row;
        row = explanations[a]; explanations[a] = explanations[b]; explanations[b] = row;
        var support = rowColumns[a]; rowColumns[a] = rowColumns[b]; rowColumns[b] = support;
        support = rowWeights[a]; rowWeights[a] = rowWeights[b]; rowWeights[b] = support;
    }

    private void swapColumns(int a, int b) {
        if (a == b) return;
        var touched = (BitSet) columnRows[a].clone(); touched.or(columnRows[b]);
        for (int i = touched.nextSetBit(0); i >= 0; i = touched.nextSetBit(i + 1)) {
            scan();
            var value = matrix[i][a]; matrix[i][a] = matrix[i][b]; matrix[i][b] = value;
            rowColumns[i].set(a, matrix[i][a].signum() != 0);
            rowColumns[i].set(b, matrix[i][b].signum() != 0);
        }
        var support = columnRows[a]; columnRows[a] = columnRows[b]; columnRows[b] = support;
    }

    private static boolean oversized(BigInteger value) { return value.bitLength() > MAX_BITS; }
    private long work() { return PlanningBudget.units(workTicks); }
    private void scan() {
        workTicks += budget.operation(PlanningBudget.Operation.SCAN, 0);
        if (work() >= allowance) throw new Stop();
    }
    private void integer(int bits) {
        workTicks += budget.operation(PlanningBudget.Operation.INTEGER, bits);
        if (work() >= allowance) throw new Stop();
    }
    private void reserve(long bytes) {
        if (!budget.tryReserve(bytes)) throw new Stop();
        memory += bytes;
    }
    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }

    boolean infeasible() { return infeasible; }
    private void releaseWorkspace() {
        matrix = explanations = null;
        rowColumns = columnRows = rowWeights = null;
        axioms.clear(); equations.clear();
        budget.release(memory);
        memory = 0;
    }
    @Override public void close() { releaseWorkspace(); }
}
