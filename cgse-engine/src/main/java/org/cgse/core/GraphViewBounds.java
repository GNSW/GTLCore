// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Necessary material bounds for one complete selected proposal graph. A local
 * region is insufficient: another selected region may supply its coproducts.
 * Neither an interrupted check nor a contradiction is a full-catalog proof.
 */
final class GraphViewBounds<K> implements AutoCloseable {
    private final PlanningBudget budget;
    private final long allowance;
    private long memory, work;
    private RecipeCountModel<K> model;
    private CountBounds bounds;
    private boolean complete, blocked;

    static <K> GraphViewBounds<K> create(GraphCompiler.Compiled<K> graph, K target, long amount,
                                       Map<K, Long> stock, Map<K, Long> seeds, Set<K> external, boolean force,
                                       PlanningBudget budget, long allowance) {
        if (graph.recipes().size() > 64 || seeds.size() > 64 || allowance < 1024 || budget.remainingWork() < 8192)
            return null;
        long started = budget.threadSearchWork();
        long ports = 0;
        for (var recipe : graph.recipes().values()) {
            budget.check();
            ports += (long) recipe.inputs().size() + recipe.outputs().size();
            if (ports > 256) return null;
        }
        long bytes = 512L + 32L * graph.recipes().size() + 128L * (seeds.size() + 1L);
        if (force && !external.contains(target)) bytes += 2L * (128L + 96L * graph.recipes().size());
        if (!budget.tryReserve(bytes)) return null;
        var check = new GraphViewBounds<K>(budget, Math.min(4096, allowance), bytes);
        try {
            Map<K, BigInteger> goals = new LinkedHashMap<>();
            for (var seed : seeds.entrySet()) {
                budget.check();
                goals.put(seed.getKey(), BigInteger.valueOf(seed.getValue()));
            }
            // Existing target stock may fund startup, including forced orders.
            // This relaxation never replaces the final production proof.
            goals.merge(target, BigInteger.valueOf(amount), BigInteger::add);
            check.model = RecipeCountModel.forShell(List.copyOf(graph.recipes().values()), goals, stock, external, budget);
            if (check.model != null && force && !external.contains(target)) check.model.requireProduction(target, amount);
            if (check.model == null || budget.threadSearchWork() - started >= check.allowance) {
                check.close();
                return null;
            }
            check.bounds = CountBounds.propagationOnly(check.model.recipes.size(), check.model.constraints, budget);
            return check;
        } catch (RuntimeException | Error failure) {
            check.close();
            throw failure;
        } finally { check.work = budget.threadSearchWork() - started; }
    }

    private GraphViewBounds(PlanningBudget budget, long allowance, long memory) {
        this.budget = budget;
        this.allowance = allowance;
        this.memory = memory;
    }

    boolean step() {
        long before = budget.threadSearchWork();
        try {
            budget.checkpoint();
            if (complete) return true;
            if (bounds.blocked()) {
                blocked = complete = true;
            } else if (work >= allowance) {
                complete = true;
            } else if (bounds.step()) {
                blocked = bounds.blocked();
                complete = true;
            }
            return complete;
        } finally { work += budget.threadSearchWork() - before; }
    }

    boolean blocked() { return blocked; }

    @Override public void close() {
        if (bounds != null) bounds.close();
        if (model != null) model.close();
        bounds = null;
        model = null;
        budget.release(memory);
        memory = 0;
        complete = true;
    }
}
