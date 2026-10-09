// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Optimization-based bound tightening, with exact dual certificates for every exported bound. */
final class CountObbt implements AutoCloseable {

    private final List<ExactLinearProgram.Constraint> rows = new ArrayList<>(), cuts = new ArrayList<>();
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private final List<Integer> variables = new ArrayList<>();
    private final long allowance;
    private ExactLinearProgram linear;
    private BigInteger[] counts;
    private int cursor, side;
    private long work, memory;
    private boolean complete, infeasible;

    CountObbt(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, ExactRational[] point, PlanningBudget budget) {
        this.lower = lower;
        this.upper = upper;
        this.budget = budget;
        allowance = Math.min(65536, budget.remainingWork() / 32);
        if (lower.length > 64 || rows.size() > 256 || allowance < 4096) {
            complete = true;
            return;
        }
        long bytes = 2048L + 512L * lower.length + 32L * rows.size();
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        this.rows.addAll(rows);
        for (int i = 0; i < lower.length; i++) {
            this.rows.add(bound(i, lower[i], true));
            if (upper[i] != null) this.rows.add(bound(i, upper[i], false));
            if (!lower[i].equals(upper[i])) variables.add(i);
        }
        if (point != null) variables.sort(Comparator.<Integer>comparingInt(i -> point[i].integral() ? 1 : 0).thenComparingInt(i -> i));
    }

    boolean step() {
        if (complete) return true;
        long started = budget.threadWork();
        try {
            budget.check();
            if (work >= allowance || cursor >= Math.min(6, variables.size())) return finish();
            int id = variables.get(cursor);
            if (linear == null) {
                BigInteger[] objective = new BigInteger[lower.length];
                Arrays.fill(objective, BigInteger.ZERO);
                objective[id] = side == 0 ? BigInteger.ONE.negate() : BigInteger.ONE;
                linear = new ExactLinearProgram(lower.length, rows, objective, budget);
            }
            if (!linear.step()) return false;
            if (linear.result() == ExactLinearProgram.Result.INFEASIBLE) {
                infeasible = true;
                return finish();
            }
            if (linear.result() == ExactLinearProgram.Result.OPTIMAL) {
                var point = linear.point();
                BigInteger endpoint = side == 0 ? point[id].ceil() : point[id].floor();
                if (side == 0 ? endpoint.compareTo(lower[id]) > 0 : upper[id] == null || endpoint.compareTo(upper[id]) < 0) {
                    var cut = bound(id, endpoint, side == 0);
                    var proof = certificate(id, side == 0, linear.optimumDual(), cut);
                    if (proof != null && CountProof.verify(proof, 200000) == CountProof.Verdict.VERIFIED) {
                        cuts.add(cut);
                        if (budget.proofJournal() != null) budget.proofJournal().add(proof);
                    }
                }
                if (Arrays.stream(point).allMatch(ExactRational::integral)) counts = Arrays.stream(point).map(ExactRational::numerator).toArray(BigInteger[]::new);
            }
            linear.close();
            linear = null;
            if (++side == 2) {
                side = 0;
                cursor++;
            }
            if (counts != null) return finish();
            return false;
        } finally {
            work += budget.threadWork() - started;
        }
    }

    private CountProof.Rounding certificate(int variable, boolean minimum, ExactRational[] dual, ExactLinearProgram.Constraint cut) {
        if (dual == null || dual.length != rows.size()) return null;
        BigInteger divisor = BigInteger.ONE;
        for (var value : dual) {
            budget.check();
            if (value.signum() < 0) return null;
            divisor = divisor.multiply(value.denominator().divide(divisor.gcd(value.denominator())));
            if (divisor.bitLength() > 512) return null;
        }
        List<CountProof.Row> axioms = new ArrayList<>(rows.stream().map(CountProof::row).toList());
        List<BigInteger> weights = new ArrayList<>();
        BigInteger[] sum = new BigInteger[lower.length];
        Arrays.fill(sum, BigInteger.ZERO);
        for (int r = 0; r < rows.size(); r++) {
            BigInteger weight = dual[r].numerator().multiply(divisor.divide(dual[r].denominator()));
            weights.add(weight);
            for (var term : rows.get(r).terms().entrySet()) {
                budget.check();
                sum[term.getKey()] = sum[term.getKey()].add(weight.multiply(term.getValue()));
            }
        }
        for (int i = 0; i < sum.length; i++) {
            budget.check();
            BigInteger expected = i == variable ? (minimum ? divisor.negate() : divisor) : BigInteger.ZERO;
            BigInteger surplus = sum[i].subtract(expected);
            if (surplus.signum() < 0) return null;
            axioms.add(new CountProof.Row(Map.of(i, BigInteger.ONE.negate()), BigInteger.ZERO));
            weights.add(surplus);
        }
        return new CountProof.Rounding("obbt_exact_dual", lower.length, axioms, weights, divisor,
                Collections.nCopies(lower.length, BigInteger.ZERO), CountProof.row(cut));
    }

    private static ExactLinearProgram.Constraint bound(int id, BigInteger value, boolean minimum) {
        return new ExactLinearProgram.Constraint(Map.of(id, minimum ? BigInteger.ONE.negate() : BigInteger.ONE), minimum ? value.negate() : value);
    }

    private boolean finish() {
        complete = true;
        budget.note("count_obbt", "bounds=" + cuts.size() + "; witness=" + (counts != null) + "; infeasible=" + infeasible + "; work=" + work);
        return true;
    }

    List<ExactLinearProgram.Constraint> cuts() {
        return List.copyOf(cuts);
    }

    BigInteger[] counts() {
        return counts == null ? null : counts.clone();
    }

    boolean infeasible() {
        return infeasible;
    }

    @Override
    public void close() {
        if (linear != null) linear.close();
        linear = null;
        budget.release(memory);
        memory = 0;
    }
}
