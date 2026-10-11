// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** An explicit old execution witness is a proposal, never a cached conclusion. */
final class PlanReuseVerification<K> implements AutoCloseable {

    private final GraphPlan<K> proposed;
    private final GraphCompiler<K> compiler;
    private final K target;
    private final long amount;
    private final Map<K, Long> stock, requiredSeeds;
    private final Set<K> external;
    private final boolean preserve, forceCraft;
    private final PlanningBudget budget;
    private final long started;
    private final Set<String> matched = new HashSet<>();
    private Iterator<Map.Entry<K, BigInteger>> inputs;
    private Iterator<Map.Entry<K, Long>> seeds;
    private Iterator<GraphRecipe<K>> catalog;
    private PlanVerification<K> verification;
    private ForceCraftProof<K> production;
    private long memory;
    private int phase;
    private boolean complete;
    private GraphPlan<K> result;
    private PlanningBudget.CandidateObservation observation;

    PlanReuseVerification(GraphPlan<K> proposed, GraphCompiler<K> compiler, K target, long amount,
                          Map<K, Long> stock, Set<K> external, Map<K, Long> requiredSeeds,
                          boolean preserve, boolean forceCraft, PlanningBudget budget, long started) {
        this.proposed = proposed;
        this.compiler = compiler;
        this.target = target;
        this.amount = amount;
        this.stock = stock;
        this.external = external;
        this.requiredSeeds = requiredSeeds;
        this.preserve = preserve;
        this.forceCraft = forceCraft;
        this.budget = budget;
        this.started = started;
    }

    boolean step() {
        if (phase == 0 && !complete && observation == null) observation = budget.observeCandidate(PlanningBudget.CandidateOrigin.PLAN_REUSE);
        var observing = observation;
        long work = observing == null ? 0 : budget.threadWork();
        long nanos = observing == null ? 0 : System.nanoTime();
        try (var costs = budget.trace(PlanningCostTrace.Origin.PLAN_REUSE, PlanningCostTrace.Stage.VERIFY)) {
            budget.check();
            budget.phase(PlanningBudget.Phase.VERIFY);
            if (complete) return true;
            switch (phase) {
                case 0 -> {
                    if (!proposed.feasible() || !proposed.missingExact().isEmpty() ||
                            !target.equals(proposed.target()) || amount != proposed.amount() ||
                            preserve != proposed.preserveSeeds()) return reject("request_semantics");
                    // The witness belongs to the caller. Account for our matching
                    // workspace and the copied result maps, not another search cache.
                    long bytes = 512L + 128L * proposed.recipes().size() +
                            192L * (proposed.initialExact().size() + (long) proposed.seeds().size());
                    if (!budget.tryReserve(bytes)) return reject("memory_admission");
                    memory = bytes;
                    inputs = proposed.initialExact().entrySet().iterator();
                    seeds = requiredSeeds.entrySet().iterator();
                    phase = 1;
                }
                case 1 -> {
                    if (inputs.hasNext()) {
                        var input = inputs.next();
                        if (!external.contains(input.getKey()) && input.getValue().compareTo(
                                BigInteger.valueOf(stock.getOrDefault(input.getKey(), 0L))) > 0)
                            return reject("stock");
                    } else phase = 2;
                }
                case 2 -> {
                    if (seeds.hasNext()) {
                        var seed = seeds.next();
                        if (proposed.seeds().getOrDefault(seed.getKey(), 0L) < seed.getValue())
                            return reject("required_seeds");
                    } else {
                        catalog = compiler.catalog().iterator();
                        phase = 3;
                    }
                }
                case 3 -> {
                    // Scan the immutable current catalog once; equal IDs alone
                    // do not certify bindings, slots, configuration or returns.
                    for (int i = 0; i < 32 && catalog.hasNext(); i++) {
                        budget.check();
                        var recipe = catalog.next();
                        var previous = proposed.recipes().get(recipe.id());
                        if (previous == null) continue;
                        if (!previous.equals(recipe)) return reject("recipe_semantics");
                        matched.add(recipe.id());
                    }
                    if (catalog.hasNext()) return false;
                    if (matched.size() != proposed.recipes().size()) return reject("recipe_removed");
                    verification = new PlanVerification<>(proposed, budget);
                    phase = 4;
                }
                case 4 -> {
                    if (!verification.step()) return false;
                    if (forceCraft && !external.contains(target)) {
                        production = new ForceCraftProof<>(proposed, verification, requiredSeeds, budget);
                        phase = 5;
                    } else return accept();
                }
                case 5 -> {
                    if (!production.step()) return false;
                    if (!production.proved()) return reject("force_craft_" + production.outcome());
                    return accept();
                }
                default -> throw new IllegalStateException("Invalid reuse phase");
            }
            return false;
        } catch (IllegalArgumentException invalid) {
            // A supplied witness may be malformed or no longer executable.
            // Reject only this candidate, never learn a stock-dependent failure.
            return reject("invalid_witness");
        } catch (PlanningBudget.Exhausted limit) {
            // Optional verification can release its workspace and leave normal
            // search eligible. Time/work exhaustion and cancellation stay final.
            if (limit.limit() == PlanningBudget.Limit.MEMORY_LIMIT) return reject("memory_limit");
            close();
            throw limit;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        } finally {
            if (observing != null) observing.step(work, nanos);
        }
    }

    private boolean accept() {
        result = new GraphPlan<>(target, amount, preserve, proposed.steps(), proposed.recipes(),
                proposed.initialExact(), proposed.seeds(), Map.of(), GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL,
                budget.nodes(), System.nanoTime() - started);
        budget.note("plan_reuse", "accepted; recipes=" + proposed.recipes().size() +
                "; initial_keys=" + proposed.initialExact().size());
        complete = true;
        if (observation != null) observation.finish(PlanningBudget.CandidateOutcome.ACCEPTED);
        close();
        return true;
    }

    private boolean reject(String reason) {
        budget.note("plan_reuse", "declined; reason=" + reason);
        complete = true;
        if (observation != null) observation.finish(reason.startsWith("memory") ?
                PlanningBudget.CandidateOutcome.INCONCLUSIVE : PlanningBudget.CandidateOutcome.REJECTED);
        close();
        return true;
    }

    GraphPlan<K> result() { return result; }

    @Override
    public void close() {
        if (observation != null) observation.finish(PlanningBudget.CandidateOutcome.ABANDONED);
        if (verification != null) verification.close();
        verification = null;
        if (production != null) production.close();
        production = null;
        matched.clear();
        catalog = null;
        inputs = null;
        seeds = null;
        budget.release(memory);
        memory = 0;
    }
}
