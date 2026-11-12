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
 * FULL/ACYCLIC primary solves. Catalog capture and host-specific plan assembly stay outside.
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

    /** Legacy host API. Both graph modes now use the primary budget configuration. */
    @Deprecated
    public record FallbackLimits(long milliseconds, long maxWork, long maxBytes) {
        // Retain the old public constant for binary/source compatibility only.
        // These values do not configure either of the two primary solves.
        public static final FallbackLimits DEFAULT = new FallbackLimits(0, 131_072, 16L << 20);

        public FallbackLimits {
            if (milliseconds < 0 || milliseconds > Long.MAX_VALUE / 1_000_000L || maxWork <= 0 || maxBytes <= 0)
                throw new IllegalArgumentException("Invalid fallback limits");
        }
    }

    /** Both solve accounts remain explicit; legacy field names preserve the host API. */
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
    private final java.math.BigInteger capturedTargetStock;
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
    private PlanningBudget.Metrics fallbackMetrics;
    private PlanningCostTrace.Snapshot fallbackPipeline;
    private PlanningScheduler.Work<GraphPlan<K>> fallbackWork;
    private BooleanSupplier fallbackStep;
    private PlanningBudget fallbackBudget;
    private GraphPlan.Result fallbackTrigger;
    private RuntimeException fallbackFailure;
    private boolean advancing;
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
        capturedTargetStock = PlanningInventory.exactAmount(stock, target);
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

    /** Compatibility with existing adapters. Separate fallback caps no longer apply. */
    @Deprecated
    public RequestPlanningWork<K> fallbackLimits(FallbackLimits limits) {
        if (current != null || selected != null || reuseAttempted || fallbackAttempted)
            throw new IllegalStateException("Planning already started");
        java.util.Objects.requireNonNull(limits);
        return this;
    }

    /**
     * Retain the whole captured candidate domain, not only the compiler's first
     * concrete window. Configure before submitting this request. The compiler
     * is not substituted for the captured catalog during the ACYCLIC attempt.
     */
    public RequestPlanningWork<K> capturedCatalog(CapturedCatalog<K> catalog) {
        if (current != null || selected != null || reuseAttempted) throw new IllegalStateException("Planning already started");
        capturedCatalog = java.util.Objects.requireNonNull(catalog);
        boolean produced = catalog.hasPotentialProducer(target);
        boolean emittedBefore = directEmission;
        directEmission = emitable.contains(target) && !produced;
        unavailableTarget = checkpoint == null && !emitable.contains(target) && !produced;
        if (checkpoint == null && emittedBefore != directEmission) {
            var updated = new java.util.LinkedHashMap<>(PlanningInventory.exactAmounts(available));
            if (directEmission || capturedTargetStock.signum() <= 0) updated.remove(target);
            else updated.put(target, capturedTargetStock);
            available = PlanningInventory.exactSnapshot(updated);
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
        if (completed) return true;
        if (fallbackWork != null) return advanceFallback(slice);
        advancing = true;
        try {
            return advancePrimary(slice);
        } catch (PlanningBudget.Exhausted limit) {
            limited(limit);
            return completed;
        } catch (java.util.concurrent.CompletionException failure) {
            Throwable cause = failure;
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (!(cause instanceof PlanningBudget.Exhausted limit)) throw failure;
            limited(limit);
            return completed;
        } finally {
            advancing = false;
        }
    }

    private boolean advancePrimary(PlanningScheduler.Slice slice) {
        if (current == null) {
            current = calculation(amount);
        }
        if (!current.advance(slice)) return false;
        GraphPlan<K> candidate = scopedPreview(current.result(), currentIncomplete);
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
                    .multiply(PlanningInventory.exactAmount(available, entry.getKey()))
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
        selected = scopedPreview(selected, selectedIncomplete);
        if (!selected.feasible() && !selected.conditionalPreview() && (selected.missingExact().isEmpty() || selectedIncomplete && !fallbackMode)) {
            // Coverage applies before every negative exit, including a completed
            // child proof without a refill preview. Exhausted search and omitted
            // alternatives are separate reasons, not interchangeable budgets.
            GraphPlan.Result reason = failureReason();
            if (!fallback(reason)) throw limitOrUnknown(reason);
            if (fallbackWork != null) return false;
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

    /** The same primary algorithms receive a fresh, globally acyclic recipe domain. */
    private boolean fallback(GraphPlan.Result reason) {
        if (!fallbackEnabled || fallbackAttempted || cancelled.getAsBoolean()) return false;
        if (!switch (reason) {
            case UNKNOWN, INCOMPLETE_MODEL, TIMEOUT, SEARCH_LIMIT, MEMORY_LIMIT, GRAPH_LIMIT -> true;
            default -> false;
        }) return false;
        fallbackAttempted = true;
        // Stop siblings, release the old frontier, and restart only the model.
        // No spent work, timeout or internal cancellation is inherited.
        budget.cancel();
        if (current != null) {
            current.close();
            current = null;
        }
        fallbackTrigger = reason;
        if (fallbackFailure == null) fallbackFailure = limitOrUnknown(reason);
        fallbackBudget = budget.independentSolve(cancelled);
        fallbackWork = acyclicCalculation();
        budget.note("fallback", "mode=ACYCLIC; same_primary_limits; trigger=" + reason);
        // Public limited() remains synchronous for callers outside the
        // scheduler. Scheduled requests resume the identical state in advance.
        if (!advancing) while (!advanceFallback(null)) {}
        return true;
    }

    private PlanningScheduler.Work<GraphPlan<K>> acyclicCalculation() {
        if (capturedCatalog != null) {
            var work = capturedCatalog.begin(target, amount, available, emitable, recoverySeeds,
                    preserve, checkpoint == null && !directEmission, fallbackBudget)
                    .graphMode(GraphMode.ACYCLIC).catalysts(CatalystPolicy.MINIMAL).conditionalPreviews(true);
            fallbackStep = work::step;
            return new PlanningScheduler.Work<>() {
                public boolean advance(PlanningScheduler.Slice slice) { return work.advance(slice); }
                public GraphPlan<K> result() { return work.result().plan(); }
                public GraphPlan<K> limited(PlanningBudget.Exhausted limit) { return work.limited(limit).plan(); }
                public CompletableFuture<?> waitingFor() { return work.waitingFor(); }
                public void close() { work.close(); }
            };
        }
        // The restricted domain has no cyclic catalysts to optimize. Keep
        // explicit final inventory requirements, without a seed-policy search.
        var work = new GraphPlanningWork<>(compiler, target, amount, available, emitable, recoverySeeds,
                preserve, checkpoint == null && !directEmission, fallbackBudget, GraphMode.ACYCLIC);
        fallbackStep = work::step;
        return work;
    }

    private boolean advanceFallback(PlanningScheduler.Slice slice) {
        try {
            GraphPlan<K> candidate;
            try (var timing = slice == null ? fallbackBudget.work(fallbackBudget.phase()) : null;
                 var costs = fallbackBudget.trace(PlanningCostTrace.Origin.FALLBACK, PlanningCostTrace.Stage.SEARCH)) {
                // The queue/admission owner stays the same, but both local
                // slicing and parallel children must charge the NEW account.
                boolean done = slice == null ? fallbackStep.getAsBoolean() : fallbackWork.advance(slice.withBudget(fallbackBudget));
                if (!done) return false;
                candidate = fallbackWork.result();
            }
            return acceptFallback(candidate);
        } catch (PlanningBudget.Exhausted exhausted) {
            if (fallbackWork == null) throw exhausted;
            return fallbackLimited(exhausted);
        } catch (java.util.concurrent.CompletionException failure) {
            Throwable cause = failure;
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof PlanningBudget.Exhausted limit) return fallbackLimited(limit);
            closeFallback();
            throw failure;
        } catch (RuntimeException | Error failure) {
            closeFallback();
            throw failure;
        } finally {
            // Existing hosts observe the request's original account for UI
            // progress; publish the active phase without transferring its work.
            if (fallbackBudget != null) budget.phase(completed ? PlanningBudget.Phase.COMPLETE : fallbackBudget.phase());
        }
    }

    private boolean fallbackLimited(PlanningBudget.Exhausted limit) {
        budget.note("fallback", "mode=ACYCLIC; trigger=" + fallbackTrigger + "; stopped=" + limit.limit() + "; work=" + fallbackBudget.nodes());
        try {
            return acceptFallback(fallbackWork.limited(limit));
        } finally {
            closeFallback();
        }
    }

    private boolean acceptFallback(GraphPlan<K> candidate) {
        if (candidate != null && (candidate.feasible() || verifiedRefill(candidate, true))) {
            // A completed primary incumbent always wins over a failed second
            // attempt. Normally it already ended the request before this phase.
            if (selected == null || !selected.feasible() && !verifiedRefill(selected, selectedIncomplete)) {
                selected = candidate;
                selectedIncomplete = true;
                fallbackMode = true;
            }
            budget.note("fallback", "mode=ACYCLIC; cycle_solving=false; work=" + fallbackBudget.nodes() + "; result=" + selected.result());
            fallbackReporter.accept(new FallbackReport<>(target, amount, fallbackTrigger, selected.result(),
                    fallbackBudget.nodes(), fallbackBudget.elapsedNanos()));
            closeFallback();
            return finish();
        }
        budget.note("fallback", "mode=ACYCLIC; restricted_failure=" + (candidate == null ? "UNKNOWN" : candidate.result()));
        closeFallback();
        if (selected != null && (selected.feasible() || verifiedRefill(selected, selectedIncomplete))) return finish();
        // A failed subset proves nothing about the original domain. Preserve
        // the full attempt's resource/unknown classification and diagnostic.
        throw fallbackFailure;
    }

    private void closeFallback() {
        if (fallbackBudget != null) fallbackBudget.cancel();
        if (fallbackWork != null) fallbackWork.close();
        fallbackWork = null;
        fallbackStep = null;
        if (fallbackBudget != null) {
            fallbackMetrics = fallbackBudget.metrics();
            fallbackPipeline = fallbackBudget.pipelineMetrics();
        }
    }

    @Override
    public void close() {
        if (current != null) current.close();
        closeFallback();
    }

    @Override
    public CompletableFuture<?> waitingFor() {
        if (fallbackWork != null) return fallbackWork.waitingFor();
        return current == null ? null : current.waitingFor();
    }

    @Override
    public PlanningBudget activeBudget() {
        return fallbackWork == null ? budget : fallbackBudget;
    }

    @Override
    public GraphPlan<K> result() {
        return selected;
    }

    @Override
    public GraphPlan<K> limited(PlanningBudget.Exhausted limit) {
        if (fallbackWork != null) {
            if (!advancing) while (!advanceFallback(null)) {}
            return selected;
        }
        selected = scopedPreview(selected, selectedIncomplete);
        if (current != null) {
            GraphPlan<K> retained = scopedPreview(current.limited(limit), currentIncomplete);
            if (retained.feasible() && (selected == null || !selected.feasible() || retained.amount() >= selected.amount())) {
                selected = retained;
                selectedIncomplete = currentIncomplete;
            } else if (verifiedRefill(retained, currentIncomplete) &&
                    (selected == null || !selected.feasible() && selected.missingExact().isEmpty())) {
                selected = retained;
                selectedIncomplete = currentIncomplete;
            } else if (retained.result() == GraphPlan.Result.INFEASIBLE && !currentIncomplete &&
                    (selected == null || !selected.feasible() && selected.missingExact().isEmpty())) {
                // Both complete ordinary catalogs and complete capture owners
                // retain original-domain proofs; bounded alternatives do not.
                selected = retained;
                selectedIncomplete = false;
                finish();
                return selected;
            } else if (retained.result() == GraphPlan.Result.INFEASIBLE && currentIncomplete &&
                    budget.failureDetail().startsWith("PROVEN_INFEASIBLE:")) {
                budget.failureDetail("RESTRICTED_PROOF_NOT_TRANSFERRED: preview stopped by " + limit.limit());
            }
        }
        if (selected == null || !selected.feasible() && !verifiedRefill(selected, selectedIncomplete) &&
                (!partialSearch || selected.missingExact().isEmpty())) {
            fallbackFailure = limit;
            if (!fallback(GraphPlan.Result.valueOf(limit.limit().name()))) throw limit;
            if (fallbackWork != null) return selected;
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

    /** A verified word from incomplete capture is still a valid conditional
     * bill. Missing producer alternatives remove necessity/optimality claims,
     * not its physical witness, and do not trigger a second solve. */
    private GraphPlan<K> scopedPreview(GraphPlan<K> plan, boolean incomplete) {
        if (plan == null || !incomplete || plan.missingExact().isEmpty() ||
                plan.result() != GraphPlan.Result.MISSING_INPUT && plan.result() != GraphPlan.Result.MISSING_SEED) return plan;
        return new GraphPlan<>(plan.target(), plan.amount(), plan.preserveSeeds(), plan.steps(), plan.recipes(),
                plan.initialExact(), plan.seeds(), plan.missingExact(), GraphPlan.Result.INCOMPLETE_MODEL,
                plan.searchNodes(), plan.planningNanos()).withMissingSourcesFrom(plan)
                .withAlternatives(plan.alternatives().stream().map(option -> scopedPreview(option, true)).toList());
    }
}
