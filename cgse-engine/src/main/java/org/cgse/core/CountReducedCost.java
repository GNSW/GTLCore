// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Exact reduced-cost fixing, scoped to an explicit incumbent objective cutoff. */
final class CountReducedCost implements AutoCloseable {

    private final List<ExactLinearProgram.Constraint> rows = new ArrayList<>(), cuts = new ArrayList<>();
    private final BigInteger[] lower, upper, cost;
    private final BigInteger cutoff;
    private final PlanningBudget budget;
    private final long allowance;
    private ExactLinearProgram linear;
    private BigInteger objectiveLower;
    private long work, memory;
    private boolean complete, infeasible;

    CountReducedCost(List<ExactLinearProgram.Constraint> constraints, BigInteger[] lower, BigInteger[] upper,
                     BigInteger[] cost, BigInteger cutoff, PlanningBudget budget, long maximumWork) {
        this.lower = lower;
        this.upper = upper;
        this.cost = cost;
        this.cutoff = cutoff;
        this.budget = budget;
        allowance = Math.min(maximumWork, budget.remainingWork() / 32);
        if (lower.length > 256 || constraints.size() > 1024 || allowance < 4096 ||
                Arrays.stream(lower).anyMatch(v -> v.signum() < 0)) {
            complete = true;
            return;
        }
        long bytes = 4096L + 512L * lower.length + 32L * constraints.size();
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        rows.addAll(constraints);
        for (int j = 0; j < lower.length; j++) {
            rows.add(new ExactLinearProgram.Constraint(Map.of(j, BigInteger.ONE.negate()), lower[j].negate()));
            if (upper[j] != null) rows.add(new ExactLinearProgram.Constraint(Map.of(j, BigInteger.ONE), upper[j]));
        }
    }

    boolean step() {
        if (complete) return true;
        long before = budget.threadWork();
        try {
            budget.check();
            if (work >= allowance) return finish();
            if (linear == null) linear = new ExactLinearProgram(lower.length, rows,
                    Arrays.stream(cost).map(BigInteger::negate).toArray(BigInteger[]::new), budget);
            if (!linear.step()) return false;
            if (linear.result() == ExactLinearProgram.Result.INFEASIBLE) {
                infeasible = true;
                return finish();
            }
            if (linear.result() != ExactLinearProgram.Result.OPTIMAL) return finish();
            var dual = linear.optimumDual();
            if (dual == null) return finish();
            BigInteger scale = BigInteger.ONE;
            for (var value : dual) {
                budget.check();
                if (value.signum() < 0) return finish();
                scale = scale.multiply(value.denominator().divide(scale.gcd(value.denominator())));
                if (scale.bitLength() > 512) return finish();
            }
            BigInteger[] reduced = new BigInteger[cost.length];
            for (int j = 0; j < reduced.length; j++) reduced[j] = cost[j].multiply(scale);
            var multipliers = new ArrayList<BigInteger>();
            BigInteger bound = BigInteger.ZERO;
            for (int r = 0; r < rows.size(); r++) {
                BigInteger weight = dual[r].numerator().multiply(scale.divide(dual[r].denominator()));
                multipliers.add(weight);
                bound = bound.add(weight.multiply(rows.get(r).upper()));
                for (var term : rows.get(r).terms().entrySet()) {
                    budget.check();
                    reduced[term.getKey()] = reduced[term.getKey()].add(weight.multiply(term.getValue()));
                }
            }
            if (Arrays.stream(reduced).anyMatch(v -> v.signum() < 0)) return finish();
            objectiveLower = floor(bound, scale).negate();
            // Sum y*A + c is nonnegative. Under c*x <= cutoff it gives
            // reduced*x <= y*b + cutoff; eliminate other coordinates at
            // their declared lower bounds. Never export this to feasibility
            // search without the same or a stronger objective restriction.
            var scope = new ArrayList<>(rows.stream().map(CountProof::row).toList());
            var objectiveTerms = new TreeMap<Integer, BigInteger>();
            for (int j = 0; j < cost.length; j++) if (cost[j].signum() != 0) objectiveTerms.put(j, cost[j]);
            scope.add(new CountProof.Row(objectiveTerms, cutoff));
            multipliers.add(scale);
            bound = bound.add(scale.multiply(cutoff));
            BigInteger minimum = BigInteger.ZERO;
            for (int j = 0; j < lower.length; j++) minimum = minimum.add(reduced[j].multiply(lower[j]));
            for (int j = 0; j < lower.length; j++) {
                budget.check();
                if (reduced[j].signum() == 0) continue;
                BigInteger endpoint = floor(bound.subtract(minimum).add(reduced[j].multiply(lower[j])), reduced[j]);
                if (upper[j] != null && endpoint.compareTo(upper[j]) >= 0) continue;
                var axioms = new ArrayList<>(scope);
                var weights = new ArrayList<>(multipliers);
                for (int i = 0; i < lower.length; i++) if (i != j && reduced[i].signum() > 0) {
                    budget.check();
                    axioms.add(new CountProof.Row(Map.of(i, BigInteger.ONE.negate()), lower[i].negate()));
                    weights.add(reduced[i]);
                }
                var cut = new ExactLinearProgram.Constraint(Map.of(j, BigInteger.ONE), endpoint);
                var proof = new CountProof.Rounding("incumbent_reduced_cost", lower.length, axioms, weights,
                        reduced[j], Arrays.asList(lower), CountProof.row(cut));
                long check = Math.min(131072, Math.max(1, allowance - work));
                budget.charge(axioms.size() + 2L * lower.length + axioms.stream().mapToLong(r -> r.terms().size()).sum());
                if (CountProof.verify(proof, check) == CountProof.Verdict.VERIFIED) {
                    cuts.add(cut);
                    infeasible |= endpoint.compareTo(lower[j]) < 0;
                    if (budget.proofJournal() != null) budget.proofJournal().add(proof);
                }
            }
            infeasible |= objectiveLower.compareTo(cutoff) > 0;
            return finish();
        } catch (ExactRational.PrecisionLimit limit) {
            return finish();
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private static BigInteger floor(BigInteger numerator, BigInteger denominator) {
        BigInteger[] qr = numerator.divideAndRemainder(denominator);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }

    private boolean finish() {
        complete = true;
        budget.note("count_reduced_cost", "cutoff=" + cutoff + "; bounds=" + cuts.size() + "; lower=" + objectiveLower + "; closed=" + infeasible + "; work=" + work);
        return true;
    }

    List<ExactLinearProgram.Constraint> cuts() {
        return List.copyOf(cuts);
    }

    BigInteger objectiveLower() {
        return objectiveLower;
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
