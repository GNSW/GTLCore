// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/** Keeps progress and cancellation attached to the future owned by the requester. */
public class PlanningRequest<K, P> extends CompletableFuture<P> {

    private final PlanningBudget budget;
    private final LongSupplier clock;
    private volatile CompletableFuture<?> worker;
    private long lastNotice;
    private volatile Set<K> dependencies = Set.of();

    public PlanningRequest(PlanningBudget budget) {
        this(budget, System::nanoTime);
    }

    public PlanningRequest(PlanningBudget budget, LongSupplier clock) {
        this.budget = budget;
        this.clock = clock;
    }

    public PlanningBudget budget() {
        return budget;
    }

    public void dependencies(Set<K> keys) {
        dependencies = Set.copyOf(keys);
    }

    public Set<K> dependencies() {
        return dependencies;
    }

    public void attach(CompletableFuture<?> worker) {
        this.worker = worker;
        if (isCancelled()) worker.cancel(false);
    }

    public boolean noticeDue(long thresholdMilliseconds) {
        if (isDone() || budget.elapsedNanos() < thresholdMilliseconds * 1_000_000L) return false;
        long now = clock.getAsLong();
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
