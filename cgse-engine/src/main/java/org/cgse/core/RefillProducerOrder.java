// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/** Well-founded production ranks over material OR nodes and shared recipe AND nodes. */
final class RefillProducerOrder<K> implements AutoCloseable {
    private static final int OPERATIONS_PER_STEP = 64;

    abstract class Vertex {
        final int increment;
        int rank = -1, index = -1, low, edge, componentId = -1;
        boolean onStack, cycle, selfLoop;
        Vertex(int increment) { this.increment = increment; }
        abstract List<? extends Vertex> dependencies();
        void assign(int value) { if (rank < 0) rank = value; }
    }
    final class Node extends Vertex {
        final K key;
        final List<Source> sources = new ArrayList<>(), consumers = new ArrayList<>();
        boolean free, excludedSource, allowedSource;
        Node(K key) { super(1); this.key = key; }
        @Override List<? extends Vertex> dependencies() {
            // A private one-output producer has no sharing to preserve. Borrow
            // its inputs directly instead of adding an unnecessary SCC vertex.
            return sources.size() == 1 && sources.get(0).outputCount() == 1 ? sources.get(0).inputs : sources;
        }
        @Override void assign(int value) {
            if (rank < 0) { super.assign(value); ready.addLast(this); }
        }
    }
    final class Source extends Vertex {
        final GraphRecipe<K> recipe;
        final List<Node> inputs = new ArrayList<>();
        Node firstOutput;
        int firstOrdinal;
        List<Output> additionalOutputs;
        int remaining, depth, outputAt;
        Source(GraphRecipe<K> recipe) {
            super(0); this.recipe = recipe; remaining = recipe.inputs().size();
            if (remaining == 0) rank = 0;
        }
        @Override List<Node> dependencies() { return inputs; }
        int outputCount() { return 1 + (additionalOutputs == null ? 0 : additionalOutputs.size()); }
        int nextOrdinal() { return outputAt == 0 ? firstOrdinal : additionalOutputs.get(outputAt - 1).ordinal; }
        void emit() {
            Node output = outputAt == 0 ? firstOutput : additionalOutputs.get(outputAt - 1).node;
            outputAt++; output.assign(depth + 1);
        }
    }
    private final class Output {
        final Node node;
        final int ordinal;
        Output(Node node, int ordinal) { this.node = node; this.ordinal = ordinal; }
    }
    private final GraphCompiler<K> compiler;
    private final Set<K> external;
    private final Set<String> excluded;
    private final PlanningBudget budget;
    private final Iterator<K> roots;
    private final Map<K, Node> indexed = new LinkedHashMap<>();
    private final Map<GraphRecipe<K>, Source> shared = new IdentityHashMap<>();
    private final List<Node> nodes = new ArrayList<>();
    private final Deque<Node> ready = new ArrayDeque<>();
    private final PriorityQueue<Source> completedSources = new PriorityQueue<>(
            Comparator.comparingInt(Source::nextOrdinal));
    private final Deque<Vertex> dfs = new ArrayDeque<>(), component = new ArrayDeque<>();
    private int phase, next, serial, outputSerial;
    private long memory;

    private Node building, propagating;
    private Iterator<GraphRecipe<K>> producers;
    private Iterator<K> inputs;
    private Source linking;
    private int consumerAt;
    private Vertex closing;
    private List<Vertex> members;
    private int closePhase, memberAt, dependencyAt, componentDepth, componentIncrement;

    RefillProducerOrder(GraphCompiler<K> compiler, Collection<K> roots, Set<K> external,
                        Set<String> excluded, PlanningBudget budget) {
        this.compiler = compiler; this.external = external; this.excluded = excluded; this.budget = budget;
        // The owner supplies immutable witness keys. Discovery, including roots,
        // is charged and resumable after construction so close can release it.
        this.roots = roots.iterator();
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
        for (int i = 0; i < OPERATIONS_PER_STEP; i++) {
            budget.check();
            if (advance()) return true;
        }
        return false;
    }

    private boolean advance() {
        if (phase == 0) {
            if (roots.hasNext()) node(roots.next());
            else phase = 1;
        } else if (phase == 1) discover();
        else if (phase == 2) {
            if (next == nodes.size()) { next = 0; phase = 3; }
            else {
                Node node = nodes.get(next++);
                if (node.sources.isEmpty()) node.assign(0);
                else if (node.free) node.assign(1);
            }
        } else if (phase == 3 || phase == 5) {
            if (propagate()) {
                if (phase == 5) return true;
                next = 0; phase = 4;
            }
        } else components();
        return false;
    }

    private void discover() {
        if (inputs != null) {
            if (inputs.hasNext()) {
                reserve(48);
                Node dependency = node(inputs.next());
                linking.inputs.add(dependency); dependency.consumers.add(linking);
            }
            if (!inputs.hasNext()) { inputs = null; linking = null; }
        } else if (producers != null) {
            if (!producers.hasNext()) { producers = null; building = null; return; }
            var recipe = producers.next();
            if (excluded.contains(recipe.id())) { building.excludedSource = true; return; }
            building.allowedSource = true;
            if (recipe.outputs().getOrDefault(building.key, 0L) <=
                    recipe.inputs().getOrDefault(building.key, 0L)) return;
            Source source = shared.get(recipe);
            if (source == null) {
                reserve(192); source = new Source(recipe); shared.put(recipe, source);
                if (source.remaining != 0) { linking = source; inputs = recipe.inputs().keySet().iterator(); }
            }
            // Keep each material's original producer order and only its reached
            // positive-net outputs; do not activate unrelated coproducts.
            if (source.firstOutput == null) {
                reserve(32); source.firstOutput = building; source.firstOrdinal = outputSerial++;
            } else {
                reserve(64);
                if (source.additionalOutputs == null) source.additionalOutputs = new ArrayList<>();
                source.additionalOutputs.add(new Output(building, outputSerial++));
            }
            building.sources.add(source);
            building.free |= source.remaining == 0;
            if (!producers.hasNext()) { producers = null; building = null; }
        } else if (next < nodes.size()) {
            building = nodes.get(next++);
            var available = external.contains(building.key) ? List.<GraphRecipe<K>>of() : compiler.producers(building.key);
            if (!available.isEmpty()) producers = available.iterator();
        } else { next = 0; phase = 2; }
    }

    /** Each input readiness update and each reached output is visited once. */
    private boolean propagate() {
        if (propagating == null) {
            if (ready.isEmpty()) return true;
            propagating = ready.removeFirst(); consumerAt = 0;
        }
        // Consumer discovery follows first-output order. Only later
        // coproduct outputs need a merge queue; single-output sources keep
        // their original direct readiness-to-output fast path.
        Source next = consumerAt < propagating.consumers.size() ? propagating.consumers.get(consumerAt) : null;
        if (!completedSources.isEmpty() && (next == null || completedSources.peek().nextOrdinal() < next.firstOrdinal)) {
            Source source = completedSources.remove(); source.emit();
            if (source.outputAt < source.outputCount()) completedSources.add(source);
        } else if (next != null) {
            Source source = propagating.consumers.get(consumerAt++);
            source.depth = Math.max(source.depth, propagating.rank);
            if (--source.remaining == 0) {
                source.assign(source.depth); source.emit();
                if (source.outputAt < source.outputCount()) completedSources.add(source);
            }
        }
        if (consumerAt == propagating.consumers.size() && completedSources.isEmpty()) propagating = null;
        return false;
    }

    private void components() {
        if (closing != null) { closeComponent(); return; }
        // Only unranked material support needs cycle analysis. Sources reached
        // solely from already finite alternatives cannot change their ranks.
        if (dfs.isEmpty()) {
            if (next < nodes.size()) {
                Node node = nodes.get(next++);
                if (node.rank < 0 && node.index < 0) visit(node);
            } else phase = 5;
            return;
        }
        Vertex vertex = dfs.peek();
        if (vertex.edge < vertex.dependencies().size()) {
            Vertex child = vertex.dependencies().get(vertex.edge++);
            vertex.selfLoop |= child == vertex;
            if (child.rank >= 0) return;
            if (child.index < 0) visit(child);
            else if (child.onStack) vertex.low = Math.min(vertex.low, child.index);
            return;
        }
        dfs.pop();
        if (!dfs.isEmpty()) dfs.peek().low = Math.min(dfs.peek().low, vertex.low);
        if (vertex.low == vertex.index) {
            closing = vertex; members = new ArrayList<>(); closePhase = 0;
            componentDepth = componentIncrement = memberAt = dependencyAt = 0;
        }
    }

    private void closeComponent() {
        if (closePhase == 0) {
            Vertex member = component.pop(); member.onStack = false; members.add(member);
            componentIncrement = Math.max(componentIncrement, member.increment);
            if (member == closing) closePhase = 1;
        } else if (closePhase == 1) {
            if (memberAt == members.size()) { memberAt = 0; closePhase = 2; return; }
            var dependencies = members.get(memberAt).dependencies();
            if (dependencyAt < dependencies.size()) componentDepth = Math.max(componentDepth, dependencies.get(dependencyAt++).rank);
            else { memberAt++; dependencyAt = 0; }
        } else if (memberAt < members.size()) {
            Vertex member = members.get(memberAt++);
            member.cycle = members.size() > 1 || closing.selfLoop;
            member.componentId = closing.index;
            // Recipe-only components add no material level. A cyclic component
            // has one shared rank above its outside material feedstocks, just
            // like the unfactored material graph; AND edges add no fake depth.
            member.assign(componentDepth + componentIncrement);
        } else {
            // Do not clear a large array in one turn. Its references are already
            // owned by the graph; the temporary traversal list can be dropped.
            members = null; closing = null;
        }
    }
    private void visit(Vertex vertex) {
        vertex.index = vertex.low = serial++; vertex.onStack = true; dfs.push(vertex); component.push(vertex);
    }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    @Override public void close() { budget.release(memory); memory = 0; }
}
