// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Checks a completed witness's production obligation; failure only rejects that witness. */
final class ForceCraftProof<K> implements AutoCloseable {

    enum Outcome {
        PROVED, NON_PRODUCTIVE, OUTPUT_SHORTFALL, MODEL_LIMIT, BATCH_SEMANTICS,
        WORK_LIMIT, MEMORY_LIMIT, RELAXATION_INCONCLUSIVE
    }

    private final PlanningBudget budget;
    private final long allowance;
    private CountQuickSolve counterexample;
    private CountBounds propagating;
    private final List<GraphRecipe<K>> recipes = new ArrayList<>();
    private final List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
    private final BitSet startup = new BitSet();
    private Map<K, BigInteger> initial;
    private BigInteger[] low, high;
    private long memory, work;
    private boolean complete, proved;
    private Outcome outcome = Outcome.RELAXATION_INCONCLUSIVE;

    ForceCraftProof(GraphPlan<K> plan, PlanVerification<K> verified, Map<K, Long> mandatorySeeds, PlanningBudget budget) {
        try (var costs = budget.trace(PlanningCostTrace.Stage.FORCE_CHECK)) {
            this.budget = budget;
            allowance = Math.min(65536, budget.remainingWork() / 8);
            BigInteger amount = BigInteger.valueOf(plan.amount());
            BigInteger net = verified.summary().delta(plan.target());
            // Returned configuration tokens are not physical production. A zero
            // gain loop cannot discharge an ordinary forced crafting request.
            if (verified.physicalProduced(plan.target()).compareTo(amount) < 0 || net.signum() <= 0) {
                outcome = net.signum() <= 0 ? Outcome.NON_PRODUCTIVE : Outcome.OUTPUT_SHORTFALL;
                complete = true;
                return;
            }
            if (net.compareTo(amount) >= 0) {
                complete = proved = true;
                return;
            }
            var counts = verified.patternCounts();
            long terms = counts.keySet().stream().map(plan.recipes()::get).mapToLong(r -> r.inputs().size() + r.outputs().size()).sum();
            if (counts.size() > 96 || terms > 2048 || allowance < 2048 ||
                    counts.keySet().stream().map(plan.recipes()::get).anyMatch(GraphRecipe::batchSensitiveInputs)) {
                outcome = counts.size() > 96 || terms > 2048 ? Outcome.MODEL_LIMIT : allowance < 2048 ? Outcome.WORK_LIMIT : Outcome.BATCH_SEMANTICS;
                complete = true;
                return;
            }
            long bytes = 2048L + 512L * counts.size() + 384L * terms;
            if (!budget.tryReserve(bytes)) {
                outcome = Outcome.MEMORY_LIMIT;
                complete = true;
                return;
            }
            memory = bytes;
            long before = budget.threadWork();
            try {
                Map<K, Map<Integer, BigInteger>> consumed = new LinkedHashMap<>();
                Map<Integer, BigInteger> produced = new LinkedHashMap<>();
                low = new BigInteger[counts.size()];
                high = new BigInteger[counts.size()];
                initial = plan.initialExact();
                Arrays.fill(low, BigInteger.ZERO);
                int id = 0;
                for (var entry : counts.entrySet()) {
                    budget.check();
                    var recipe = plan.recipes().get(entry.getKey());
                    recipes.add(recipe);
                    high[id] = entry.getValue();
                    for (var input : recipe.inputs().entrySet()) {
                        budget.check();
                        consumed.computeIfAbsent(input.getKey(), unused -> new LinkedHashMap<>()).merge(id, BigInteger.valueOf(input.getValue()), BigInteger::add);
                    }
                    for (var output : recipe.outputs().entrySet()) {
                        budget.check();
                        consumed.computeIfAbsent(output.getKey(), unused -> new LinkedHashMap<>()).merge(id, BigInteger.valueOf(output.getValue()).negate(), BigInteger::add);
                    }
                    long actual = recipe.executionOutputs().getOrDefault(plan.target(), 0L);
                    if (actual > 0) produced.put(id, BigInteger.valueOf(actual));
                    id++;
                }
                // Ask if fewer target outputs could preserve this witness's target
                // gain using a submultiset and the same initial inventory. Genuine
                // surplus from indivisible productive batches is not turnover.
                // Optional coproducts are NOT goals: preserving them would make a
                // wasteful loop look mandatory merely because it also made a bonus.
                consumed.computeIfAbsent(plan.target(), unused -> new LinkedHashMap<>());
                for (K key : mandatorySeeds.keySet()) consumed.computeIfAbsent(key, unused -> new LinkedHashMap<>());
                for (var entry : consumed.entrySet()) {
                    budget.check();
                    BigInteger goal = BigInteger.valueOf(mandatorySeeds.getOrDefault(entry.getKey(), 0L));
                    if (entry.getKey().equals(plan.target()))
                        goal = goal.add(amount).max(plan.initialExact().getOrDefault(plan.target(), BigInteger.ZERO).add(net));
                    rows.add(new ExactLinearProgram.Constraint(entry.getValue(), plan.initialExact().getOrDefault(entry.getKey(), BigInteger.ZERO).subtract(goal)));
                }
                rows.add(new ExactLinearProgram.Constraint(produced, amount.subtract(BigInteger.ONE)));
                for (int i = 0; i < high.length; i++) rows.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), high[i]));
                propagating = new CountBounds(counts.size(), rows, budget);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            } finally {
                work += budget.threadWork() - before;
            }
        }
    }

    boolean step() {
        try (var costs = budget.trace(PlanningCostTrace.Stage.FORCE_CHECK)) {
            return stepMeasured();
        }
    }

    private boolean stepMeasured() {
        if (complete) return true;
        long before = budget.threadWork();
        try {
            if (work >= allowance) {
                outcome = Outcome.WORK_LIMIT;
                complete = true;
                return true;
            }
            if (propagating != null) {
                if (!propagating.step()) return false;
                if (propagating.blocked()) {
                    complete = proved = true;
                    return true;
                }
                low = propagating.lowerBounds();
                high = propagating.upperBounds();
                propagating.close();
                propagating = null;
                boolean added = false;
                for (int i = 0; i < recipes.size(); i++) if (!startup.get(i) && low[i].signum() > 0) {
                    startup.set(i);
                    // Before a recipe's FIRST firing its own returned inputs
                    // cannot fund startup. Only other recipes' positive net
                    // gains can augment the captured initial material. The
                    // row is unconditional only when propagation proved that
                    // this recipe must occur in every counterexample.
                    for (var input : recipes.get(i).inputs().entrySet()) {
                        budget.check();
                        if (work + budget.threadWork() - before >= allowance) {
                            outcome = Outcome.WORK_LIMIT;
                            complete = true;
                            return true;
                        }
                        BigInteger shortage = BigInteger.valueOf(input.getValue()).subtract(initial.getOrDefault(input.getKey(), BigInteger.ZERO));
                        if (shortage.signum() <= 0) continue;
                        Map<Integer, BigInteger> gains = new LinkedHashMap<>();
                        for (int j = 0; j < recipes.size(); j++) if (j != i) {
                            budget.check();
                            if (work + budget.threadWork() - before >= allowance) {
                                outcome = Outcome.WORK_LIMIT;
                                complete = true;
                                return true;
                            }
                            var recipe = recipes.get(j);
                            BigInteger gain = BigInteger.valueOf(recipe.outputs().getOrDefault(input.getKey(), 0L))
                                    .subtract(BigInteger.valueOf(recipe.inputs().getOrDefault(input.getKey(), 0L)));
                            if (gain.signum() > 0) gains.put(j, gain.negate());
                        }
                        long bytes = 128L + 96L * gains.size();
                        if (!budget.tryReserve(bytes)) {
                            outcome = Outcome.MEMORY_LIMIT;
                            complete = true;
                            return true;
                        }
                        memory += bytes;
                        rows.add(new ExactLinearProgram.Constraint(gains, shortage.negate()));
                        added = true;
                    }
                }
                if (added) propagating = new CountBounds(recipes.size(), rows, budget);
                else counterexample = new CountQuickSolve(rows, low, high, budget, false, false, 8192);
                return false;
            }
            if (!counterexample.step()) return false;
            // The relaxation ignores execution ordering, so infeasibility is
            // sufficient. Feasibility or a cutoff is deliberately inconclusive.
            proved = counterexample.infeasible();
            complete = true;
            return true;
        } finally {
            work += budget.threadWork() - before;
        }
    }

    boolean proved() {
        return complete && proved;
    }

    Outcome outcome() {
        if (!complete) throw new IllegalStateException("Production proof is still running");
        return proved ? Outcome.PROVED : outcome;
    }

    CandidateFeedback feedback() {
        return switch (outcome()) {
            case PROVED -> CandidateFeedback.ACCEPTED;
            case NON_PRODUCTIVE, OUTPUT_SHORTFALL -> CandidateFeedback.REJECTED;
            default -> CandidateFeedback.INCONCLUSIVE;
        };
    }

    @Override
    public void close() {
        if (propagating != null) propagating.close();
        propagating = null;
        if (counterexample != null) counterexample.close();
        counterexample = null;
        budget.release(memory);
        memory = 0;
    }
}
