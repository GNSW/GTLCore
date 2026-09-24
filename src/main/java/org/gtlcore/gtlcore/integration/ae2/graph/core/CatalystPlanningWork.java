package org.gtlcore.gtlcore.integration.ae2.graph.core;

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
    private GraphPlan<K> best, result;
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
        return result != null || current.advance(slice) && consume();
    }

    public boolean step() {
        return result != null || current.step() && consume();
    }

    private boolean consume() {
        GraphPlan<K> candidate = current.result();
        if (switch (candidate.result()) {
            case TIMEOUT, SEARCH_LIMIT, MEMORY_LIMIT, GRAPH_LIMIT, QUEUE_LIMIT -> true;
            default -> false;
        }) return finish(best == null ? candidate : unproven(best));
        if (minimum) return finish(candidate);
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

    private void next(int limit) {
        extra = limit;
        attempts++;
        current = factory.apply(new CatalystPolicy(policy.parallelism(), limit));
    }

    private void minimum() {
        minimum = true;
        attempts++;
        current = factory.apply(CatalystPolicy.MINIMAL);
    }

    private boolean finish(GraphPlan<K> plan) {
        result = attempts == 1 ? plan : new GraphPlan<>(plan.target(), plan.amount(), plan.preserveSeeds(), plan.steps(),
                plan.recipes(), plan.initialExact(), plan.seeds(), plan.missingExact(), plan.result(), budget.nodes(), System.nanoTime() - started);
        return true;
    }

    private GraphPlan<K> unproven(GraphPlan<K> plan) {
        return new GraphPlan<>(plan.target(), plan.amount(), plan.preserveSeeds(), plan.steps(), plan.recipes(), plan.initialExact(),
                plan.seeds(), Map.of(), GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, budget.nodes(), System.nanoTime() - started);
    }

    @Override
    public GraphPlan<K> limited(PlanningBudget.Exhausted limit) {
        return best == null ? current.limited(limit) : unproven(best);
    }

    @Override
    public CompletableFuture<?> waitingFor() {
        return result == null ? current.waitingFor() : null;
    }

    @Override
    public GraphPlan<K> result() {
        if (result == null) throw new IllegalStateException("Catalyst search is incomplete");
        return result;
    }
}
