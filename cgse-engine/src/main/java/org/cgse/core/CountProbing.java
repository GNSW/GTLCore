// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Failed-bound probing, as used by SCIP's prop_probing.c: temporarily split an
 * integer domain, propagate, and retain only proved consequences. The ancestor
 * propagation state is immutable. A cutoff yields no exclusion; every exported
 * exclusion is replayed by the independent integer certificate checker.
 */
final class CountProbing implements AutoCloseable {

    private final PlanningBudget budget;
    private final List<ExactLinearProgram.Constraint> base;
    private final List<ExactLinearProgram.Constraint> cuts = new ArrayList<>();
    private final List<CountConflict> learned = new ArrayList<>();
    private final BigInteger[] lower, upper;
    private final List<Integer> choices = new ArrayList<>();
    private final long allowance;
    private CountBounds propagating;
    private CountBounds.Seed seed;
    private ExactLinearProgram.Constraint assumption;
    private BigInteger pivot;
    private int index, side, probes;
    private long memory, work;
    private boolean initialized, complete, infeasible;

    CountProbing(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget) {
        this.budget = budget;
        this.lower = lower.clone();
        this.upper = upper.clone();
        base = new ArrayList<>(rows);
        allowance = Math.min(131_072, budget.remainingWork() / 16);
        if (lower.length > 128 || rows.size() > 512 || allowance < 8192) {
            complete = true;
            return;
        }
        for (int i = 0; i < lower.length; i++) {
            base.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), lower[i].negate()));
            if (upper[i] != null) base.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), upper[i]));
            if (!lower[i].equals(upper[i])) choices.add(i);
        }
        // Probing is useful on small discrete decisions. Wide domains retain
        // their algebraic/LP paths instead of repeatedly bisecting huge counts.
        if (choices.stream().filter(i -> upper[i] != null && upper[i].subtract(lower[i]).compareTo(BigInteger.valueOf(32)) <= 0).count() < 2) {
            complete = true;
            return;
        }
        long bytes = 1024 + 512L * lower.length + base.stream().mapToLong(r -> 160L + 96L * r.terms().size()).sum();
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        int[] degree = new int[lower.length];
        for (var row : rows) for (int id : row.terms().keySet()) degree[id]++;
        choices.sort(Comparator.<Integer>comparingInt(i -> -degree[i]).thenComparingInt(i -> i));
    }

    boolean step() {
        if (complete) return true;
        long before = budget.threadWork();
        try {
            budget.check();
            if (work >= allowance) return finish("work_limit");
            if (!initialized) {
                if (propagating == null) propagating = new CountBounds(lower.length, base, budget, base.size());
                if (!propagating.step()) return false;
                // Even an already blocked model goes through the checker below.
                if (!propagating.blocked()) seed = propagating.snapshot();
                propagating.close();
                propagating = null;
                initialized = true;
                return false;
            }
            if (propagating != null) {
                if (!propagating.step()) return false;
                boolean blocked = propagating.blocked();
                propagating.close();
                propagating = null;
                probes++;
                if (blocked && certify()) {
                    Map<Integer, BigInteger> terms = new LinkedHashMap<>();
                    assumption.terms().forEach((id, value) -> terms.put(id, value.negate()));
                    var cut = new ExactLinearProgram.Constraint(terms, assumption.upper().negate().subtract(BigInteger.ONE));
                    cuts.add(cut);
                    int id = choices.get(index);
                    if (side == 0) lower[id] = lower[id].max(pivot.add(BigInteger.ONE));
                    else upper[id] = upper[id] == null ? pivot : upper[id].min(pivot);
                    if (upper[id] != null && lower[id].compareTo(upper[id]) > 0) {
                        infeasible = true;
                        return finish("proven_infeasible");
                    }
                }
                if (++side == 2) {
                    side = 0;
                    index++;
                }
                return false;
            }
            if (index >= Math.min(12, choices.size())) return finish("complete");
            int id = choices.get(index);
            if (side == 0) {
                if (lower[id].equals(upper[id])) {
                    index++;
                    return false;
                }
                pivot = upper[id] == null ? lower[id] : lower[id].add(upper[id]).shiftRight(1);
            }
            assumption = side == 0 ? new ExactLinearProgram.Constraint(Map.of(id, BigInteger.ONE), pivot) :
                    new ExactLinearProgram.Constraint(Map.of(id, BigInteger.ONE.negate()), pivot.add(BigInteger.ONE).negate());
            var input = new ArrayList<>(base);
            input.addAll(cuts);
            int start = input.size();
            input.add(assumption);
            propagating = new CountBounds(lower.length, input, budget, start, seed);
            return false;
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private boolean certify() {
        var clause = new CountConflict(List.of(assumption));
        var candidates = new ArrayList<>(learned);
        candidates.add(clause);
        var proof = CountProof.certificate("bound_probe", lower.length, base, candidates, null, false);
        long checkAllowance = Math.min(16_384, Math.min(allowance - work, budget.remainingWork() / 8));
        if (checkAllowance < 256) return false;
        // The independent checker has its own strict work counter. Conservatively
        // charge its entire allowance; actual work is never refunded or hidden.
        for (long i = 0; i < checkAllowance; i++) budget.check();
        if (CountProof.verify(proof, checkAllowance) != CountProof.Verdict.VERIFIED) return false;
        learned.add(clause);
        if (budget.proofJournal() != null) budget.proofJournal().add(proof);
        return true;
    }

    private boolean finish(String detail) {
        complete = true;
        budget.note("count_probing", detail + "; probes=" + probes + "; verified_bounds=" + cuts.size() + "; work=" + work);
        return true;
    }

    List<ExactLinearProgram.Constraint> cuts() {
        return List.copyOf(cuts);
    }

    List<CountConflict> learnedConflicts() {
        return List.copyOf(learned);
    }

    boolean infeasible() {
        return infeasible;
    }

    @Override
    public void close() {
        if (propagating != null) propagating.close();
        propagating = null;
        if (seed != null) seed.close();
        seed = null;
        budget.release(memory);
        memory = 0;
    }
}
