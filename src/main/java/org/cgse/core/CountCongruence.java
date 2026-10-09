// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Necessary integer equalities: a checked divisibility contradiction only. */
final class CountCongruence implements AutoCloseable {

    private final PlanningBudget budget;
    private final int variables;
    private final long allowance;
    private final List<ExactLinearProgram.Constraint> equations = new ArrayList<>();
    private final List<CountProof.Row> axioms = new ArrayList<>();
    private BigInteger[][] matrix, explanations;
    private int pivot;
    private long work, memory;
    private boolean complete, infeasible, positioned;

    CountCongruence(List<ExactLinearProgram.Constraint> rows, int variables, PlanningBudget budget) {
        this.budget = budget;
        this.variables = variables;
        allowance = Math.min(262_144, budget.remainingWork() / 32);
        if (variables > 192 || rows.size() > 1024 || allowance < 1024) {
            complete = true;
            return;
        }
        Map<Map<Integer, BigInteger>, BigInteger> known = new HashMap<>();
        for (var candidate : rows) known.merge(candidate.terms(), candidate.upper(), BigInteger::min);
        Set<Map<Integer, BigInteger>> used = new HashSet<>();
        for (var candidate : rows) {
            charge();
            if (candidate.terms().size() < 2 || used.contains(candidate.terms())) continue;
            Map<Integer, BigInteger> opposite = new HashMap<>();
            candidate.terms().forEach((id, value) -> opposite.put(id, value.negate()));
            if (!candidate.upper().negate().equals(known.get(opposite))) continue;
            used.add(candidate.terms());
            used.add(opposite);
            equations.add(candidate);
            axioms.add(new CountProof.Row(candidate.terms(), candidate.upper()));
            axioms.add(new CountProof.Row(opposite, candidate.upper().negate()));
        }
        if (equations.size() < 2 || equations.size() > 192) {
            complete = true;
            return;
        }
        long bytes = 2048L + 256L * (variables + 1L) * (variables + equations.size());
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        try {
            matrix = new BigInteger[equations.size()][variables + 1];
            explanations = new BigInteger[equations.size()][equations.size()];
            for (int i = 0; i < equations.size(); i++) {
                Arrays.fill(matrix[i], BigInteger.ZERO);
                Arrays.fill(explanations[i], BigInteger.ZERO);
                var equation = equations.get(i);
                for (var term : equation.terms().entrySet()) {
                    charge();
                    matrix[i][term.getKey()] = term.getValue();
                }
                matrix[i][variables] = equation.upper();
                explanations[i][i] = BigInteger.ONE;
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    boolean step() {
        if (complete) return true;
        if (work >= allowance || pivot == equations.size()) {
            complete = true;
            return true;
        }
        if (!positioned) {
            int r = -1, c = -1;
            search:
            for (int i = pivot; i < matrix.length; i++) for (int j = pivot; j < variables; j++) {
                charge();
                if (matrix[i][j].signum() != 0 && (r < 0 || matrix[i][j].abs().compareTo(matrix[r][c].abs()) < 0)) {
                    r = i;
                    c = j;
                    if (matrix[i][j].abs().equals(BigInteger.ONE)) break search;
                }
            }
            if (r < 0) {
                for (int i = pivot; i < matrix.length; i++) if (matrix[i][variables].signum() != 0) return reject(i);
                complete = true;
                return true;
            }
            swapRows(pivot, r);
            swapColumns(pivot, c);
            positioned = true;
        }
        // Integer row/column Euclidean operations, rather than rational
        // elimination: divisibility information must survive free variables.
        for (int i = pivot + 1; i < matrix.length; i++) {
            charge();
            if (matrix[i][pivot].signum() == 0) continue;
            BigInteger quotient = matrix[i][pivot].divide(matrix[pivot][pivot]);
            for (int j = pivot; j <= variables; j++) {
                charge();
                matrix[i][j] = matrix[i][j].subtract(quotient.multiply(matrix[pivot][j]));
                complete |= oversized(matrix[i][j]);
            }
            for (int j = 0; j < equations.size(); j++) {
                charge();
                explanations[i][j] = explanations[i][j].subtract(quotient.multiply(explanations[pivot][j]));
                complete |= oversized(explanations[i][j]);
            }
            if (matrix[i][pivot].signum() != 0) swapRows(i, pivot);
            return complete;
        }
        for (int j = pivot + 1; j < variables; j++) {
            charge();
            if (matrix[pivot][j].signum() == 0) continue;
            BigInteger quotient = matrix[pivot][j].divide(matrix[pivot][pivot]);
            for (int i = 0; i < matrix.length; i++) {
                charge();
                matrix[i][j] = matrix[i][j].subtract(quotient.multiply(matrix[i][pivot]));
                complete |= oversized(matrix[i][j]);
            }
            if (matrix[pivot][j].signum() != 0) swapColumns(j, pivot);
            return complete;
        }
        if (matrix[pivot][variables].remainder(matrix[pivot][pivot]).signum() != 0) return reject(pivot);
        pivot++;
        positioned = false;
        return false;
    }

    private boolean reject(int id) {
        List<BigInteger> weights = new ArrayList<>();
        for (var value : explanations[id]) {
            weights.add(value);
            weights.add(BigInteger.ZERO);
        }
        var proof = new CountProof.Divisibility("compiled_integer_equalities", variables, axioms, weights);
        // Column operations preserve a row's coefficient GCD. Reconstruct its
        // signed combination in the ORIGINAL coordinates for independent proof.
        infeasible = CountProof.verify(proof, allowance) == CountProof.Verdict.VERIFIED;
        for (var axiom : axioms) for (int ignored : axiom.terms().keySet()) charge();
        if (infeasible) {
            if (budget.proofJournal() != null) budget.proofJournal().add(proof);
            budget.note("count_congruence", "verified_infeasible; equalities=" + equations.size() + "; work=" + work);
        }
        complete = true;
        return true;
    }

    private void swapRows(int a, int b) {
        var tmp = matrix[a];
        matrix[a] = matrix[b];
        matrix[b] = tmp;
        tmp = explanations[a];
        explanations[a] = explanations[b];
        explanations[b] = tmp;
    }

    private void swapColumns(int a, int b) {
        for (var row : matrix) {
            charge();
            var tmp = row[a];
            row[a] = row[b];
            row[b] = tmp;
        }
    }

    private static boolean oversized(BigInteger value) {
        return value.bitLength() > 512;
    }

    private void charge() {
        budget.check();
        work++;
    }

    boolean infeasible() {
        return infeasible;
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
