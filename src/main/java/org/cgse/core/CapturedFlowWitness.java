// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reuse an auxiliary count assignment through the canonical scheduling and validation tools. */
final class CapturedFlowWitness<K> implements AutoCloseable {

    private final K target;
    private final long amount, started = System.nanoTime();
    private final Map<K, Long> stock, seeds;
    private final Set<K> external;
    private final boolean preserve, force;
    private final PlanningBudget budget;
    private final Map<String, GraphRecipe<K>> recipes = new LinkedHashMap<>();
    private final long searchStarted, allowance;
    private RecipeCountModel<K> model;
    private CountSchedule<K> schedule;
    private AllocationSearch.Candidate<K> assembly;
    private PlanVerification<K> verification;
    private ForceCraftProof<K> production;
    private PlanningBudget.CandidateObservation observation;
    private GraphPlan<K> result;
    private long memory;
    private boolean done;

    CapturedFlowWitness(List<GraphRecipe<K>> recipes, Map<String, BigInteger> counts, K target, long amount,
                        Map<K, Long> stock, Set<K> external, Map<K, Long> seeds, boolean preserve, boolean force,
                        PlanningBudget budget) {
        this.target = target; this.amount = amount; this.stock = stock; this.external = external;
        this.seeds = seeds; this.preserve = preserve; this.force = force; this.budget = budget;
        searchStarted = budget.searchWork(); allowance = Math.min(32768, budget.remainingWork() / 4);
        long bytes = 512L + 160L * (recipes.size() + seeds.size());
        if (allowance < 1024 || !budget.tryReserve(bytes)) { done = true; return; }
        memory = bytes;
        try (var costs = budget.trace(PlanningCostTrace.Origin.ALLOCATION, PlanningCostTrace.Stage.PREPARE)) {
            Map<K, BigInteger> goals = new LinkedHashMap<>();
            seeds.forEach((key, value) -> { budget.compilationCheck(); goals.put(key, BigInteger.valueOf(value)); });
            goals.merge(target, BigInteger.valueOf(amount), BigInteger::add);
            model = RecipeCountModel.forShell(recipes, goals, stock, external, budget);
            if (model == null) { done = true; return; }
            BigInteger[] values = new BigInteger[recipes.size()];
            for (int i = 0; i < recipes.size(); i++) {
                budget.compilationCheck(); var recipe = recipes.get(i);
                this.recipes.put(recipe.id(), recipe); values[i] = counts.getOrDefault(recipe.id(), BigInteger.ZERO);
            }
            schedule = new CountSchedule<>(model, values, budget).retainWitnessForAssembly();
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    boolean step() {
        var observed = observation;
        long work = observed == null ? 0 : budget.threadWork(), nanos = observed == null ? 0 : System.nanoTime();
        try (var costs = budget.trace(PlanningCostTrace.Origin.ALLOCATION, PlanningCostTrace.Stage.OTHER)) {
            return stepMeasured();
        } finally { if (observed != null) observed.step(work, nanos); }
    }

    private boolean stepMeasured() {
        if (done) return true;
        budget.phase(assembly == null ? PlanningBudget.Phase.SOLVE : PlanningBudget.Phase.VERIFY);
        if (budget.searchWork() - searchStarted >= allowance) {
            finishObservation(PlanningBudget.CandidateOutcome.INCONCLUSIVE); done = true; return true;
        }
        if (verification != null) {
            if (!verification.step()) return false;
            if (force) {
                if (production == null) production = new ForceCraftProof<>(assembly.plan, verification, seeds, budget);
                if (!production.step()) return false;
                if (!production.proved()) { rejectOrder(); return done; }
            }
            result = assembly.plan; done = true;
            finishObservation(PlanningBudget.CandidateOutcome.ACCEPTED);
            budget.note("candidate_flow", "verified_execution; recipes=" + recipes.size() + "; work=" + (budget.searchWork() - searchStarted));
        } else if (assembly != null) {
            if (!assembly.step()) return false;
            if (assembly.plan == null) rejectOrder();
            else verification = new PlanVerification<>(assembly.plan, budget);
        } else if (schedule.step()) {
            if (schedule.witness() == null) done = true;
            else {
                observation = budget.observeCandidate(PlanningBudget.CandidateOrigin.ALLOCATION);
                long work = budget.threadWork(), nanos = System.nanoTime();
                try {
                    assembly = new AllocationSearch.Candidate<>(schedule.witness(), recipes, target, amount, stock,
                            seeds, external, preserve, force, false, budget, started);
                } finally { if (observation != null) observation.step(work, nanos); }
            }
        }
        return done;
    }

    private void rejectOrder() {
        finishObservation(PlanningBudget.CandidateOutcome.REJECTED);
        if (production != null) production.close(); production = null;
        if (verification != null) verification.close(); verification = null;
        assembly.close(); assembly = null;
        done = !schedule.retryAfterRejectedWitness();
    }

    GraphPlan<K> result() { return result; }

    private void finishObservation(PlanningBudget.CandidateOutcome outcome) {
        if (observation != null) observation.finish(outcome);
        observation = null;
    }

    @Override public void close() {
        finishObservation(PlanningBudget.CandidateOutcome.ABANDONED);
        if (production != null) production.close(); production = null;
        if (verification != null) verification.close(); verification = null;
        if (assembly != null) assembly.close(); assembly = null;
        if (schedule != null) schedule.close(); schedule = null;
        if (model != null) model.close(); model = null;
        budget.release(memory); memory = 0; done = true; recipes.clear();
    }
}
