package org.cgse.core;

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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Resumable selected-graph construction and iterative SCC analysis. */
public final class GraphCompilation<K> implements PlanningScheduler.Work<GraphCompiler.Compiled<K>>, AutoCloseable {

    private final GraphCompiler<K> compiler;
    private final Map<K, Integer> choices;
    private final Set<String> excluded;
    private final Set<K> stockLeaves;
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
    private GraphTopology<K> topology;
    private List<GraphCompiler.Region<K>> sharedRegions;
    private GraphCatalogIndex<K> catalogIndex;
    private GraphCatalogIndex.Builder<K> indexing;
    private int catalogCursor, packedInput, packedEdge;
    private long selectedIncidences, selectedOutputs;
    private boolean indexedSelection;
    private long indexedMemory;
    private long memory;
    private boolean closed;
    private final List<GraphCatalogIndex.Ports> ports = new ArrayList<>();
    private Ints[] packedOutputs;
    private int[] firstProducers;
    private int[][] packedTargets;
    private final Map<Integer, int[]> packedHubs = new HashMap<>();
    private int[] packedEdges;
    private static final int[] EMPTY = new int[0];

    GraphCompilation(GraphCompiler<K> compiler, K target, Set<K> additional, Map<K, Integer> choices,
                     Set<String> excluded, PlanningBudget budget) {
        this(compiler, target, additional, choices, excluded, Set.of(), budget);
    }

    GraphCompilation(GraphCompiler<K> compiler, K target, Set<K> additional, Map<K, Integer> choices,
                     Set<String> excluded, Set<K> stockLeaves, PlanningBudget budget) {
        this.compiler = compiler;
        this.choices = Map.copyOf(choices);
        this.excluded = Set.copyOf(excluded);
        this.stockLeaves = Set.copyOf(stockLeaves);
        this.budget = budget;
        catalogIndex = compiler.catalogIndex();
        indexedSelection = catalogIndex != null;
        enqueue(target);
        for (K key : additional) enqueue(key);
    }

    @Override
    public boolean advance(PlanningScheduler.Slice slice) {
        while (slice.nextCompilation()) {
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
        budget.compilationCheck();
        switch (phase) {
            case 0 -> discover();
            case 1 -> register();
            case 2 -> edges();
            case 3 -> freezeEdges();
            case 4 -> finishOrder();
            case 5 -> components();
            case 6 -> {
                var partition = sharedRegions == null ? List.copyOf(regions) : sharedRegions;
                result = new GraphCompiler.Compiled<>(Collections.unmodifiableMap(recipes),
                        Collections.unmodifiableMap(selected), partition);
                if (sharedRegions == null) compiler.rememberTopology(topology, partition);
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
                reserve(384);
                for (K key : recipe.inputs().keySet()) enqueue(key);
                if (catalogIndex != null) {
                    var packed = catalogIndex.ports(recipe);
                    if (packed == null) indexedSelection = false;
                    else {
                        selectedIncidences += packed.inputs().length + packed.physicalOutputs().length;
                        selectedOutputs += packed.physicalOutputs().length;
                    }
                }
            }
            candidates = null;
            return;
        }
        if (pending.isEmpty()) {
            // Equal selected recipe order has exactly the same edges and SCC
            // traversal. Material/source choices remain private to this graph;
            // stock boundaries can only reuse the immutable partition.
            if (recipes.size() >= 2 && recipes.size() <= 16384) {
                long bytes = 128L + 16L * recipes.size();
                if (budget.tryReserve(bytes)) {
                    memory += bytes;
                    topology = new GraphTopology<>(recipes, budget);
                    sharedRegions = compiler.topology(topology);
                    if (sharedRegions != null) {
                        budget.phase(PlanningBudget.Phase.ANALYSE);
                        budget.note("graph_topology", "reused; recipes=" + recipes.size() + "; regions=" + sharedRegions.size());
                        phase = 6;
                        return;
                    }
                }
            }
            if (catalogIndex == null && compiler.reuseCatalogIndex() && 4L * recipes.size() >= compiler.catalog().size()) {
                indexing = new GraphCatalogIndex.Builder<>(compiler.catalog(), budget);
                phase = 9;
            } else prepareRegistration();
            return;
        }
        currentKey = pending.removeFirst();
        reserve(64);
        if (stockLeaves.contains(currentKey)) return;
        var sources = compiler.producers(currentKey);
        candidateIndex = choices.getOrDefault(currentKey, 0);
        if (excluded.isEmpty()) {
            // The normal branch changes one source ordinal. Indexed lookup
            // avoids rescanning all earlier alternatives on every compilation.
            candidates = sources.listIterator(Math.min(sources.size(), Math.max(0, candidateIndex)));
            candidateIndex = 0;
        } else candidates = sources.iterator();
    }

    private void enqueue(K key) {
        if (seen.add(key)) {
            pending.addLast(key);
        }
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
                selectedOutputs = 0;
                for (var recipe : recipes.values()) {
                    budget.compilationCheck();
                    var packed = catalogIndex.ports(recipe);
                    if (packed == null) {
                        catalogIndex = null;
                        break;
                    }
                    selectedIncidences += packed.inputs().length + packed.physicalOutputs().length;
                    selectedOutputs += packed.physicalOutputs().length;
                }
            }
            // A tiny closure should not scan a whole network's resource mask.
            if (catalogIndex != null && catalogIndex.resourceCount() <= Math.max(256, 4 * selectedIncidences)) {
                // Most materials have one selected producer. Store it inline;
                // allocate a growable list only when a second producer appears.
                long bytes = 1024L + 72L * catalogIndex.resourceCount() + 16L * selectedOutputs;
                if (bytes <= budget.availableBytes() / 8 && budget.tryReserve(bytes)) {
                    indexedMemory = bytes;
                    packedOutputs = new Ints[catalogIndex.resourceCount()];
                    firstProducers = new int[catalogIndex.resourceCount()];
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
                    budget.compilationCheck();
                    if (firstProducers[key] == 0) firstProducers[key] = nodes.size();
                    else {
                        if (packedOutputs[key] == null) {
                            packedOutputs[key] = new Ints();
                            packedOutputs[key].add(firstProducers[key] - 1);
                        }
                        packedOutputs[key].add(nodes.size() - 1);
                    }
                    // Keep the cold traversal's material order without its
                    // boxed producer lists. Hub order can affect later plan
                    // heuristics even when the SCC partition is unchanged.
                    outputIds.putIfAbsent(catalogIndex.resource(key), List.of());
                }
                return;
            }
            for (K key : recipe.executionOutputs().keySet()) {
                budget.compilationCheck();
                reserve(48);
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
            int[] targets = packedOutputs[resource] == null ? new int[] { firstProducers[resource] - 1 } : packedOutputs[resource].array();
            packedOutputs[resource] = null;
            if (targets.length > 1) {
                reserve(160);
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
                reserve(160);
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
                reserve(24);
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
                reserve(24);
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
                        budget.compilationCheck();
                        reserve(24);
                        targets.add(producer);
                    }
                } else for (int input : ports.get(index).inputs()) {
                    budget.compilationCheck();
                    for (int producer : packedTargets(input)) {
                        budget.compilationCheck();
                        reserve(24);
                        targets.add(producer);
                    }
                }
                edges[index - start] = targets.array();
                continue;
            }
            if (nodes.get(index) == null) {
                for (int producer : resourceHubs.get(index)) {
                    budget.compilationCheck();
                    reserve(24);
                    targets.add(producer);
                }
                edges[index - start] = targets.array();
                continue;
            }
            for (K input : nodes.get(index).inputs().keySet()) {
                budget.compilationCheck();
                for (int producer : outputIds.getOrDefault(input, List.of())) {
                    budget.compilationCheck();
                    reserve(24);
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
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (indexing != null) indexing.close();
        indexing = null;
        budget.release(indexedMemory + memory);
        indexedMemory = 0;
        memory = 0;
    }

    private synchronized void reserve(long bytes) {
        // Edge partitions may still be unwinding after their owner is closed.
        // Do not let them add reservations after the final release.
        if (closed) throw new CancellationException("Graph compilation closed");
        budget.reserve(bytes);
        memory += bytes;
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
