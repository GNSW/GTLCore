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

    /** Ordinary fallback has its own budget, with no worker multiplier or refund of primary work. */
    public record FallbackLimits(long milliseconds, long maxWork, long maxBytes) {
        public static final FallbackLimits DEFAULT = new FallbackLimits(250, 131_072, 16L << 20);

        public FallbackLimits {
            if (milliseconds < 0 || milliseconds > Long.MAX_VALUE / 1_000_000L || maxWork <= 0 || maxBytes <= 0)
                throw new IllegalArgumentException("Invalid fallback limits");
        }
    }

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
    private Map<K, Long> available;
    private final CatalystPolicy catalysts;
    private boolean directEmission;
    private boolean unavailableTarget;
    private final long capturedTargetStock;
    private final Map<K, Long> recoverySeeds;
    private ReuseScope<K> requestScope;
    private CapturedCatalog<K> capturedCatalog;
    private PlanningScheduler.Work<GraphPlan<K>> current;
    private GraphPlan<K> selected;
    private boolean currentIncomplete, selectedIncomplete;
    private GraphPlan<K> reuseCandidate;
    private boolean reuseAttempted;
    private boolean completed;
    private boolean partialSearch, tryEstimate, tryNeighbor;
    private boolean fallbackAttempted, fallbackMode;
    private FallbackLimits fallbackLimits = FallbackLimits.DEFAULT;
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
        currentIncomplete = selectedIncomplete = boundedAlternatives;
        this.budget = budget;
        this.cancelled = cancelled;
        this.fallbackReporter = fallbackReporter;
        directEmission = this.emitable.contains(target) && compiler.producers(target).isEmpty();
        unavailableTarget = checkpoint == null && !this.emitable.contains(target) && compiler.producers(target).isEmpty();
        capturedTargetStock = stock.getOrDefault(target, 0L);
        available = PlanningInventory.availability(stock, checkpoint == null ? Map.of() : checkpoint.forecast(),
                directEmission && checkpoint == null ? target : null);
        recoverySeeds = PlanningInventory.snapshot(checkpoint == null ? Map.of() : checkpoint.recoverySeeds());
        updateReuseScope();
    }

    private void updateReuseScope() {
        requestScope = new ReuseScope<>(target, amount, preserve, craftLess, strategy, catalysts,
                checkpoint == null && !directEmission, checkpoint != null, fallbackEnabled, boundedAlternatives,
                this.emitable, recoverySeeds);
    }

    /** Configure before submitting the request; active work keeps its captured limits. */
    public RequestPlanningWork<K> fallbackLimits(FallbackLimits limits) {
        if (current != null || selected != null || reuseAttempted || fallbackAttempted)
            throw new IllegalStateException("Planning already started");
        fallbackLimits = java.util.Objects.requireNonNull(limits);
        return this;
    }

    /**
     * Retain the whole captured candidate domain, not only the compiler's first
     * concrete window. Configure before submitting this request. The compiler
     * remains available for explicitly marked ordinary fallback previews.
     */
    public RequestPlanningWork<K> capturedCatalog(CapturedCatalog<K> catalog) {
        if (current != null || selected != null || reuseAttempted) throw new IllegalStateException("Planning already started");
        capturedCatalog = java.util.Objects.requireNonNull(catalog);
        boolean produced = catalog.hasPotentialProducer(target);
        boolean emittedBefore = directEmission;
        directEmission = emitable.contains(target) && !produced;
        unavailableTarget = checkpoint == null && !emitable.contains(target) && !produced;
        if (checkpoint == null && emittedBefore != directEmission) {
            var updated = new java.util.LinkedHashMap<>(available);
            if (directEmission || capturedTargetStock <= 0) updated.remove(target);
            else updated.put(target, capturedTargetStock);
            available = Map.copyOf(updated);
        }
        updateReuseScope();
        return this;
    }

    /** Immutable captured availability used for extraction accounting after planning. */
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
            current = calculation(amount);
        }
        if (!current.advance(slice)) return false;
        GraphPlan<K> candidate = current.result();
        if (!partialSearch) {
            selected = candidate;
            selectedIncomplete = currentIncomplete;
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
                selectedIncomplete = currentIncomplete;
                low = middle + 1;
            } else if (candidate.missing().isEmpty() || candidate.conditionalPreview()) {
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

    private PlanningScheduler.Work<GraphPlan<K>> calculation(long count) {
        budget.note("request", "target=" + target + "; amount=" + count + "; strategy=" + strategy + "; preserve_seeds=" + preserve);
        currentIncomplete = boundedAlternatives;
        CatalystPolicy policy = checkpoint != null || craftLess ? CatalystPolicy.MINIMAL : catalysts;
        if (capturedCatalog != null) {
            // The concrete compiler's identities/coverage cannot establish a
            // witness in a different candidate domain. Scoped catalog reuse can
            // be added separately; ordinary requests retain their existing reuse.
            if (reuseCandidate != null) budget.note("plan_reuse", "declined; reason=captured_domain");
            reuseAttempted = true;
            reuseCandidate = null;
            var captured = capturedCatalog.begin(target, count, available, emitable, recoverySeeds,
                    preserve, checkpoint == null && !directEmission, budget).catalysts(policy).conditionalPreviews(true);
            return new PlanningScheduler.Work<>() {
                @Override public boolean advance(PlanningScheduler.Slice slice) { return captured.advance(slice); }
                @Override public GraphPlan<K> result() { return consume(captured.result()); }
                @Override public GraphPlan<K> limited(PlanningBudget.Exhausted limit) { return consume(captured.limited(limit)); }
                @Override public CompletableFuture<?> waitingFor() { return captured.waitingFor(); }
                @Override public void close() { captured.close(); }
                private GraphPlan<K> consume(CapturedCatalog.Result<K> result) {
                    currentIncomplete = !result.coverage().complete();
                    return result.plan();
                }
            };
        }
        // Feasibility probes share the order budget and must not each repeat
        // the optional catalyst acceleration search.
        return new CatalystPlanningWork<>(policy, budget,
                selectedPolicy -> {
                    var work = new GraphPlanningWork<>(compiler, target, count, available, emitable,
                            recoverySeeds, preserve,
                            checkpoint == null && !directEmission, budget).catalysts(selectedPolicy);
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

    private RuntimeException limitOrUnknown(GraphPlan.Result reason) {
        return switch (reason) {
            case TIMEOUT, SEARCH_LIMIT, MEMORY_LIMIT, GRAPH_LIMIT, QUEUE_LIMIT -> new PlanningBudget.Exhausted(PlanningBudget.Limit.valueOf(reason.name()), budget.failureDetail());
            default -> new PlanningFailure(reason, budget.failureDetail());
        };
    }

    private GraphPlan.Result failureReason() {
        GraphPlan.Result reason = selected.result();
        if (selectedIncomplete && switch (reason) {
            case INFEASIBLE, MISSING_INPUT, MISSING_SEED, UNKNOWN -> true;
            default -> false;
        }) {
            budget.failureDetail("input_alternatives_bounded; captured_result=" + reason +
                    "; omitted alternatives have not been disproved");
            return GraphPlan.Result.INCOMPLETE_MODEL;
        }
        return reason;
    }

    private boolean finish() {
        if (!selected.feasible() && !selected.conditionalPreview() && (selected.missingExact().isEmpty() || selectedIncomplete && !fallbackMode)) {
            // Coverage applies before every negative exit, including a completed
            // child proof without a refill preview. Exhausted search and omitted
            // alternatives are separate reasons, not interchangeable budgets.
            GraphPlan.Result reason = failureReason();
            if (!fallback(reason)) throw limitOrUnknown(reason);
        }
        if (selected.feasible() && selectedIncomplete) {
            // A restricted positive witness is valid, but its seed/material
            // optimality cannot cover routes absent from the captured model.
            selected = new GraphPlan<>(selected.target(), selected.amount(), selected.preserveSeeds(), selected.steps(),
                    selected.recipes(), selected.initialExact(), selected.seeds(), selected.missingExact(),
                    GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, selected.searchNodes(), selected.planningNanos())
                    .withAlternatives(selected.alternatives());
        }
        completed = true;
        return true;
    }

    /** One bounded ordinary expansion, never another cycle solver or a legacy engine request. */
    private boolean fallback(GraphPlan.Result reason) {
        if (!fallbackEnabled || fallbackAttempted || cancelled.getAsBoolean()) return false;
        if (!switch (reason) {
            case UNKNOWN, INFEASIBLE, INCOMPLETE_MODEL, TIMEOUT, SEARCH_LIMIT, MEMORY_LIMIT, GRAPH_LIMIT -> true;
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
        PlanningBudget quick = new PlanningBudget(fallbackLimits.milliseconds(), fallbackLimits.maxWork(),
                fallbackLimits.maxBytes(), cancelled, System::nanoTime).shareMemory(budget);
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
            if (retained.feasible()) {
                selected = retained;
                selectedIncomplete = currentIncomplete;
            } else if (verifiedRefill(retained, currentIncomplete) &&
                    (selected == null || !selected.feasible() && selected.missingExact().isEmpty())) {
                selected = retained;
                selectedIncomplete = currentIncomplete;
            } else if (capturedCatalog != null && retained.result() == GraphPlan.Result.INFEASIBLE && !currentIncomplete &&
                    (selected == null || !selected.feasible() && selected.missingExact().isEmpty())) {
                // Catalog work has already certified that this is a complete
                // original-domain proof, not a restricted allocation/prefix.
                selected = retained;
                selectedIncomplete = false;
                finish();
                return selected;
            }
        }
        if (selected == null || !selected.feasible() && !verifiedRefill(selected, selectedIncomplete) &&
                (!partialSearch || selected.missingExact().isEmpty())) {
            if (!fallback(GraphPlan.Result.valueOf(limit.limit().name()))) throw limit;
            finish();
            return selected;
        }
        budget.note("craft_less", "limit=" + limit.limit() + "; retained_amount=" + selected.amount() + "; result=" + selected.result());
        finishReduced();
        return selected;
    }

    private boolean verifiedRefill(GraphPlan<K> plan, boolean incomplete) {
        return plan.conditionalPreview() || !incomplete && !plan.missingExact().isEmpty() &&
                (plan.result() == GraphPlan.Result.MISSING_INPUT || plan.result() == GraphPlan.Result.MISSING_SEED);
    }
}
