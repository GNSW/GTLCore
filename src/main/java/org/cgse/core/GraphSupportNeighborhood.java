// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;

/** Joint exact-count search over supports proposed by different demand views. */
final class GraphSupportNeighborhood<K> implements AutoCloseable {

    private final K target;
    private final long amount, started;
    private final Map<K, Long> stock, seeds;
    private final Set<K> external;
    private final boolean preserve, force;
    private final PlanningBudget budget;
    private final Map<String, GraphRecipe<K>> pool = new LinkedHashMap<>();
    private final GraphResidualSources<K> residual;
    private IntegerCountSearch<K> search;
    private GraphPlan<K> result;
    private int searchedSize, proposals;
    private long memory, work, until, expansionUntil;
    private boolean running, expanding, turnDeclined, expandedSupport, retrySearch;

    GraphSupportNeighborhood(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock, Set<K> external,
                             Map<K, Long> seeds, Set<String> excluded, boolean preserve, boolean force, PlanningBudget budget, long started) {
        this.target = target;
        this.amount = amount;
        this.stock = stock;
        this.external = external;
        this.seeds = seeds;
        this.preserve = preserve;
        this.force = force;
        this.budget = budget;
        this.started = started;
        residual = new GraphResidualSources<>(compiler, stock, external, excluded, pool, this::add, budget);
    }

    void offer(GraphCompiler.Compiled<K> graph, GraphPlan<K> candidate) {
        if (candidate.feasible() || candidate.missingExact().isEmpty() || pool.size() >= 512) return;
        // Candidate counts identify the actually used support, even when the
        // selected source graph contains thousands of irrelevant recipes.
        var used = candidate.patternTimesExact();
        if (used.isEmpty() || used.size() > 256) {
            budget.note("support_neighborhood", "support_admission; used=" + used.size() + "; positive_only");
            return;
        }
        proposals++;
        for (String id : used.keySet()) {
            budget.operation(PlanningBudget.Operation.SCAN, 1);
            add(graph.recipes().get(id));
        }
        for (K key : candidate.missingExact().keySet()) {
            budget.operation(PlanningBudget.Operation.SCAN, 1);
            add(graph.selected().get(key));
            residual.offer(key);
        }
        // A missing raw input may have no producer. Reopen the outputs of its
        // consumers too: a co-producer can replace several individually cheap
        // routes that compete for the same limited inventory.
        for (String id : used.keySet()) {
            var recipe = graph.recipes().get(id);
            if (recipe != null) for (K key : recipe.executionOutputs().keySet()) residual.offer(key);
        }
    }

    private boolean add(GraphRecipe<K> recipe) {
        if (recipe == null || pool.containsKey(recipe.id()) || pool.size() >= 512) return false;
        long bytes = 256L + 96L * (recipe.inputs().size() + (long) recipe.outputs().size());
        if (bytes > (4L << 20) - memory || !budget.tryReserve(bytes)) return false;
        memory += bytes;
        pool.put(recipe.id(), recipe);
        return true;
    }

    boolean beginTurn(long allowance) {
        turnDeclined = allowance < 32768;
        boolean frontier = pool.size() < 512 && residual.pending();
        if (allowance < 32768 || proposals < 1 || pool.size() < 1 || running || result != null) return false;
        if ((pool.size() < 2 || proposals < 2 && !expandedSupport) && !frontier) return false;
        if (search != null && pool.size() >= searchedSize + Math.max(8, searchedSize / 4)) {
            search.close();
            search = null;
        }
        if (search == null && pool.size() == searchedSize && !frontier && !retrySearch) return false;
        until = work + allowance;
        expanding = search == null && frontier && !retrySearch;
        if (expanding) {
            residual.begin(Math.min(32, 512 - pool.size()));
            expansionUntil = work + Math.min(65536, allowance / 2);
        }
        running = true;
        return true;
    }

    void rejectCandidate() {
        // The local count search already validates its witness. If the outer
        // validator cannot accept it, retain the support pool, not a stale
        // result to emit again. A larger support may start a fresh local turn.
        result = null;
    }

    boolean step() {
        try (var costs = budget.trace(PlanningCostTrace.Origin.SUPPORT_NEIGHBORHOOD, PlanningCostTrace.Stage.SEARCH)) {
            return stepMeasured();
        }
    }

    private boolean stepMeasured() {
        long before = budget.threadSearchWork();
        String previousFailure = budget.failureDetail();
        try {
            if (!running) return true;
            if (work >= until) {
                running = false;
                return true;
            }
            if (expanding) {
                if (work < expansionUntil && !residual.step()) return false;
                expanding = false;
                expandedSupport |= residual.added() > 0;
                budget.note("support_neighborhood", "residual_admission; added=" + residual.added() +
                        "; scanned=" + residual.scanned() + "; recipes=" + pool.size() + "; pending=" + residual.pending());
                // An empty scan is not a new support. Preserve the old cheap
                // path for a single recipe or a single unchanged proposal.
                if (pool.size() < 2 || proposals < 2 && !expandedSupport) { running = false; return true; }
                if (pool.size() == searchedSize) { running = false; return true; }
            }
            if (search == null) {
                retrySearch = false;
                searchedSize = pool.size();
                var recipes = List.copyOf(pool.values());
                for (var recipe : recipes) {
                    budget.operation(PlanningBudget.Operation.SCAN, 1);
                    for (K ignored : recipe.executionOutputs().keySet()) budget.operation(PlanningBudget.Operation.SCAN, 1);
                }
                // This is a restricted positive-witness neighborhood. It owns
                // its compiler, arithmetic cuts and count continuations. No
                // negative result or learned clause escapes to the full catalog.
                var compiler = new GraphCompiler<>(recipes);
                search = new IntegerCountSearch<>(compiler, target, amount, stock, seeds, external, Set.of(),
                        preserve, force, budget, started);
                if (!search.admitted() && residual.releaseRankings() > 0) {
                    search.close();
                    search = null;
                    retrySearch = true;
                    budget.note("support_neighborhood", "declined_model_retry_without_rankings; recipes=" + pool.size());
                    return false;
                }
                search.scout(Math.max(1, until - work));
                budget.note("support_neighborhood", "start; recipes=" + searchedSize + "; proposals=" + proposals);
            } else if (search.paused()) search.resume();
            if (!search.step()) return false;
            result = search.result();
            if (!search.paused() || result != null) {
                search.close();
                search = null;
            }
            running = false;
            budget.note("support_neighborhood", "handoff; witness=" + (result != null) + "; retained=" + (search != null));
            return true;
        } catch (PlanningBudget.Exhausted failure) {
            if (failure.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw failure;
            if (search != null) search.close();
            search = null;
            retrySearch = residual.releaseRankings() > 0;
            if (retrySearch) {
                // Retry the SAME admitted support before allocating any new
                // ranking window. Its previous work still consumes the request
                // and local quotas, including a handoff at the next step.
                budget.failureDetail(previousFailure);
                budget.note("support_neighborhood", "workspace_retry_without_rankings; recipes=" + pool.size());
                return false;
            }
            running = false;
            budget.failureDetail(previousFailure);
            budget.note("support_neighborhood", "workspace_declined; full_catalog_retained");
            return true;
        } finally {
            work += budget.threadSearchWork() - before;
        }
    }

    GraphPlan<K> result() {
        return result;
    }

    boolean retained() {
        // A catalog cursor is not runnable work when the caller cannot grant
        // even the admission slice. Let the original planner use that budget;
        // repeatedly parking this optional view would otherwise starve it.
        return !turnDeclined && (search != null || retrySearch || pool.size() < 512 && residual.pending());
    }

    @Override
    public void close() {
        if (search != null) search.close();
        search = null;
        residual.close();
        pool.clear();
        budget.release(memory);
        memory = 0;
        running = false;
        retrySearch = false;
    }
}
