// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/** Incremental display witnesses inside the already classified support components. */
final class RefillCycleView<K> implements AutoCloseable {
    private final GraphPlan<K> plan;
    private final RefillProducerOrder<K> order;
    private final PlanningBudget budget;
    private final Iterator<Map.Entry<K, GraphPlan.MissingSource>> roots;
    private final List<List<GraphRecipe<K>>> cycles = new ArrayList<>();
    private final Set<RefillProducerOrder<K>.Node> covered = Collections.newSetFromMap(new IdentityHashMap<>());
    private Map<RefillProducerOrder<K>.Node, Trace> seen;
    private Deque<Trace> queue;
    private RefillProducerOrder<K>.Node root;
    private RefillProducerOrder<K>.Source source;
    private Trace active, walking;
    private int sourceAt, inputAt;
    private List<GraphRecipe<K>> path;
    private long memory, workspace;

    private final class Trace {
        final RefillProducerOrder<K>.Node node;
        final Trace parent;
        final GraphRecipe<K> via;
        Trace(RefillProducerOrder<K>.Node node, Trace parent, GraphRecipe<K> via) {
            this.node = node; this.parent = parent; this.via = via;
        }
    }

    RefillCycleView(GraphPlan<K> plan, RefillProducerOrder<K> order, PlanningBudget budget,
                    Map<K, GraphPlan.MissingSource> sources) {
        this.plan = plan; this.order = order; this.budget = budget; roots = sources.entrySet().iterator();
    }

    /** One vertex/edge/path operation, charged by the independent diagnostic allowance. */
    boolean advance() {
        if (path != null) {
            if (!reserve(96)) return true;
            covered.add(walking.node);
            if (walking.parent != null) {
                path.add(walking.via); walking = walking.parent;
            } else {
                // Publish only after the entire return path is known. A budget
                // stop before this point cannot display a fabricated half-cycle.
                cycles.add(Collections.unmodifiableList(path));
                path = null; walking = null; reset();
            }
        } else if (root == null) {
            if (!roots.hasNext()) return true;
            var entry = roots.next();
            if (entry.getValue() != GraphPlan.MissingSource.CYCLE_BOUNDARY) return false;
            var node = order.get(entry.getKey());
            if (node == null || covered.contains(node)) return false;
            if (!reserveWorkspace(384)) return true;
            root = node; seen = new IdentityHashMap<>(); queue = new ArrayDeque<>();
            var trace = new Trace(root, null, null); seen.put(root, trace); queue.add(trace);
        } else if (active == null) {
            if (queue.isEmpty()) { reset(); return false; }
            active = queue.removeFirst(); sourceAt = 0;
        } else if (source == null) {
            if (sourceAt == active.node.sources.size()) { active = null; return false; }
            var candidate = active.node.sources.get(sourceAt++);
            var recipe = candidate.recipe;
            // A display limitation or a conflicting binding must not invalidate
            // a previously verified bill, nor invent a path through that recipe.
            if (recipe.inputs().size() > PlanRingView.MAX_SLOTS || recipe.outputs().size() > PlanRingView.MAX_SLOTS) return false;
            var selected = plan.recipes().get(recipe.id());
            if (selected != null && !selected.equals(recipe)) return false;
            source = candidate; inputAt = 0;
        } else if (inputAt == source.inputs.size()) {
            source = null;
        } else {
            var next = source.inputs.get(inputAt++);
            if (next.componentId != root.componentId) return false;
            if (next == root) {
                if (!reserve(96)) return true;
                path = new ArrayList<>(); path.add(source.recipe); walking = active;
            } else if (!seen.containsKey(next)) {
                if (!reserveWorkspace(160)) return true;
                var trace = new Trace(next, active, source.recipe); seen.put(next, trace); queue.addLast(trace);
            }
        }
        return false;
    }

    List<List<GraphRecipe<K>>> cycles() { return cycles; }
    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) return false;
        memory += bytes; return true;
    }
    private boolean reserveWorkspace(long bytes) {
        if (!reserve(bytes)) return false;
        workspace += bytes; return true;
    }
    private void reset() {
        budget.release(workspace); memory -= workspace; workspace = 0;
        root = null; active = null; source = null; seen = null; queue = null;
    }
    @Override public void close() {
        budget.release(memory); memory = workspace = 0;
        root = null; source = null; active = walking = null; path = null; seen = null; queue = null;
    }
}
