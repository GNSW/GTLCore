// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One order's solve, craft-less probes, retained feasible result and bounded
 * ordinary fallback. Catalog capture and host-specific plan assembly stay outside.
 */
public final class RequestPlanningWork<K> implements PlanningScheduler.Work<GraphPlan<K>> {

    /** Caller-owned, immutable handoff for one completed request, not a global cache. */
    public static final class ReuseCandidate<K> {
        private final GraphPlan<K> plan;
        private final ReuseScope<K> scope;

        private ReuseCandidate(GraphPlan<K> plan, ReuseScope<K> scope) {
            this.plan = plan;
            this.scope = scope;
        }
    }

    private record ReuseScope<K>(K target, long amount, boolean preserve, boolean craftLess, String strategy,
                                 CatalystPolicy catalysts, boolean forceCraft, boolean replanning,
                                 boolean fallbackEnabled, boolean boundedAlternatives,
                                 Set<K> external, Map<K, Long> requiredSeeds) {}

    public record FallbackReport<K>(K target, long amount, GraphPlan.Result trigger,
                                    GraphPlan.Result result, long nodes, long elapsedNanos) {}

    /** Both accounts remain explicit: fallback spends a separate allowance after primary search stops. */
    public record CostReport(PlanningBudget.Metrics primary, PlanningCostTrace.Snapshot primaryPipeline,
                             PlanningBudget.Metrics fallback, PlanningCostTrace.Snapshot fallbackPipeline) {}

    private final GraphCompiler<K> compiler;
    private final K target;
    private final long amount;
    private final boolean craftLess;
    private final String strategy;
    private final PlanningBudget budget;
    private final GraphJobRuntime.ReplanCheckpoint<K> checkpoint;
    private final boolean preserve;
    private final boolean fallbackEnabled;
    private final boolean boundedAlternatives;
    private final BooleanSupplier cancelled;
    private final Consumer<FallbackReport<K>> fallbackReporter;
    private final Set<K> emitable;
    private final Map<K, Long> available;
    private final CatalystPolicy catalysts;
    private final boolean directEmission;
    private final boolean unavailableTarget;
    private final Map<K, Long> recoverySeeds;
    private final ReuseScope<K> requestScope;
    private CatalystPlanningWork<K> current;
    private GraphPlan<K> selected;
    private GraphPlan<K> reuseCandidate;
    private boolean reuseAttempted;
    private boolean completed;
    private boolean partialSearch, tryEstimate, tryNeighbor;
    private boolean fallbackAttempted, fallbackMode;
    private PlanningBudget.Metrics fallbackMetrics;
    private PlanningCostTrace.Snapshot fallbackPipeline;
    private long low, high, middle, estimatedAmount;

    public RequestPlanningWork(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
                               Set<K> emitable, GraphJobRuntime.ReplanCheckpoint<K> checkpoint,
                               boolean preserve, boolean craftLess, String strategy, CatalystPolicy catalysts,
                               boolean fallbackEnabled, boolean boundedAlternatives, PlanningBudget budget,
                               BooleanSupplier cancelled, Consumer<FallbackReport<K>> fallbackReporter) {
        this.compiler = compiler;
        this.target = target;
        this.amount = amount;
        this.emitable = Set.copyOf(emitable);
        this.checkpoint = checkpoint;
        this.preserve = preserve;
        this.craftLess = craftLess;
        this.strategy = strategy;
        this.catalysts = catalysts;
        this.fallbackEnabled = fallbackEnabled;
        this.boundedAlternatives = boundedAlternatives;
        this.budget = budget;
        this.cancelled = cancelled;
        this.fallbackReporter = fallbackReporter;
        available = PlanningInventory.availability(stock, checkpoint == null ? Map.of() : checkpoint.forecast());
        directEmission = emitable.contains(target) && compiler.producers(target).isEmpty();
        unavailableTarget = checkpoint == null && !emitable.contains(target) && compiler.producers(target).isEmpty();
        recoverySeeds = checkpoint == null ? Map.of() : GraphRecipe.amounts(checkpoint.recoverySeeds());
        requestScope = new ReuseScope<>(target, amount, preserve, craftLess, strategy, catalysts,
                checkpoint == null && !directEmission, checkpoint != null, fallbackEnabled, boundedAlternatives,
                this.emitable, recoverySeeds);
    }

    /** Availability used for extraction accounting after planning. */
    public Map<K, Long> availability() {
        return available;
    }

    /** An explicit witness for this refresh; every current obligation is revalidated. */
    public RequestPlanningWork<K> reuseCandidate(GraphPlan<K> previous) {
        if (current != null || selected != null) throw new IllegalStateException("Planning already started");
        reuseCandidate = previous;
        return this;
    }

    /** Use a prior request only when all captured request policies still match. */
    public RequestPlanningWork<K> reuseCandidate(ReuseCandidate<K> previous) {
        if (current != null || selected != null) throw new IllegalStateException("Planning already started");
        reuseCandidate = previous != null && previous.scope.equals(reuseScope()) ? previous.plan : null;
        if (previous != null && reuseCandidate == null) budget.note("plan_reuse", "declined; reason=request_configuration");
        return this;
    }

    /** No handle is exported for failed, incomplete or reduced-amount requests. */
    public ReuseCandidate<K> reuseCandidate() {
        return completed && selected != null && selected.feasible() && selected.amount() == amount ?
                new ReuseCandidate<>(selected, reuseScope()) : null;
    }

    private ReuseScope<K> reuseScope() {
        return requestScope;
    }

    public boolean fallbackMode() {
        return fallbackMode;
    }

    /** Fallback fields are null until an attempted fallback finishes (including a budget exit). */
    public CostReport costs() {
        return new CostReport(budget.metrics(), budget.pipelineMetrics(), fallbackMetrics, fallbackPipeline);
    }

    @Override
    public boolean advance(PlanningScheduler.Slice slice) {
        if (current == null) {
            if (directEmission && checkpoint == null) available.remove(target);
            current = calculation(amount);
        }
        if (!current.advance(slice)) return false;
        GraphPlan<K> candidate = current.result();
        if (!partialSearch) {
            selected = candidate;
            if (selected.feasible() || unavailableTarget || !craftLess || selected.missing().isEmpty()) return finish();
            partialSearch = true;
            low = 1;
            high = amount - 1;
            estimatedAmount = estimateAmount(candidate);
            tryEstimate = estimatedAmount > 1;
            middle = 1;
        } else {
            if (candidate.feasible()) {
                selected = candidate;
                low = middle + 1;
            } else if (candidate.missing().isEmpty()) {
                budget.note("craft_less", "undecided_probe=" + middle + "; retained_amount=" + selected.amount());
                return finishReduced();
            } else high = middle - 1;
            if (tryEstimate && estimatedAmount >= low && estimatedAmount <= high) {
                middle = estimatedAmount;
                tryNeighbor = true;
            } else if (tryNeighbor && candidate.feasible()) {
                middle = low;
                tryNeighbor = false;
            } else {
                middle = low + (high - low) / 2;
                tryNeighbor = false;
            }
            tryEstimate = false;
        }
        if (low > high) return finish();
        current.close();
        current = calculation(middle);
        return false;
    }

    private CatalystPlanningWork<K> calculation(long count) {
        budget.note("request", "target=" + target + "; amount=" + count + "; strategy=" + strategy + "; preserve_seeds=" + preserve);
        // Feasibility probes share the order budget and must not each repeat
        // the optional catalyst acceleration search.
        return new CatalystPlanningWork<>(checkpoint != null || craftLess ? CatalystPolicy.MINIMAL : catalysts, budget,
                policy -> {
                    var work = new GraphPlanningWork<>(compiler, target, count, available, emitable,
                            recoverySeeds, preserve,
                            checkpoint == null && !directEmission, budget).catalysts(policy);
                    // A rejection consumes the proposal only, never an ordinary
                    // search attempt or the remainder of craft-less probes.
                    if (!reuseAttempted) {
                        reuseAttempted = true;
                        work.reuseCandidate(reuseCandidate);
                        reuseCandidate = null;
                    }
                    return work;
                });
    }

    private long estimateAmount(GraphPlan<K> full) {
        java.math.BigInteger guess = java.math.BigInteger.valueOf(amount);
        for (var entry : full.initialExact().entrySet()) {
            if (emitable.contains(entry.getKey()) || entry.getValue().signum() == 0) continue;
            java.math.BigInteger scaled = java.math.BigInteger.valueOf(amount)
                    .multiply(java.math.BigInteger.valueOf(available.getOrDefault(entry.getKey(), 0L)))
                    .divide(entry.getValue());
            guess = guess.min(scaled);
        }
        // A probe hint, never an upper-bound proof: batches, alternative
        // sources and startup costs need not scale with order quantity.
        return guess.max(java.math.BigInteger.ONE).longValueExact();
    }

    private boolean finishReduced() {
        if (selected.feasible()) selected = new GraphPlan<>(selected.target(), selected.amount(), selected.preserveSeeds(), selected.steps(),
                selected.recipes(), selected.initialExact(), selected.seeds(), Map.of(), GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL,
                budget.nodes(), selected.planningNanos());
        return finish();
    }

    private RuntimeException limitOrUnknown(GraphPlan<K> plan) {
        return switch (plan.result()) {
            case TIMEOUT, SEARCH_LIMIT, MEMORY_LIMIT, GRAPH_LIMIT, QUEUE_LIMIT -> new PlanningBudget.Exhausted(PlanningBudget.Limit.valueOf(plan.result().name()), budget.failureDetail());
            default -> new PlanningFailure(plan.result(), budget.failureDetail());
        };
    }

    private boolean finish() {
        if (!selected.feasible() && selected.missing().isEmpty() && !fallback(selected.result())) throw limitOrUnknown(selected);
        if (!selected.feasible() && boundedAlternatives && !fallbackMode && !fallback(GraphPlan.Result.SEARCH_LIMIT))
            throw budget.exhausted(PlanningBudget.Limit.SEARCH_LIMIT, "input_alternatives_bounded; missing preview is not a proof for omitted alternatives");
        completed = true;
        return true;
    }

    /** One bounded ordinary expansion, never another cycle solver or a legacy engine request. */
    private boolean fallback(GraphPlan.Result reason) {
        if (!fallbackEnabled || fallbackAttempted || cancelled.getAsBoolean()) return false;
        if (!switch (reason) {
            case UNKNOWN, INFEASIBLE, TIMEOUT, SEARCH_LIMIT, MEMORY_LIMIT, GRAPH_LIMIT -> true;
            default -> false;
        }) return false;
        fallbackAttempted = true;
        // Stop count-search siblings before releasing their state. Fallback has
        // its own small allowance, not a refund of spent work.
        budget.cancel();
        if (current != null) {
            current.close();
            current = null;
        }
        PlanningBudget quick = new PlanningBudget(250, 131_072, 16L << 20, cancelled, System::nanoTime);
        if (budget.metricsEnabled()) quick.enableMetrics();
        try {
            try (var costs = quick.trace(PlanningCostTrace.Origin.FALLBACK, PlanningCostTrace.Stage.SEARCH)) {
                selected = GraphFallback.plan(compiler, target, amount, available, emitable,
                        recoverySeeds, preserve, checkpoint == null && !directEmission, quick);
            }
            fallbackMode = true;
            budget.note("fallback", "cycle_solving=false; reason=" + reason + "; work=" + quick.nodes() + "; result=" + selected.result());
            fallbackReporter.accept(new FallbackReport<>(target, amount, reason, selected.result(), quick.nodes(), quick.elapsedNanos()));
            return true;
        } catch (PlanningBudget.Exhausted exhausted) {
            budget.note("fallback", "cycle_solving=false; trigger=" + reason + "; stopped=" + exhausted.limit() + "; work=" + quick.nodes());
            return false;
        } finally {
            fallbackMetrics = quick.metrics();
            fallbackPipeline = quick.pipelineMetrics();
        }
    }

    @Override
    public void close() {
        if (current != null) current.close();
    }

    @Override
    public CompletableFuture<?> waitingFor() {
        return current == null ? null : current.waitingFor();
    }

    @Override
    public GraphPlan<K> result() {
        return selected;
    }

    @Override
    public GraphPlan<K> limited(PlanningBudget.Exhausted limit) {
        if (current != null) {
            GraphPlan<K> retained = current.limited(limit);
            if (retained.feasible()) selected = retained;
        }
        if (selected == null || !selected.feasible() && (!partialSearch || selected.missingExact().isEmpty())) {
            if (!fallback(GraphPlan.Result.valueOf(limit.limit().name()))) throw limit;
            finish();
            return selected;
        }
        budget.note("craft_less", "limit=" + limit.limit() + "; retained_amount=" + selected.amount() + "; result=" + selected.result());
        finishReduced();
        return selected;
    }
}
