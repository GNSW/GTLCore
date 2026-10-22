// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small ordinary witness probe, then complete backward requirements. Its
 * shared refill never invokes cyclic search. The remaining ordinary portfolio
 * runs only after retaining a complete, verified supply boundary.
 * Missing inputs describe this witness only, not infeasibility of the catalog.
 */
public final class GraphFallback {

    private GraphFallback() {}

    public static <K> GraphPlan<K> plan(GraphCompiler<K> compiler, K target, long amount,
                                        Map<K, Long> stock, Set<K> external, Map<K, Long> seeds,
                                        boolean preserve, boolean forceCraft, PlanningBudget budget) {
        GraphPlan<K> candidate;
        try (var work = new Expansion<>(compiler, target, amount, stock, external, seeds, preserve, forceCraft, budget)) {
            work.quickProbe = true;
            while (!work.step()) { /* Search and verification use actual inventory only. */ }
            candidate = work.result;
        }
        if (candidate != null) return candidate;
        return refillGoals(compiler, target, amount, stock, external, seeds, preserve, forceCraft, budget);
    }

    /** No executable word was found. The same backward constructor as main
     * planning now owns every missing boundary, with no forward loans. */
    private static <K> GraphPlan<K> refillGoals(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
                                               Set<K> external, Map<K, Long> seeds, boolean preserve, boolean force,
                                               PlanningBudget budget) {
        budget.note("fallback_refill", "compact_goals; backward_requirements");
        try (var expansion = MissingMaterialExpansion.fromRequest(compiler, target, amount, stock, external,
                seeds, Set.of(), preserve, force, budget).ordinaryFallback()) {
            String previousFailure = budget.failureDetail();
            try {
                while (!expansion.step()) { /* Shared backward requirements; no truncated forward loans. */ }
            } catch (PlanningBudget.Exhausted limit) {
                // result() is published only after full expansion and witness
                // verification. A cutoff during construction must still fail.
                if (expansion.result() == null) throw limit;
                budget.failureDetail(previousFailure);
                budget.note("fallback_refill", "verified_bill_retained; optional_refill_limit=" + limit.limit());
            }
            var candidate = expansion.result();
            if (candidate.feasible() || budget.remainingWork() < 4096) return candidate;
            // Keep the builder and its accounting alive while another search
            // borrows this bill. Failed optional work cannot remove its result.
            try (var work = new Expansion<>(compiler, target, amount, stock, external, seeds, preserve, force, budget)) {
                while (!work.step()) { /* Original ordinary source portfolio; no native cycle search. */ }
                if (work.result != null) return work.result;
            } catch (PlanningBudget.Exhausted limit) {
                budget.failureDetail(previousFailure);
                budget.note("fallback_refill", "verified_bill_retained; optional_source_limit=" + limit.limit());
            }
            return candidate;
        }
    }

    /** A bounded positive proposal. Failure never produces a missing-material claim. */
    static <K> GraphPlan<K> witnessByCost(GraphCompiler<K> compiler, K target, long amount,
                                          Map<K, Long> stock, Set<K> external, Map<K, Long> seeds,
                                          boolean preserve, boolean forceCraft, PlanningBudget budget, long maximumWork) {
        budget.checkpoint();
        if (maximumWork <= 0) return null;
        long start = budget.nodes();
        String previousFailure = budget.failureDetail();
        var previousPhase = budget.phase();
        try (var work = new Expansion<>(compiler, target, amount, stock, external, seeds, preserve, forceCraft, budget)) {
            work.witnessAllowance = Math.min(maximumWork, budget.remainingWork());
            if (work.witnessAllowance == 0) budget.check();
            while (budget.nodes() - start < maximumWork) {
                if (work.step()) return work.result != null && work.result.feasible() ? work.result : null;
            }
            return null;
        } catch (PlanningBudget.Exhausted failure) {
            if (failure.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw failure;
            // An optional witness must not abort the retained search just
            // because its independent workspace or final summary did not fit.
            budget.failureDetail(previousFailure);
            budget.note("source_witness", "workspace_declined; original_frontier_retained");
            return null;
        } finally {
            budget.phase(previousPhase);
        }
    }

    /** Positive witness construction only; a failed search publishes no bill. */
    private static final class Expansion<K> implements AutoCloseable {
        private final GraphCompiler<K> compiler;
        private final K target;
        private final long amount, started = System.nanoTime();
        private final Map<K, Long> stock, seeds;
        private final Set<K> external;
        private final boolean preserve, forceCraft;
        private final PlanningBudget budget;
        private final Map<String, GraphRecipe<K>> recipes = new LinkedHashMap<>();
        private final List<PlanStep> steps = new ArrayList<>();
        private final Map<K, BigInteger> initial = new LinkedHashMap<>();
        private SummaryComputation<K> summary;
        private PlanVerification<K> verification;
        private PlanStep program;
        private Iterator<K> keys;
        private GraphPlan<K> candidate, result;
        private long memory, witnessAllowance;
        private boolean searched, quickProbe;

        Expansion(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock, Set<K> external,
                  Map<K, Long> seeds, boolean preserve, boolean forceCraft, PlanningBudget budget) {
            if (amount <= 0) throw new IllegalArgumentException("Non-positive crafting request");
            this.compiler = compiler; this.target = target; this.amount = amount; this.stock = stock;
            this.external = external; this.seeds = seeds; this.preserve = preserve; this.forceCraft = forceCraft; this.budget = budget;
        }

        private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }

        private boolean step() {
            budget.check();
            if (!searched) {
                searched = true;
                try (var search = new GraphFallbackSearch<>(compiler, target, amount, stock, external, seeds, forceCraft, budget)) {
                    if (!(witnessAllowance > 0 ? search.findByCost(witnessAllowance) : quickProbe ?
                            search.findPriority(Math.min(4096, budget.remainingWork() / 16)) : search.find())) return true;
                    reserve(256L * search.size()); search.copyTo(steps, recipes);
                }
                program = new PlanStep.Sequence(steps);
                summary = new SummaryComputation<>(program, recipes, budget);
                return false;
            }
            if (result != null) return true;
            if (verification != null) {
                if (verification.step()) result = candidate;
                return result != null;
            }
            if (keys == null) {
                if (!summary.step()) return false;
                var all = new java.util.LinkedHashSet<>(summary.result().keys()); all.add(target); all.addAll(seeds.keySet());
                keys = all.iterator();
            }
            if (keys.hasNext()) {
                K key = keys.next();
                BigInteger goal = BigInteger.valueOf(seeds.getOrDefault(key, 0L));
                if (target.equals(key)) goal = goal.add(BigInteger.valueOf(amount));
                BigInteger need = summary.result().required(key).max(goal.subtract(summary.result().delta(key)));
                if (need.signum() > 0) {
                    BigInteger have = forceCraft && target.equals(key) ? BigInteger.ZERO : PlanningInventory.exactAmount(stock, key);
                    if (!external.contains(key) && need.compareTo(have) > 0) return true;
                    reserve(192); initial.put(key, need);
                }
                return false;
            }
            candidate = new GraphPlan<>(target, amount, preserve, program, recipes, initial, seeds, Map.of(),
                    GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, budget.nodes(), System.nanoTime() - started);
            verification = new PlanVerification<>(candidate, budget);
            return false;
        }

        @Override public void close() {
            if (summary != null) summary.close();
            if (verification != null) verification.close();
            budget.release(memory); memory = 0;
        }
    }
}
