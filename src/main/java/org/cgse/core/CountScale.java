// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

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
    private final boolean retaining;
    private CountQuickSolve search;
    private final Deque<BigInteger> factors = new ArrayDeque<>();
    private BigInteger scale;
    private BigInteger[] counts;
    private long work, allowance;
    private long completionLimit;
    private boolean paused;
    private boolean completionExtended;

    CountScale(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget) {
        this(rows, lower, upper, budget, true);
    }

    CountScale(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget, boolean factor) {
        this(rows, lower, upper, budget, factor, false);
    }

    CountScale(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget,
               boolean factor, boolean retaining) {
        this.budget = budget;
        this.factor = factor;
        this.retaining = retaining;
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
        // Small factors are useful too when they expose a small-domain model.
        // Admission depends on that reduction, not the requested quantity alone.
        if (candidates.isEmpty()) return;
        allowance = Math.min(1_048_576, budget.remainingWork() / 8);
        // An admitted finite table may earn one estimated completion turn.
        // Keep at least two thirds of the current order budget for other paths;
        // later turns retain progress without renewing this initial allowance.
        completionLimit = Math.min(8_388_608, budget.remainingWork() / 3);
        factors.addAll(candidates);
        beginNext();
    }

    private void addFactors(LinkedHashSet<BigInteger> candidates, BigInteger divisor) {
        if (divisor.equals(BigInteger.ONE)) return;
        BigInteger bounded = divisor;
        for (int i = 0; i < lower.length; i++) {
            budget.check();
            bounded = bounded.gcd(lower[i]);
            if (upper[i] != null) bounded = bounded.gcd(upper[i]);
        }
        if (usefulFactor(bounded)) divisor = bounded;
        if (!usefulFactor(divisor)) return;
        candidates.add(divisor);
        // The largest common factor may force every source group to choose one
        // source exclusively. Smaller factors retain mixed allocations such as
        // one third from A and two thirds from B, still within the same quota.
        for (int part = 2; part <= 16; part++) {
            BigInteger[] divided = divisor.divideAndRemainder(BigInteger.valueOf(part));
            if (divided[1].signum() == 0 && !candidates.contains(divided[0]) && usefulFactor(divided[0])) candidates.add(divided[0]);
        }
    }

    private boolean usefulFactor(BigInteger divisor) {
        if (divisor.compareTo(BigInteger.ONE) <= 0) return false;
        if (divisor.bitLength() >= 16) return true;
        int free = 0, narrowed = 0;
        for (int i = 0; i < lower.length; i++) {
            budget.check();
            if (upper[i] == null) { free++; continue; }
            BigInteger width = upper[i].subtract(lower[i]);
            if (width.signum() == 0) continue;
            free++;
            BigInteger low = lower[i].add(divisor).subtract(BigInteger.ONE).divide(divisor);
            BigInteger high = upper[i].divide(divisor);
            if (low.compareTo(high) > 0) return false;
            if (width.compareTo(BigInteger.ONE) > 0 && high.subtract(low).compareTo(BigInteger.valueOf(3)) <= 0) narrowed++;
        }
        // A majority of the live domains must become at most four-valued.
        // Failure of this restricted lattice still proves nothing about the
        // unscaled model; it remains available to all the other strategies.
        return narrowed > 0 && narrowed * 2 >= free;
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
        // A retained outer turn limits actual work. Let the matcher keep its
        // table across those turns rather than exhausting the same first slice
        // twice (here and inside the quick portfolio).
        long matchingWork = retaining ? Math.min(12_000_000, budget.remainingWork() / 2) : allowance - work;
        search = new CountQuickSolve(reduced, lo, hi, budget, false, factor, matchingWork);
        budget.note("count_scale", "candidate_factor=" + scale + "; variables=" + lo.length);
        return true;
    }

    private static BigInteger floorDiv(BigInteger value, BigInteger divisor) {
        BigInteger[] qr = value.divideAndRemainder(divisor);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }

    boolean step() {
        if (search == null || paused) return true;
        long before = budget.threadWork();
        try {
            return advance();
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private boolean advance() {
        if (work >= allowance) {
            if (retaining && !completionExtended) {
                completionExtended = true;
                long extra = search.finiteContinuationWork();
                if (extra > 0 && work < completionLimit) {
                    allowance = work + Math.min(extra, completionLimit - work);
                    budget.note("count_scale", "finite_table_continuation; factor=" + scale + "; allowance=" + allowance);
                    return false;
                }
            }
            if (retaining) {
                budget.note("count_scale", "local_pause; factor=" + scale + "; work=" + work);
                return paused = true;
            }
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

    boolean paused() {
        return paused;
    }

    boolean finiteProbing() {
        return search != null && search.finiteProbing();
    }

    void resume(long quantum) {
        if (!paused || quantum <= 0) throw new IllegalStateException("Scaled search is not paused");
        budget.check();
        allowance = work + Math.min(quantum, budget.remainingWork());
        paused = false;
    }

    @Override
    public void close() {
        if (search != null) search.close();
        search = null;
        paused = false;
        factors.clear();
    }
}
