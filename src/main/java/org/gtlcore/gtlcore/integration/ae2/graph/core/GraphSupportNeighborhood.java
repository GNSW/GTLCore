package org.gtlcore.gtlcore.integration.ae2.graph.core;

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
    private IntegerCountSearch<K> search;
    private GraphPlan<K> result;
    private int searchedSize, proposals;
    private long memory, work, until;
    private boolean running;

    GraphSupportNeighborhood(K target, long amount, Map<K, Long> stock, Set<K> external,
                             Map<K, Long> seeds, boolean preserve, boolean force, PlanningBudget budget, long started) {
        this.target = target;
        this.amount = amount;
        this.stock = stock;
        this.external = external;
        this.seeds = seeds;
        this.preserve = preserve;
        this.force = force;
        this.budget = budget;
        this.started = started;
    }

    void offer(GraphCompiler.Compiled<K> graph, GraphPlan<K> candidate) {
        if (candidate.feasible() || candidate.missingExact().isEmpty() || pool.size() >= 512) return;
        // Candidate counts identify the actually used support, even when the
        // selected source graph contains thousands of irrelevant recipes.
        var used = candidate.patternTimesExact();
        if (used.isEmpty() || used.size() > 256) return;
        proposals++;
        for (String id : used.keySet()) {
            budget.operation(PlanningBudget.Operation.SCAN, 1);
            add(graph.recipes().get(id));
        }
        for (K key : candidate.missingExact().keySet()) {
            budget.operation(PlanningBudget.Operation.SCAN, 1);
            add(graph.selected().get(key));
        }
    }

    private void add(GraphRecipe<K> recipe) {
        if (recipe == null || pool.containsKey(recipe.id()) || pool.size() >= 512) return;
        long bytes = 256L + 96L * (recipe.inputs().size() + (long) recipe.outputs().size());
        if (bytes > (4L << 20) - memory || !budget.tryReserve(bytes)) return;
        memory += bytes;
        pool.put(recipe.id(), recipe);
    }

    boolean beginTurn(long allowance) {
        if (allowance < 32768 || proposals < 2 || pool.size() < 2 || running) return false;
        if (search != null && pool.size() >= searchedSize + Math.max(8, searchedSize / 4)) {
            search.close();
            search = null;
        }
        if (search == null && pool.size() == searchedSize) return false;
        until = work + allowance;
        running = true;
        return true;
    }

    boolean step() {
        long before = budget.threadSearchWork();
        String previousFailure = budget.failureDetail();
        try {
            if (!running) return true;
            if (work >= until) {
                running = false;
                return true;
            }
            if (search == null) {
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
        return search != null;
    }

    @Override
    public void close() {
        if (search != null) search.close();
        search = null;
        pool.clear();
        budget.release(memory);
        memory = 0;
        running = false;
    }
}
