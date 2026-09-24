package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Resumable selected-graph construction and iterative SCC analysis. */
public final class GraphCompilation<K> implements PlanningScheduler.Work<GraphCompiler.Compiled<K>> {

    private final GraphCompiler<K> compiler;
    private final Map<K, Integer> choices;
    private final Set<String> excluded;
    private final PlanningBudget budget;
    private final Map<K, GraphRecipe<K>> selected = new LinkedHashMap<>();
    private final Map<String, GraphRecipe<K>> recipes = new LinkedHashMap<>();
    private final Set<K> seen = new HashSet<>();
    private final Deque<K> pending = new ArrayDeque<>();
    private final Map<String, Integer> ids = new HashMap<>();
    private final List<GraphRecipe<K>> nodes = new ArrayList<>();
    private final List<Ints> out = new ArrayList<>(), in = new ArrayList<>();
    private final List<GraphCompiler.Region<K>> regions = new ArrayList<>();
    private final Ints finish = new Ints();
    private Iterator<GraphRecipe<K>> candidates;
    private K currentKey;
    private int candidateIndex;
    private Iterator<GraphRecipe<K>> registering;
    private Iterator<K> edgeInputs;
    private int phase, cursor, depth, root, finishRoot;
    private int[] stack, nextEdge;
    private byte[] visited;
    private int[][] forward, reverse;
    private List<GraphRecipe<K>> members;
    private CompletableFuture<List<EdgeBatch>> parallelEdges;
    private List<EdgeBatch> merging;
    private int mergeBatch, mergeNode, mergeEdge;
    private GraphCompiler.Compiled<K> result;

    GraphCompilation(GraphCompiler<K> compiler, K target, Set<K> additional, Map<K, Integer> choices,
                     Set<String> excluded, PlanningBudget budget) {
        this.compiler = compiler;
        this.choices = Map.copyOf(choices);
        this.excluded = Set.copyOf(excluded);
        this.budget = budget;
        pending.add(target);
        pending.addAll(additional);
    }

    @Override
    public boolean advance(PlanningScheduler.Slice slice) {
        while (slice.next()) {
            if (phase == 2 && nodes.size() >= 512 && slice.parallelism() > 1 && edgeInputs == null && merging == null) {
                if (parallelEdges == null && cursor < nodes.size()) {
                    List<Supplier<EdgeBatch>> partitions = new ArrayList<>();
                    for (int i = 0; i < slice.parallelism() && cursor < nodes.size(); i++) {
                        int start = cursor, end = Math.min(nodes.size(), start + 64);
                        cursor = end;
                        partitions.add(() -> collectEdges(start, end));
                    }
                    parallelEdges = slice.fork(partitions);
                    return false;
                }
                if (parallelEdges != null) {
                    if (!parallelEdges.isDone()) return false;
                    merging = parallelEdges.join();
                    parallelEdges = null;
                    mergeBatch = mergeNode = mergeEdge = 0;
                }
            }
            if (step()) return true;
        }
        return false;
    }

    @Override
    public CompletableFuture<?> waitingFor() {
        return parallelEdges;
    }

    @Override
    public GraphCompiler.Compiled<K> result() {
        if (result == null) throw new IllegalStateException("Graph is still being built");
        return result;
    }

    /** One bounded traversal operation, also used by the synchronous test/reference path. */
    public boolean step() {
        budget.check();
        switch (phase) {
            case 0 -> discover();
            case 1 -> register();
            case 2 -> edges();
            case 3 -> freezeEdges();
            case 4 -> finishOrder();
            case 5 -> components();
            case 6 -> {
                result = new GraphCompiler.Compiled<>(Collections.unmodifiableMap(recipes),
                        Collections.unmodifiableMap(selected), List.copyOf(regions));
                phase = 7;
            }
            default -> {
                return true;
            }
        }
        return result != null;
    }

    private void discover() {
        budget.phase(PlanningBudget.Phase.BUILD);
        if (candidates != null) {
            if (!candidates.hasNext()) {
                candidates = null;
                return;
            }
            GraphRecipe<K> recipe = candidates.next();
            if (excluded.contains(recipe.id()) || candidateIndex-- > 0) return;
            selected.put(currentKey, recipe);
            if (recipes.putIfAbsent(recipe.id(), recipe) == null) {
                budget.reserve(384);
                pending.addAll(recipe.inputs().keySet());
            }
            candidates = null;
            return;
        }
        if (pending.isEmpty()) {
            registering = recipes.values().iterator();
            phase = 1;
            return;
        }
        currentKey = pending.removeFirst();
        if (!seen.add(currentKey)) return;
        budget.reserve(64);
        candidates = compiler.producers(currentKey).iterator();
        candidateIndex = choices.getOrDefault(currentKey, 0);
    }

    private void register() {
        if (registering.hasNext()) {
            GraphRecipe<K> recipe = registering.next();
            ids.put(recipe.id(), nodes.size());
            nodes.add(recipe);
            out.add(new Ints());
            in.add(new Ints());
        } else {
            stack = new int[nodes.size()];
            nextEdge = new int[nodes.size()];
            visited = new byte[nodes.size()];
            forward = new int[nodes.size()][];
            reverse = new int[nodes.size()][];
            phase = 2;
        }
    }

    private void edges() {
        if (merging != null) {
            if (mergeBatch >= merging.size()) {
                merging = null;
                return;
            }
            EdgeBatch batch = merging.get(mergeBatch);
            if (mergeNode >= batch.edges.length) {
                mergeBatch++;
                mergeNode = mergeEdge = 0;
                return;
            }
            int[] edges = batch.edges[mergeNode];
            if (mergeEdge >= edges.length) {
                mergeNode++;
                mergeEdge = 0;
                return;
            }
            int from = batch.start + mergeNode, to = edges[mergeEdge++];
            out.get(from).add(to);
            in.get(to).add(from);
            return;
        }
        if (cursor >= nodes.size()) {
            cursor = 0;
            phase = 3;
            return;
        }
        if (edgeInputs == null) edgeInputs = nodes.get(cursor).inputs().keySet().iterator();
        if (!edgeInputs.hasNext()) {
            edgeInputs = null;
            cursor++;
            return;
        }
        GraphRecipe<K> producer = selected.get(edgeInputs.next());
        if (producer == null) return;
        int to = ids.get(producer.id());
        budget.reserve(24);
        out.get(cursor).add(to);
        in.get(to).add(cursor);
    }

    private EdgeBatch collectEdges(int start, int end) {
        int[][] edges = new int[end - start][];
        for (int index = start; index < end; index++) {
            Ints targets = new Ints();
            for (K input : nodes.get(index).inputs().keySet()) {
                budget.check();
                GraphRecipe<K> producer = selected.get(input);
                if (producer != null) {
                    budget.reserve(24);
                    targets.add(ids.get(producer.id()));
                }
            }
            edges[index - start] = targets.array();
        }
        return new EdgeBatch(start, edges);
    }

    private void freezeEdges() {
        if (cursor == nodes.size()) {
            cursor = 0;
            phase = 4;
            budget.phase(PlanningBudget.Phase.ANALYSE);
            return;
        }
        forward[cursor] = out.get(cursor).array();
        reverse[cursor] = in.get(cursor).array();
        out.set(cursor, null);
        in.set(cursor, null);
        cursor++;
    }

    private void finishOrder() {
        if (depth == 0) {
            if (root == nodes.size()) {
                finishRoot = finish.size - 1;
                phase = 5;
                return;
            }
            int id = root++;
            if (visited[id] != 0) return;
            visited[id] = 1;
            stack[depth++] = id;
            return;
        }
        int id = stack[depth - 1];
        if (nextEdge[id] == forward[id].length) {
            finish.add(id);
            depth--;
            return;
        }
        int child = forward[id][nextEdge[id]++];
        if (visited[child] == 0) {
            visited[child] = 1;
            stack[depth++] = child;
        }
    }

    private void components() {
        if (depth == 0) {
            if (members != null) {
                int member = ids.get(members.get(0).id());
                boolean self = false;
                for (int to : forward[member]) if (member == to) {
                    self = true;
                    break;
                }
                regions.add(new GraphCompiler.Region<>(List.copyOf(members), members.size() > 1 || self));
                members = null;
                return;
            }
            if (finishRoot < 0) {
                phase = 6;
                return;
            }
            int id = finish.data[finishRoot--];
            if (visited[id] == 2) return;
            members = new ArrayList<>();
            visitReverse(id);
            return;
        }
        int id = stack[depth - 1];
        if (nextEdge[id] == reverse[id].length) {
            depth--;
            return;
        }
        int child = reverse[id][nextEdge[id]++];
        if (visited[child] != 2) visitReverse(child);
    }

    private void visitReverse(int id) {
        visited[id] = 2;
        nextEdge[id] = 0;
        stack[depth++] = id;
        members.add(nodes.get(id));
    }

    private record EdgeBatch(int start, int[][] edges) {}

    private static final class Ints {

        private int[] data = new int[4];
        private int size;

        void add(int value) {
            if (size == data.length) data = Arrays.copyOf(data, Math.multiplyExact(size, 2));
            data[size++] = value;
        }

        int[] array() {
            return Arrays.copyOf(data, size);
        }
    }
}
