// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/** Well-founded production ranks for a refill's reachable support, with explicit cycle boundaries. */
final class RefillProducerOrder<K> implements AutoCloseable {
    final class Node {
        final K key;
        final List<Source> sources = new ArrayList<>();
        final List<Source> consumers = new ArrayList<>();
        final List<Node> dependencies = new ArrayList<>();
        int rank = -1, index = -1, low, edge;
        boolean onStack, cycle;
        Node(K key) { this.key = key; }
    }
    final class Source {
        final Node output;
        final GraphRecipe<K> recipe;
        int remaining, depth;
        Source(Node output, GraphRecipe<K> recipe) { this.output = output; this.recipe = recipe; }
    }
    private final GraphCompiler<K> compiler;
    private final Set<K> external;
    private final Set<String> excluded;
    private final PlanningBudget budget;
    private final Map<K, Node> indexed = new LinkedHashMap<>();
    private final List<Node> nodes = new ArrayList<>();
    private final Deque<Node> ready = new ArrayDeque<>(), dfs = new ArrayDeque<>(), component = new ArrayDeque<>();
    private int phase, next, serial;
    private long memory;

    RefillProducerOrder(GraphCompiler<K> compiler, Collection<K> roots, Set<K> external,
                        Set<String> excluded, PlanningBudget budget) {
        this.compiler = compiler; this.external = external; this.excluded = excluded; this.budget = budget;
        // No budget allocations in constructors: an interrupted owner must be able to close us.
        for (K key : roots) indexed.putIfAbsent(key, null);
    }
    private Node node(K key) {
        Node node = indexed.get(key);
        if (node == null) {
            reserve(256); node = new Node(key); indexed.put(key, node); nodes.add(node);
        }
        return node;
    }
    Node get(K key) { return indexed.get(key); }
    int rank(K key) { Node node = get(key); return node == null ? 0 : node.rank; }

    boolean step() {
        budget.check();
        if (phase == 0) {
            for (K key : new ArrayList<>(indexed.keySet())) { budget.check(); node(key); }
            phase = 1; return false;
        }
        if (phase == 1) {
            if (next == nodes.size()) { next = 0; phase = 2; return false; }
            Node output = nodes.get(next++);
            if (!external.contains(output.key)) for (var recipe : compiler.producers(output.key)) {
                budget.check();
                if (excluded.contains(recipe.id()) || recipe.outputs().getOrDefault(output.key, 0L) <=
                        recipe.inputs().getOrDefault(output.key, 0L)) continue;
                reserve(128); var source = new Source(output, recipe); output.sources.add(source);
                for (K input : recipe.inputs().keySet()) {
                    budget.check(); reserve(48);
                    Node dependency = node(input); dependency.consumers.add(source);
                    output.dependencies.add(dependency); source.remaining++;
                }
            }
            return false;
        }
        if (phase == 2) {
            if (next == nodes.size()) { next = 0; phase = 3; return false; }
            Node node = nodes.get(next++);
            if (node.sources.isEmpty()) rank(node, 0);
            else for (Source source : node.sources) if (source.remaining == 0) rank(node, 1);
            return false;
        }
        if (phase == 3 || phase == 5) {
            if (ready.isEmpty()) {
                if (phase == 5) return true;
                next = 0; phase = 4; return false;
            }
            Node node = ready.removeFirst();
            for (Source source : node.consumers) {
                budget.check(); source.depth = Math.max(source.depth, node.rank);
                if (--source.remaining == 0) rank(source.output, source.depth + 1);
            }
            return false;
        }
        // Only the still-unranked support needs cycle analysis. A raw alternative
        // already assigned a finite derivation rank, even if another source cycles.
        if (dfs.isEmpty()) {
            while (next < nodes.size()) {
                budget.check(); Node node = nodes.get(next++);
                if (node.rank < 0 && node.index < 0) { visit(node); return false; }
            }
            phase = 5; return false;
        }
        Node node = dfs.peek();
        if (node.edge < node.dependencies.size()) {
            Node child = node.dependencies.get(node.edge++);
            if (child.rank >= 0) return false;
            if (child.index < 0) visit(child);
            else if (child.onStack) node.low = Math.min(node.low, child.index);
            return false;
        }
        dfs.pop();
        if (!dfs.isEmpty()) dfs.peek().low = Math.min(dfs.peek().low, node.low);
        if (node.low == node.index) {
            var members = new ArrayList<Node>(); Node member;
            do { budget.check(); member = component.pop(); member.onStack = false; members.add(member); } while (member != node);
            boolean cyclic = members.size() > 1 || node.dependencies.contains(node);
            // Rank a closed component above its outside feedstocks. Members
            // share one rank: swapping one unfunded cycle member for another
            // cannot count as expansion, but reducing a seed loan by consuming
            // lower-ranked raw/derived feedstocks can.
            int depth = 0;
            for (Node seed : members) for (Node dependency : seed.dependencies) {
                budget.check(); depth = Math.max(depth, dependency.rank);
            }
            for (Node seed : members) { budget.check(); seed.cycle = cyclic; rank(seed, depth + 1); }
        }
        return false;
    }
    private void visit(Node node) {
        node.index = node.low = serial++; node.onStack = true; dfs.push(node); component.push(node);
    }
    private void rank(Node node, int value) {
        if (node.rank < 0) { node.rank = value; ready.addLast(node); }
    }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    @Override public void close() { budget.release(memory); memory = 0; }
}
