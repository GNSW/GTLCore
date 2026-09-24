package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.integration.ae2.graph.core.PlanningBudget;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Keeps progress/cancellation attached to the exact future owned by the AE menu. */
public final class GraphPlanningRequest extends CompletableFuture<ICraftingPlan> {

    private final PlanningBudget budget;
    private volatile CompletableFuture<?> worker;
    private long lastNotice;
    private volatile Set<AEKey> dependencies = Set.of();

    GraphPlanningRequest(PlanningBudget budget) {
        this.budget = budget;
    }

    public PlanningBudget budget() {
        return budget;
    }

    void dependencies(Set<AEKey> keys) {
        dependencies = Set.copyOf(keys);
    }

    public Set<AEKey> dependencies() {
        return dependencies;
    }

    void attach(CompletableFuture<?> worker) {
        this.worker = worker;
        if (isCancelled()) worker.cancel(false);
    }

    public boolean noticeDue(long thresholdMilliseconds) {
        if (isDone() || budget.elapsedNanos() < thresholdMilliseconds * 1_000_000L) return false;
        long now = System.nanoTime();
        if (lastNotice != 0 && now - lastNotice < 1_000_000_000L) return false;
        lastNotice = now;
        return true;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean changed = super.cancel(mayInterruptIfRunning);
        if (changed) {
            budget.cancel();
            var current = worker;
            if (current != null) current.cancel(false);
        }
        return changed;
    }
}
