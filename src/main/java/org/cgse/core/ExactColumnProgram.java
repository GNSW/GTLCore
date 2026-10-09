// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Restricted master LP with exact reduced-cost and Farkas pricing over the complete catalog. */
final class ExactColumnProgram implements AutoCloseable {

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] objective;
    private final PlanningBudget budget;
    private final BitSet selected = new BitSet();
    private final long allowance;
    private ExactLinearProgram master;
    private int[] columns, rowIds;
    private ExactRational[] point, dual, certificate;
    private ExactLinearProgram.Result result;
    private long memory, work;
    private int rounds, added;

    ExactColumnProgram(int variables, List<ExactLinearProgram.Constraint> rows, BigInteger[] objective, PlanningBudget budget) {
        this.rows = rows;
        this.objective = objective.clone();
        this.budget = budget;
        allowance = Math.min(262144, budget.remainingWork() / 16);
        long terms = rows.stream().mapToLong(r -> r.terms().size()).sum();
        long bytes = 4096L + 192L * variables + 128L * rows.size() + 192L * terms;
        if (variables > 4096 || rows.size() > 4096 || terms > 65536 || allowance < 4096 || !budget.tryReserve(bytes)) {
            result = ExactLinearProgram.Result.UNKNOWN;
            return;
        }
        memory = bytes;
    }

    boolean step() {
        if (result != null) return true;
        long before = budget.threadWork();
        try {
            budget.check();
            if (work >= allowance || rounds >= 24 || selected.cardinality() > 192) return finish(ExactLinearProgram.Result.UNKNOWN);
            if (master == null) {
                columns = selected.stream().toArray();
                int[] inverse = new int[objective.length];
                Arrays.fill(inverse, -1);
                for (int j = 0; j < columns.length; j++) inverse[columns[j]] = j;
                List<ExactLinearProgram.Constraint> projected = new ArrayList<>();
                List<Integer> ids = new ArrayList<>();
                for (int r = 0; r < rows.size(); r++) {
                    var row = rows.get(r);
                    Map<Integer, BigInteger> terms = new LinkedHashMap<>();
                    for (var e : row.terms().entrySet()) {
                        budget.check();
                        if (inverse[e.getKey()] >= 0) terms.put(inverse[e.getKey()], e.getValue());
                    }
                    if (terms.isEmpty() && row.upper().signum() >= 0) continue;
                    ids.add(r);
                    projected.add(new ExactLinearProgram.Constraint(terms, row.upper()));
                }
                rowIds = ids.stream().mapToInt(Integer::intValue).toArray();
                BigInteger[] cost = Arrays.stream(columns).mapToObj(j -> objective[j]).toArray(BigInteger[]::new);
                // Each master inherits every branch/stock constraint. A declined
                // master cannot recursively create another column generator.
                master = new ExactLinearProgram(columns.length, projected, cost, budget, null, false, false);
                rounds++;
            }
            if (!master.step()) return false;
            var status = master.result();
            if (status == ExactLinearProgram.Result.UNKNOWN || status == ExactLinearProgram.Result.UNBOUNDED) return finish(status);
            boolean farkas = status == ExactLinearProgram.Result.INFEASIBLE;
            var localDual = farkas ? master.certificate() : master.optimumDual();
            if (localDual == null) return finish(ExactLinearProgram.Result.UNKNOWN);
            ExactRational[] weights = new ExactRational[rows.size()];
            Arrays.fill(weights, ExactRational.ZERO);
            for (int i = 0; i < rowIds.length; i++) {
                if (localDual[i].signum() < 0) return finish(ExactLinearProgram.Result.UNKNOWN);
                weights[rowIds[i]] = localDual[i];
            }
            ExactRational[] sums = new ExactRational[objective.length];
            Arrays.fill(sums, ExactRational.ZERO);
            ExactRational bound = ExactRational.ZERO;
            for (int r = 0; r < rows.size(); r++) {
                budget.check();
                if (weights[r].signum() == 0) continue;
                bound = bound.add(weights[r].multiply(ExactRational.of(rows.get(r).upper())));
                for (var e : rows.get(r).terms().entrySet()) {
                    budget.check();
                    sums[e.getKey()] = sums[e.getKey()].add(weights[r].multiply(ExactRational.of(e.getValue())));
                }
            }
            var improving = new ArrayList<Integer>();
            for (int j = 0; j < sums.length; j++) {
                budget.check();
                if (sums[j].compareTo(farkas ? ExactRational.ZERO : ExactRational.of(objective[j])) < 0) {
                    if (selected.get(j)) return finish(ExactLinearProgram.Result.UNKNOWN);
                    improving.add(j);
                }
            }
            if (improving.isEmpty()) {
                if (farkas) {
                    if (bound.signum() >= 0) return finish(ExactLinearProgram.Result.UNKNOWN);
                    certificate = weights;
                    if (budget.proofJournal() != null) budget.proofJournal().add(CountProof.certificate(
                            "complete_column_farkas", objective.length, rows, List.of(), weights, true));
                    return finish(ExactLinearProgram.Result.INFEASIBLE);
                }
                var candidate = new ExactRational[objective.length];
                Arrays.fill(candidate, ExactRational.ZERO);
                var local = master.point();
                ExactRational attained = ExactRational.ZERO;
                for (int j = 0; j < columns.length; j++) candidate[columns[j]] = local[j];
                for (int j = 0; j < candidate.length; j++) {
                    budget.check();
                    if (candidate[j].signum() < 0) return finish(ExactLinearProgram.Result.UNKNOWN);
                    attained = attained.add(candidate[j].multiply(ExactRational.of(objective[j])));
                }
                if (!attained.equals(bound)) return finish(ExactLinearProgram.Result.UNKNOWN);
                for (var row : rows) {
                    ExactRational sum = ExactRational.ZERO;
                    for (var e : row.terms().entrySet()) {
                        budget.check();
                        sum = sum.add(candidate[e.getKey()].multiply(ExactRational.of(e.getValue())));
                    }
                    if (sum.compareTo(ExactRational.of(row.upper())) > 0) throw new IllegalStateException("Priced witness violates original row");
                }
                point = candidate;
                dual = weights;
                return finish(ExactLinearProgram.Result.OPTIMAL);
            }
            improving.sort(Comparator.<Integer, ExactRational>comparing(j -> sums[j].subtract(
                    farkas ? ExactRational.ZERO : ExactRational.of(objective[j]))).thenComparingInt(j -> j));
            for (int j : improving.subList(0, Math.min(16, improving.size()))) {
                selected.set(j);
                added++;
            }
            master.close();
            master = null;
            return false;
        } catch (ExactRational.PrecisionLimit limit) {
            return finish(ExactLinearProgram.Result.UNKNOWN);
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private boolean finish(ExactLinearProgram.Result value) {
        result = value;
        budget.note("column_pricing", "result=" + value + "; variables=" + objective.length + "; selected=" + selected.cardinality() + "; rounds=" + rounds + "; priced=" + added + "; work=" + work);
        return true;
    }

    ExactLinearProgram.Result result() {
        return result;
    }

    ExactRational[] point() {
        return point == null ? null : point.clone();
    }

    ExactRational[] dual() {
        return dual == null ? null : dual.clone();
    }

    ExactRational[] certificate() {
        return certificate == null ? null : certificate.clone();
    }

    @Override
    public void close() {
        if (master != null) master.close();
        master = null;
        budget.release(memory);
        memory = 0;
    }
}
