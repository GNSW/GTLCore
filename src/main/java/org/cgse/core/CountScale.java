package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;

/** Optional divisible-count witness. Failure never restricts the original integer domain. */
final class CountScale implements AutoCloseable {

    private final PlanningBudget budget;
    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final boolean factor;
    private CountQuickSolve search;
    private final Deque<BigInteger> factors = new ArrayDeque<>();
    private BigInteger scale;
    private BigInteger[] counts;
    private long work, allowance;

    CountScale(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget) {
        this(rows, lower, upper, budget, true);
    }

    CountScale(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget, boolean factor) {
        this.budget = budget;
        this.factor = factor;
        this.rows = rows;
        this.lower = lower;
        this.upper = upper;
        if (lower.length == 0 || lower.length > 128 || rows.size() > 512) return;
        BigInteger divisor = BigInteger.ZERO, negative = BigInteger.ZERO, positive = BigInteger.ZERO;
        BigInteger coupledNegative = BigInteger.ZERO, coupledPositive = BigInteger.ZERO;
        for (var row : rows) {
            budget.check();
            if (row.terms().isEmpty()) continue;
            divisor = divisor.gcd(row.upper());
            if (row.upper().signum() < 0) negative = negative.gcd(row.upper());
            else if (row.upper().signum() > 0) positive = positive.gcd(row.upper());
            if (row.terms().size() > 1) {
                if (row.upper().signum() < 0) coupledNegative = coupledNegative.gcd(row.upper());
                else if (row.upper().signum() > 0) coupledPositive = coupledPositive.gcd(row.upper());
            }
        }
        var candidates = new LinkedHashSet<BigInteger>();
        addFactors(candidates, divisor);
        // Extra stock or a slightly smaller order can break the common GCD.
        // A divisible witness is still useful: round each scaled inequality
        // inward and allow surplus to remain unused. Neither its failure nor
        // any conflict learned in this restricted domain leaves this strategy.
        addFactors(candidates, negative);
        // Propagated singleton bounds may have arbitrary rounding remainders.
        // Use coupled material rows to propose factors too, while still keeping
        // every singleton bound in the candidate problem and final verification.
        addFactors(candidates, coupledNegative);
        addFactors(candidates, positive);
        addFactors(candidates, coupledPositive);
        // This is for huge repeated orders, not another cost on small models.
        if (candidates.isEmpty()) return;
        allowance = Math.min(1_048_576, budget.remainingWork() / 8);
        factors.addAll(candidates);
        beginNext();
    }

    private void addFactors(LinkedHashSet<BigInteger> candidates, BigInteger divisor) {
        if (divisor.signum() != 0 && divisor.bitLength() < 16) return;
        BigInteger bounded = divisor;
        for (int i = 0; i < lower.length; i++) {
            budget.check();
            bounded = bounded.gcd(lower[i]);
            if (upper[i] != null) bounded = bounded.gcd(upper[i]);
        }
        if (bounded.bitLength() >= 16) divisor = bounded;
        if (divisor.bitLength() < 16) return;
        candidates.add(divisor);
        // The largest common factor may force every source group to choose one
        // source exclusively. Smaller factors retain mixed allocations such as
        // one third from A and two thirds from B, still within the same quota.
        for (int part = 2; part <= 16; part++) {
            BigInteger[] divided = divisor.divideAndRemainder(BigInteger.valueOf(part));
            if (divided[1].signum() == 0 && divided[0].bitLength() >= 16) candidates.add(divided[0]);
        }
    }

    private boolean beginNext() {
        while (!factors.isEmpty() && work < allowance) {
            scale = factors.removeFirst();
            if (begin()) return true;
        }
        return false;
    }

    private boolean begin() {
        var reduced = new ArrayList<ExactLinearProgram.Constraint>();
        for (var row : rows) {
            budget.check();
            if (row.terms().isEmpty()) {
                if (row.upper().signum() < 0) return false;
            } else reduced.add(new ExactLinearProgram.Constraint(row.terms(), floorDiv(row.upper(), scale)));
        }
        BigInteger[] lo = new BigInteger[lower.length], hi = new BigInteger[upper.length];
        for (int i = 0; i < lo.length; i++) {
            budget.check();
            lo[i] = lower[i].add(scale).subtract(BigInteger.ONE).divide(scale);
            if (upper[i] != null) hi[i] = upper[i].divide(scale);
            if (hi[i] != null && lo[i].compareTo(hi[i]) > 0) return false;
        }
        search = new CountQuickSolve(reduced, lo, hi, budget, false, factor, allowance - work);
        budget.note("count_scale", "candidate_factor=" + scale + "; variables=" + lo.length);
        return true;
    }

    private static BigInteger floorDiv(BigInteger value, BigInteger divisor) {
        BigInteger[] qr = value.divideAndRemainder(divisor);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }

    boolean step() {
        if (search == null) return true;
        long before = budget.threadWork();
        try {
            return advance();
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private boolean advance() {
        if (work >= allowance) {
            budget.note("count_scale", "candidate_work_limit; original_domain_retained");
            close();
            return true;
        }
        BigInteger[] value = null;
        try {
            if (!search.step()) return false;
            value = search.counts();
        } catch (ExactRational.PrecisionLimit ignored) {
            // A candidate relaxation's precision limit proves nothing.
        }
        if (value != null) {
            for (int i = 0; i < value.length; i++) value[i] = value[i].multiply(scale);
            boolean valid = true;
            for (int i = 0; i < value.length; i++) {
                budget.check();
                if (value[i].compareTo(lower[i]) < 0 || upper[i] != null && value[i].compareTo(upper[i]) > 0) valid = false;
            }
            for (var row : rows) {
                BigInteger total = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) {
                    budget.check();
                    total = total.add(value[term.getKey()].multiply(term.getValue()));
                }
                if (total.compareTo(row.upper()) > 0) valid = false;
            }
            if (valid) counts = value;
        }
        budget.note("count_scale", "witness=" + (counts != null) + "; work=" + work + "; original_domain_retained");
        search.close();
        search = null;
        if (counts == null && beginNext()) return false;
        close();
        return true;
    }

    BigInteger[] counts() {
        return counts;
    }

    @Override
    public void close() {
        if (search != null) search.close();
        search = null;
        factors.clear();
    }
}
