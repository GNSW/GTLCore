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
public final class GraphCompilation<K> implements PlanningScheduler.Work<GraphCompiler.Compiled<K>>, AutoCloseable {

    private final GraphCompiler<K> compiler;
    private final Map<K, Integer> choices;
    private final Set<String> excluded;
    private final PlanningBudget budget;
    private final Map<K, GraphRecipe<K>> selected = new LinkedHashMap<>();
    private final Map<String, GraphRecipe<K>> recipes = new LinkedHashMap<>();
    private final Set<K> seen = new HashSet<>();
    private final Deque<K> pending = new ArrayDeque<>();
    private final Map<String, Integer> ids = new HashMap<>();
    private final Map<K, List<Integer>> outputIds = new HashMap<>();
    private final Map<Integer, List<Integer>> resourceHubs = new HashMap<>();
    private final List<GraphRecipe<K>> nodes = new ArrayList<>();
    private final List<Ints> out = new ArrayList<>(), in = new ArrayList<>();
    private final List<GraphCompiler.Region<K>> regions = new ArrayList<>();
    private final Ints finish = new Ints();
    private Iterator<GraphRecipe<K>> candidates;
    private K currentKey;
    private int candidateIndex;
    private Iterator<GraphRecipe<K>> registering;
    private Iterator<Map.Entry<K, List<Integer>>> sharedOutputs;
    private Iterator<K> edgeInputs;
    private Iterator<Integer> edgeProducers;
    private int phase, cursor, depth, root, finishRoot;
    private int[] stack, nextEdge;
    private byte[] visited;
    private int[][] forward, reverse;
    private List<GraphRecipe<K>> members;
    private int componentSize;
    private CompletableFuture<List<EdgeBatch>> parallelEdges;
    private List<EdgeBatch> merging;
    private int mergeBatch, mergeNode, mergeEdge;
    private GraphCompiler.Compiled<K> result;
    private GraphCatalogIndex<K> catalogIndex;
    private GraphCatalogIndex.Builder<K> indexing;
    private int catalogCursor, packedInput, packedEdge;
    private long selectedIncidences;
    private boolean indexedSelection;
    private long indexedMemory;
    private final List<GraphCatalogIndex.Ports> ports = new ArrayList<>();
    private Ints[] packedOutputs;
    private int[][] packedTargets;
    private final Map<Integer, int[]> packedHubs = new HashMap<>();
    private int[] packedEdges;
    private static final int[] EMPTY = new int[0];

    GraphCompilation(GraphCompiler<K> compiler, K target, Set<K> additional, Map<K, Integer> choices,
                     Set<String> excluded, PlanningBudget budget) {
        this.compiler = compiler;
        this.choices = Map.copyOf(choices);
        this.excluded = Set.copyOf(excluded);
        this.budget = budget;
        catalogIndex = compiler.catalogIndex();
        indexedSelection = catalogIndex != null;
        pending.add(target);
        pending.addAll(additional);
    }

    @Override
    public boolean advance(PlanningScheduler.Slice slice) {
        while (slice.next()) {
            if (phase == 2 && nodes.size() >= 512 && slice.parallelism() > 1 && edgeInputs == null && edgeProducers == null &&
                    packedEdges == null && packedInput == 0 && merging == null) {
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
                close();
            }
            case 8 -> registerHubs();
            case 9 -> indexCatalog();
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
                if (catalogIndex != null) {
                    var packed = catalogIndex.ports(recipe);
                    if (packed == null) indexedSelection = false;
                    else selectedIncidences += packed.inputs().length + packed.physicalOutputs().length;
                }
            }
            candidates = null;
            return;
        }
        if (pending.isEmpty()) {
            if (catalogIndex == null && compiler.reuseCatalogIndex() && 4L * recipes.size() >= compiler.catalog().size()) {
                indexing = new GraphCatalogIndex.Builder<>(compiler.catalog(), budget);
                phase = 9;
            } else prepareRegistration();
            return;
        }
        currentKey = pending.removeFirst();
        if (!seen.add(currentKey)) return;
        budget.reserve(64);
        candidates = compiler.producers(currentKey).iterator();
        candidateIndex = choices.getOrDefault(currentKey, 0);
    }

    private void indexCatalog() {
        if (!indexing.declined() && catalogCursor < compiler.catalog().size()) {
            indexing.add(compiler.catalog().get(catalogCursor), catalogCursor);
            catalogCursor++;
            return;
        }
        if (!indexing.step()) return;
        catalogIndex = indexing.result();
        if (catalogIndex != null) compiler.rememberCatalogIndex(catalogIndex);
        indexing.close();
        indexing = null;
        prepareRegistration();
    }

    private void prepareRegistration() {
        if (catalogIndex != null) {
            if (!indexedSelection) {
                selectedIncidences = 0;
                for (var recipe : recipes.values()) {
                    budget.check();
                    var packed = catalogIndex.ports(recipe);
                    if (packed == null) {
                        catalogIndex = null;
                        break;
                    }
                    selectedIncidences += packed.inputs().length + packed.physicalOutputs().length;
                }
            }
            // A tiny closure should not scan a whole network's resource mask.
            if (catalogIndex != null && catalogIndex.resourceCount() <= Math.max(256, 4 * selectedIncidences)) {
                long bytes = 1024L + 104L * catalogIndex.resourceCount() + 16L * recipes.size();
                if (bytes <= budget.availableBytes() / 8 && budget.tryReserve(bytes)) {
                    indexedMemory = bytes;
                    packedOutputs = new Ints[catalogIndex.resourceCount()];
                    packedTargets = new int[catalogIndex.resourceCount()][];
                    budget.note("graph_catalog", "packed_selected_mask; recipes=" + recipes.size() + "; resources=" + catalogIndex.resourceCount());
                }
            }
        }
        registering = recipes.values().iterator();
        phase = 1;
    }

    private void register() {
        if (registering.hasNext()) {
            GraphRecipe<K> recipe = registering.next();
            ids.put(recipe.id(), nodes.size());
            nodes.add(recipe);
            out.add(new Ints());
            in.add(new Ints());
            if (packedTargets != null) {
                var packed = catalogIndex.ports(recipe);
                ports.add(packed);
                for (int key : packed.physicalOutputs()) {
                    budget.check();
                    if (packedOutputs[key] == null) packedOutputs[key] = new Ints();
                    packedOutputs[key].add(nodes.size() - 1);
                    // Keep the cold traversal's material order without its
                    // boxed producer lists. Hub order can affect later plan
                    // heuristics even when the SCC partition is unchanged.
                    outputIds.putIfAbsent(catalogIndex.resource(key), List.of());
                }
                return;
            }
            for (K key : recipe.executionOutputs().keySet()) {
                budget.check();
                budget.reserve(48);
                outputIds.computeIfAbsent(key, ignored -> new ArrayList<>()).add(nodes.size() - 1);
            }
        } else {
            sharedOutputs = outputIds.entrySet().iterator();
            phase = 8;
        }
    }

    private void registerHubs() {
        if (packedTargets != null && sharedOutputs.hasNext()) {
            int resource = catalogIndex.resourceId(sharedOutputs.next().getKey());
            int[] targets = packedOutputs[resource].array();
            packedOutputs[resource] = null;
            if (targets.length > 1) {
                budget.reserve(160);
                int id = nodes.size();
                packedHubs.put(id, targets);
                nodes.add(null);
                ports.add(null);
                out.add(new Ints());
                in.add(new Ints());
                targets = new int[] { id };
            }
            packedTargets[resource] = targets;
            return;
        }
        if (sharedOutputs.hasNext()) {
            var output = sharedOutputs.next();
            if (output.getValue().size() > 1) {
                // Factor consumer -> producer cross products through one
                // material node instead of allocating a quadratic set of edges.
                budget.reserve(160);
                int id = nodes.size();
                resourceHubs.put(id, output.getValue());
                output.setValue(List.of(id));
                nodes.add(null);
                out.add(new Ints());
                in.add(new Ints());
            }
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
        if (packedTargets != null) {
            packedEdges();
            return;
        }
        if (nodes.get(cursor) == null && edgeProducers == null)
            edgeProducers = resourceHubs.get(cursor).iterator();
        if (edgeProducers != null) {
            if (edgeProducers.hasNext()) {
                int to = edgeProducers.next();
                budget.reserve(24);
                out.get(cursor).add(to);
                in.get(to).add(cursor);
                return;
            }
            edgeProducers = null;
            if (nodes.get(cursor) == null) {
                cursor++;
                return;
            }
        }
        if (edgeInputs == null) edgeInputs = nodes.get(cursor).inputs().keySet().iterator();
        if (!edgeInputs.hasNext()) {
            edgeInputs = null;
            cursor++;
            return;
        }
        // Selection chooses which recipes to include, not which of their
        // physical outputs exist. Shared catalyst returns from another selected
        // recipe must join the same SCC as every consumer of that catalyst.
        edgeProducers = outputIds.getOrDefault(edgeInputs.next(), List.of()).iterator();
    }

    private void packedEdges() {
        if (packedEdges != null) {
            if (packedEdge < packedEdges.length) {
                int to = packedEdges[packedEdge++];
                budget.reserve(24);
                out.get(cursor).add(to);
                in.get(to).add(cursor);
                return;
            }
            packedEdges = null;
            if (nodes.get(cursor) == null) {
                cursor++;
                return;
            }
        }
        if (nodes.get(cursor) == null) packedEdges = packedHubs.get(cursor);
        else {
            int[] inputs = ports.get(cursor).inputs();
            if (packedInput == inputs.length) {
                packedInput = 0;
                cursor++;
                return;
            }
            packedEdges = packedTargets(inputs[packedInput++]);
        }
        packedEdge = 0;
    }

    private EdgeBatch collectEdges(int start, int end) {
        int[][] edges = new int[end - start][];
        for (int index = start; index < end; index++) {
            Ints targets = new Ints();
            if (packedTargets != null) {
                if (nodes.get(index) == null) {
                    for (int producer : packedHubs.get(index)) {
                        budget.check();
                        budget.reserve(24);
                        targets.add(producer);
                    }
                } else for (int input : ports.get(index).inputs()) {
                    budget.check();
                    for (int producer : packedTargets(input)) {
                        budget.check();
                        budget.reserve(24);
                        targets.add(producer);
                    }
                }
                edges[index - start] = targets.array();
                continue;
            }
            if (nodes.get(index) == null) {
                for (int producer : resourceHubs.get(index)) {
                    budget.check();
                    budget.reserve(24);
                    targets.add(producer);
                }
                edges[index - start] = targets.array();
                continue;
            }
            for (K input : nodes.get(index).inputs().keySet()) {
                budget.check();
                for (int producer : outputIds.getOrDefault(input, List.of())) {
                    budget.check();
                    budget.reserve(24);
                    targets.add(producer);
                }
            }
            edges[index - start] = targets.array();
        }
        return new EdgeBatch(start, edges);
    }

    private int[] packedTargets(int input) {
        int[] producers = packedTargets[input];
        return producers == null ? EMPTY : producers;
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
                if (members.isEmpty()) {
                    members = null;
                    return;
                }
                int member = ids.get(members.get(0).id());
                boolean self = false;
                for (int to : forward[member]) if (member == to) {
                    self = true;
                    break;
                }
                regions.add(new GraphCompiler.Region<>(List.copyOf(members), componentSize > 1 || self));
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
            componentSize = 0;
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
        componentSize++;
        if (nodes.get(id) != null) members.add(nodes.get(id));
    }

    private record EdgeBatch(int start, int[][] edges) {}

    @Override
    public void close() {
        if (indexing != null) indexing.close();
        indexing = null;
        budget.release(indexedMemory);
        indexedMemory = 0;
    }

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
