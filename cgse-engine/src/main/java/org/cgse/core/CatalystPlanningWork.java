// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Optional acceleration is bounded by stock and materials, never a requirement to fill the cap. */
public final class CatalystPlanningWork<K> implements PlanningScheduler.Work<GraphPlan<K>> {

    private final CatalystPolicy policy;
    private final Function<CatalystPolicy, GraphPlanningWork<K>> factory;
    private final PlanningBudget budget;
    private final long started = System.nanoTime();
    private GraphPlanningWork<K> current;
    private GraphPlan<K> best, result, preview, pending;
    private RefillPreviewSelection<K> previewSelection;
    private int extra, low, high, attempts = 1;
    private boolean first = true, baseline, minimum;

    public CatalystPlanningWork(CatalystPolicy policy, PlanningBudget budget, Function<CatalystPolicy, GraphPlanningWork<K>> factory) {
        this.policy = policy;
        this.budget = budget;
        this.factory = factory;
        extra = policy.maxExtraCopies();
        current = factory.apply(policy);
    }

    @Override
    public boolean advance(PlanningScheduler.Slice slice) {
        return result != null || (pending != null || current.advance(slice)) && consume();
    }

    public boolean step() {
        return result != null || (pending != null || current.step()) && consume();
    }

    private boolean consume() {
        if (pending == null) {
            pending = current.result();
            budget.note("catalysts", "attempt=" + attempts + "; extra=" + extra + "; minimum=" + minimum + "; result=" + pending.result());
            if (completedPreview(pending)) {
                if (preview == null) preview = pending;
                else previewSelection = new RefillPreviewSelection<>(preview, pending, budget);
            }
        }
        if (previewSelection != null) {
            if (!previewSelection.step()) return false;
            preview = previewSelection.result();
            previewSelection = null;
        }
        GraphPlan<K> candidate = pending;
        pending = null;
        if (switch (candidate.result()) {
            case TIMEOUT, SEARCH_LIMIT, MEMORY_LIMIT, GRAPH_LIMIT, QUEUE_LIMIT -> true;
            default -> false;
        }) return finish(best == null ? withPreview(candidate) : unproven(best));
        if (minimum) return finish(withPreview(candidate));
        if (first) {
            first = false;
            if (candidate.feasible() || policy.parallelism() == 1) return finish(candidate);
            high = extra - 1;
            if (extra > 0) {
                baseline = true;
                next(0);
            } else minimum();
            return false;
        }
        if (baseline) {
            baseline = false;
            if (!candidate.feasible()) {
                minimum();
                return false;
            }
            best = candidate;
            low = 0;
        } else if (candidate.feasible()) {
            best = candidate;
            low = extra;
        } else high = extra - 1;
        if (low >= high) return finish(best);
        // At most 12 bounded refinements. All trials share the original request
        // budget and preserve the last independently verified feasible plan.
        next(low + (high - low + 1) / 2);
        return false;
    }

    private static boolean completedPreview(GraphPlan<?> plan) {
        return !plan.missingExact().isEmpty() && switch (plan.result()) {
            case MISSING_INPUT, MISSING_SEED, INCOMPLETE_MODEL -> true;
            default -> false;
        };
    }

    private GraphPlan<K> withPreview(GraphPlan<K> plan) {
        if (plan.feasible() || preview == null) return plan;
        if (plan != preview) budget.note("catalysts", "retained_refill; missing_keys=" + preview.missingExact().size());
        return preview;
    }

    private void next(int limit) {
        current.close();
        extra = limit;
        attempts++;
        current = factory.apply(new CatalystPolicy(policy.parallelism(), limit));
    }

    private void minimum() {
        current.close();
        minimum = true;
        attempts++;
        current = factory.apply(CatalystPolicy.MINIMAL);
    }

    private boolean finish(GraphPlan<K> plan) {
        result = attempts == 1 ? plan : new GraphPlan<>(plan.target(), plan.amount(), plan.preserveSeeds(), plan.steps(),
                plan.recipes(), plan.initialExact(), plan.seeds(), plan.missingExact(), plan.result(), budget.nodes(), System.nanoTime() - started)
                .withSeedOptimality(plan.seedOptimality()).withAlternatives(plan.alternatives()).withMissingSourcesFrom(plan);
        return true;
    }

    private GraphPlan<K> unproven(GraphPlan<K> plan) {
        return new GraphPlan<>(plan.target(), plan.amount(), plan.preserveSeeds(), plan.steps(), plan.recipes(), plan.initialExact(),
                plan.seeds(), Map.of(), GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, budget.nodes(), System.nanoTime() - started);
    }

    @Override
    public GraphPlan<K> limited(PlanningBudget.Exhausted limit) {
        if (result != null) return result;
        if (best != null) { finish(unproven(best)); return result; }
        // A later optional acceleration trial must not erase an earlier
        // verified refill when it runs out of work, time or memory. Harvest a
        // completed feasible result first; interrupted comparisons keep the
        // already published preview unchanged.
        var candidate = pending == null ? current.limited(limit) : pending;
        finish(withPreview(candidate));
        return result;
    }

    @Override
    public CompletableFuture<?> waitingFor() {
        return result == null ? current.waitingFor() : null;
    }

    @Override
    public void close() {
        current.close();
        pending = null;
        previewSelection = null;
        preview = null;
    }

    @Override
    public GraphPlan<K> result() {
        if (result == null) throw new IllegalStateException("Catalyst search is incomplete");
        return result;
    }
}
