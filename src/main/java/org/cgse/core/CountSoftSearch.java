// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Weighted soft linear constraints: exact selector relaxation and bounded objective search. */
final class CountSoftSearch implements AutoCloseable {

    record Soft(ExactLinearProgram.Constraint condition, BigInteger weight) {

        Soft {
            if (weight.signum() <= 0) throw new IllegalArgumentException("Nonpositive preference weight");
        }
    }

    private final List<ExactLinearProgram.Constraint> hard, encoded = new ArrayList<>();
    private final List<Soft> soft;
    private final PlanningBudget budget;
    private final BigInteger[] low, high;
    private final Map<Integer, BigInteger> objective = new LinkedHashMap<>();
    private final long allowance;
    private final int originalVariables;
    private CountDomainSearch search;
    private CountReducedCost fixing;
    private boolean fixed;
    private CountCoreSoft cores;
    private boolean coreAttempted;
    private BigInteger[] best;
    private BigInteger lower = BigInteger.ZERO, upper = BigInteger.ZERO, trial;
    private long work, memory;
    private int probes;
    private boolean complete, infeasible, optimal;

    CountSoftSearch(List<ExactLinearProgram.Constraint> hard, BigInteger[] lower, BigInteger[] upper,
                    List<Soft> soft, PlanningBudget budget, long maximumWork) {
        this(hard, lower, upper, soft, budget, maximumWork, true);
    }

    CountSoftSearch(List<ExactLinearProgram.Constraint> hard, BigInteger[] lower, BigInteger[] upper,
                    List<Soft> soft, PlanningBudget budget, long maximumWork, boolean coreSearch) {
        this.hard = hard;
        this.soft = List.copyOf(soft);
        this.budget = budget;
        coreAttempted = !coreSearch || soft.size() < 8;
        originalVariables = lower.length;
        allowance = Math.min(maximumWork, budget.remainingWork() / 16);
        low = Arrays.copyOf(lower, lower.length + soft.size());
        high = Arrays.copyOf(upper, upper.length + soft.size());
        if (low.length > 512 || soft.size() > 128 || hard.size() > 1024 || allowance < 2048) {
            complete = true;
            return;
        }
        long bytes = 4096L + 512L * low.length + 256L * soft.stream().mapToLong(s -> s.condition().terms().size()).sum();
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        try {
            encoded.addAll(hard);
            for (int s = 0; s < soft.size(); s++) {
                var preference = soft.get(s);
                BigInteger maximum = BigInteger.ZERO;
                for (var term : preference.condition().terms().entrySet()) {
                    budget.check();
                    BigInteger endpoint = term.getValue().signum() > 0 ? upper[term.getKey()] : lower[term.getKey()];
                    if (endpoint == null) {
                        complete = true;
                        return;
                    }
                    maximum = maximum.add(term.getValue().multiply(endpoint));
                }
                int id = lower.length + s;
                low[id] = BigInteger.ZERO;
                high[id] = BigInteger.ONE;
                var terms = new LinkedHashMap<>(preference.condition().terms());
                BigInteger relaxation = maximum.subtract(preference.condition().upper()).max(BigInteger.ZERO);
                if (relaxation.signum() > 0) terms.put(id, relaxation.negate());
                encoded.add(new ExactLinearProgram.Constraint(terms, preference.condition().upper()));
                objective.put(id, preference.weight());
                this.upper = this.upper.add(preference.weight());
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    boolean step() {
        if (complete) return true;
        long before = budget.threadWork();
        try {
            budget.check();
            if (work >= allowance) return finish(false);
            if (best != null && lower.compareTo(upper) >= 0) return finish(true);
            if (!coreAttempted) {
                if (cores == null) cores = new CountCoreSoft(hard, Arrays.copyOf(low, originalVariables), Arrays.copyOf(high, originalVariables),
                        soft, budget, Math.min(65536, allowance / 3));
                if (!cores.step()) return false;
                lower = lower.max(cores.lowerBound());
                if (cores.counts() != null) {
                    best = cores.counts();
                    upper = cores.cost();
                }
                boolean solved = cores.optimal();
                infeasible = cores.infeasible();
                cores.close();
                cores = null;
                coreAttempted = true;
                if (infeasible || solved) return finish(true);
            }
            if (best != null && !fixed) {
                if (fixing == null) {
                    BigInteger[] cost = new BigInteger[low.length];
                    Arrays.fill(cost, BigInteger.ZERO);
                    objective.forEach((id, weight) -> cost[id] = weight);
                    fixing = new CountReducedCost(encoded, low, high, cost, upper.subtract(BigInteger.ONE), budget,
                            Math.min(16384, (allowance - work) / 4));
                }
                if (!fixing.step()) return false;
                boolean closed = fixing.infeasible();
                encoded.addAll(fixing.cuts());
                if (fixing.objectiveLower() != null) lower = lower.max(fixing.objectiveLower());
                fixing.close();
                fixing = null;
                fixed = true;
                if (closed || lower.compareTo(upper) >= 0) return finish(true);
            }
            if (search == null) {
                trial = best == null ? upper : lower.add(upper).subtract(BigInteger.ONE).shiftRight(1);
                var rows = new ArrayList<>(encoded);
                rows.add(new ExactLinearProgram.Constraint(objective, trial));
                search = new CountDomainSearch(rows, low, high, budget, Math.min(32768, allowance - work));
                probes++;
            }
            if (!search.step()) return false;
            var candidate = search.counts();
            boolean closed = search.infeasible();
            search.close();
            search = null;
            if (candidate != null) {
                best = Arrays.copyOf(candidate, originalVariables);
                upper = cost(best);
                return false;
            }
            if (!closed) return finish(false);
            if (best == null) {
                infeasible = true;
                return finish(true);
            }
            lower = trial.add(BigInteger.ONE);
            return false;
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private BigInteger cost(BigInteger[] candidate) {
        for (var row : hard) if (!satisfies(row, candidate)) throw new IllegalStateException("Soft solver violated hard constraint");
        BigInteger result = BigInteger.ZERO;
        for (var preference : soft) if (!satisfies(preference.condition(), candidate)) result = result.add(preference.weight());
        if (result.compareTo(trial) > 0) throw new IllegalStateException("Soft objective exceeds selector bound");
        return result;
    }

    private boolean satisfies(ExactLinearProgram.Constraint row, BigInteger[] candidate) {
        BigInteger sum = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) {
            budget.check();
            sum = sum.add(term.getValue().multiply(candidate[term.getKey()]));
        }
        return sum.compareTo(row.upper()) <= 0;
    }

    private boolean finish(boolean proved) {
        complete = true;
        optimal = proved && best != null;
        budget.note("count_soft", "witness=" + (best != null) + "; cost=" + (best == null ? "none" : upper) + "; lower=" + lower + "; optimal=" + optimal + "; infeasible=" + infeasible + "; probes=" + probes + "; work=" + work);
        return true;
    }

    BigInteger[] counts() {
        return best == null ? null : best.clone();
    }

    BigInteger cost() {
        return best == null ? null : upper;
    }

    BigInteger lowerBound() {
        return lower;
    }

    boolean optimal() {
        return optimal;
    }

    boolean infeasible() {
        return infeasible;
    }

    @Override
    public void close() {
        if (cores != null) cores.close();
        cores = null;
        if (fixing != null) fixing.close();
        fixing = null;
        if (search != null) search.close();
        search = null;
        budget.release(memory);
        memory = 0;
    }
}
