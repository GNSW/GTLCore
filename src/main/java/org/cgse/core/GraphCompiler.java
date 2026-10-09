// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.IdentityHashMap;

/** Immutable catalog index. Inventory changes do not invalidate the compiled structure. */
public final class GraphCompiler<K> {

    private final List<GraphRecipe<K>> catalog;
    private final Map<K, List<GraphRecipe<K>>> producers;
    private final Map<CacheKey<K>, Compiled<K>> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<CountKey<K>, CountCatalog<K>> countCatalogs = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<ClosureKey<K>, CountClosure<K>> countClosures = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<ClosureKey<K>, ClosureSize> countClosureLimits = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<GraphTopology<K>, List<Region<K>>> topologies = new LinkedHashMap<>(16, 0.75f, true);
    private long topologyWeight;
    private long countClosureWeight;
    private long countCatalogWeight;
    private boolean countCatalogRequested, countCatalogReusable;
    private GraphCatalogIndex<K> catalogIndex;
    private GraphSourceIndex<K> sourceIndex;
    private boolean catalogIndexRequested, catalogIndexReusable;
    private final List<QuantityCertificate<K>> quantityCertificates = new ArrayList<>();
    final CountSessions countSessions = new CountSessions();
    final CountRecoveryTemplates<K> recoveryTemplates = new CountRecoveryTemplates<>();
    private final List<DemandEntry<K>> demandPrograms = new ArrayList<>();
    private final Map<String, long[]> cacheCounters = new LinkedHashMap<>();
    private long activeSearches;

    /** Logical residency estimates, not an object census or a JVM heap limit. */
    public record CacheEntry(long entries, long weight, long estimatedBytes, long hits, long misses, long evictions) {
        CacheEntry plus(CacheEntry other) {
            return new CacheEntry(sum(entries, other.entries), sum(weight, other.weight), sum(estimatedBytes, other.estimatedBytes),
                    sum(hits, other.hits), sum(misses, other.misses), sum(evictions, other.evictions));
        }
    }

    /** Includes retained macro compilers once by identity. Concurrent observations are not atomic across compilers. */
    public record CacheMetrics(int compilers, long activeSearches, Map<String, CacheEntry> caches) {
        public CacheMetrics { caches = Map.copyOf(caches); }
        public long estimatedBytes() {
            return caches.values().stream().mapToLong(CacheEntry::estimatedBytes).reduce(0, GraphCompiler::sum);
        }
    }

    private static long sum(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }

    private <T> T lookup(String name, T value) {
        long[] counters = cacheCounters.computeIfAbsent(name, ignored -> new long[3]);
        int at = value == null ? 1 : 0;
        counters[at] = sum(counters[at], 1);
        return value;
    }

    private void evicted(String name) {
        long[] counters = cacheCounters.computeIfAbsent(name, ignored -> new long[3]);
        counters[2] = sum(counters[2], 1);
    }

    private CacheEntry entry(String name, long entries, long weight, long bytes) {
        long[] counters = cacheCounters.get(name);
        return new CacheEntry(entries, weight, bytes, counters == null ? 0 : counters[0],
                counters == null ? 0 : counters[1], counters == null ? 0 : counters[2]);
    }

    synchronized void searchOpened() { activeSearches++; }
    synchronized void searchClosed() {
        if (activeSearches == 0) throw new IllegalStateException("Unbalanced compiler search lifetime");
        activeSearches--;
    }

    private synchronized CacheMetrics localCacheMetrics() {
        Map<String, CacheEntry> values = new LinkedHashMap<>();
        long graphWeight = 0, graphBytes = 0;
        for (var graph : cache.values()) {
            graphWeight += graph.recipes().size() + graph.selected().size();
            graphBytes += graph.estimatedBytes();
        }
        values.put("compiled", entry("compiled", cache.size(), graphWeight, graphBytes));
        values.put("topology", entry("topology", topologies.size(), topologyWeight, 64L * topologyWeight));
        values.put("count_catalog", entry("count_catalog", countCatalogs.size(), countCatalogWeight, 128L * countCatalogWeight));
        values.put("count_closure", entry("count_closure", countClosures.size(), countClosureWeight, 64L * countClosureWeight));
        long cutoffWeight = countClosureLimits.keySet().stream().mapToLong(k -> 1L + k.additional.size() + k.excluded.size()).sum();
        values.put("closure_cutoff", entry("closure_cutoff", countClosureLimits.size(), cutoffWeight, 128L * cutoffWeight));
        values.put("source_index", entry("source_index", sourceIndex == null ? 0 : 1,
                sourceIndex == null ? 0 : sourceIndex.recipes.size(), sourceIndex == null ? 0 : sourceIndex.bytes));
        long packedBytes = catalogIndex == null ? 0 : catalogIndex.estimatedBytes();
        values.put("catalog_index", entry("catalog_index", catalogIndex == null ? 0 : 1,
                catalogIndex == null ? 0 : catalogIndex.resourceCount(), packedBytes));
        long demandWeight = 0, demandBytes = 0;
        for (var demand : demandPrograms) {
            demandWeight += demand.graph.recipes().size() + (demand.program == null ? 0 : demand.program.weight);
            demandBytes += demand.graph.estimatedBytes() + (demand.program == null ? 0 : 64L * demand.program.weight);
        }
        values.put("demand_program", entry("demand_program", demandPrograms.size(), demandWeight, demandBytes));
        long certificateWeight = quantityCertificates.stream().mapToLong(c -> c.weights.size() + c.excluded.size()).sum();
        values.put("quantity_certificate", entry("quantity_certificate", quantityCertificates.size(), certificateWeight, 192L * certificateWeight));
        // Catalog recipes and user keys are shared immutable inputs. Count the
        // index containers here, not arbitrary host objects reachable from them.
        long catalogWeight = catalog.size() + producers.size() + producers.values().stream().mapToLong(List::size).sum();
        values.put("catalog", new CacheEntry(1, catalogWeight, 64L * catalogWeight, 0, 0, 0));
        return new CacheMetrics(1, activeSearches, values);
    }

    public CacheMetrics cacheMetrics() {
        Map<String, CacheEntry> aggregate = new LinkedHashMap<>();
        var seen = new IdentityHashMap<GraphCompiler<?>, Boolean>();
        var pending = new ArrayDeque<GraphCompiler<?>>();
        pending.add(this);
        long active = 0;
        while (!pending.isEmpty()) {
            var compiler = pending.removeFirst();
            if (seen.put(compiler, true) != null) continue;
            // Never hold a parent cache monitor while entering a child compiler.
            var local = compiler.localCacheMetrics();
            active = sum(active, local.activeSearches);
            local.caches.forEach((name, value) -> aggregate.merge(name, value, CacheEntry::plus));
            aggregate.merge("count_session", compiler.countSessions.cacheMetrics(), CacheEntry::plus);
            var recovery = compiler.recoveryTemplates.cacheMetrics();
            aggregate.merge("recovery_template", recovery.entry(), CacheEntry::plus);
            pending.addAll(recovery.compilers());
        }
        return new CacheMetrics(seen.size(), active, aggregate);
    }

    private record DemandEntry<K>(Compiled<K> graph, GraphDemandProgram<K> program) {}

    /** Compile only an observed repeated graph; failed optional preparation is never cached as final. */
    GraphDemandProgram<K> demandProgram(Compiled<K> graph, PlanningBudget budget) {
        synchronized (this) {
            int found = -1;
            for (int i = 0; i < demandPrograms.size(); i++) if (demandPrograms.get(i).graph.regions() == graph.regions()) {
                found = i;
                break;
            }
            if (found < 0) {
                lookup("demand_program", null);
                demandPrograms.add(0, new DemandEntry<>(graph, null));
                trimDemandPrograms();
                return null;
            }
            var entry = demandPrograms.remove(found);
            demandPrograms.add(0, entry);
            if (lookup("demand_program", entry.program) != null) return entry.program.forGraph(graph);
        }
        var program = GraphDemandProgram.create(graph, budget);
        if (program == null) return null;
        synchronized (this) {
            demandPrograms.removeIf(entry -> entry.graph.regions() == graph.regions());
            demandPrograms.add(0, new DemandEntry<>(graph, program));
            trimDemandPrograms();
        }
        budget.note("demand_program", "compiled; regions=" + graph.regions().size() + "; weight=" + program.weight);
        return program;
    }

    private void trimDemandPrograms() {
        long weight = 0;
        for (var entry : demandPrograms) weight += entry.graph.recipes().size() + (entry.program == null ? 0 : entry.program.weight);
        while (demandPrograms.size() > 1 && (demandPrograms.size() > 8 || weight > 65536)) {
            var removed = demandPrograms.remove(demandPrograms.size() - 1);
            evicted("demand_program");
            weight -= removed.graph.recipes().size() + (removed.program == null ? 0 : removed.program.weight);
        }
    }

    public GraphCompiler(List<GraphRecipe<K>> catalog) {
        this.catalog = List.copyOf(catalog);
        this.producers = new LinkedHashMap<>();
        for (GraphRecipe<K> recipe : catalog) {
            for (K output : recipe.executionOutputs().keySet()) producers.computeIfAbsent(output, key -> new ArrayList<>()).add(recipe);
        }
        producers.replaceAll((key, values) -> List.copyOf(values));
    }

    GraphCompiler(List<GraphRecipe<K>> catalog, Map<K, List<GraphRecipe<K>>> producers) {
        this.catalog = catalog;
        this.producers = producers;
    }

    public List<GraphRecipe<K>> producers(K key) {
        return producers.getOrDefault(key, List.of());
    }

    public List<GraphRecipe<K>> catalog() {
        return catalog;
    }

    synchronized List<Region<K>> topology(GraphTopology<K> key) {
        return lookup("topology", topologies.get(key));
    }

    synchronized void rememberTopology(GraphTopology<K> key, List<Region<K>> regions) {
        if (key == null) return;
        var previous = topologies.put(key, regions);
        if (previous != null) topologyWeight -= key.recipes.size() + previous.size();
        topologyWeight += key.recipes.size() + regions.size();
        while (topologies.size() > 16 || topologyWeight > 32768) {
            var first = topologies.entrySet().iterator().next();
            topologyWeight -= first.getKey().recipes.size() + first.getValue().size();
            topologies.remove(first.getKey());
            evicted("topology");
        }
    }

    synchronized GraphSourceIndex<K> sourceIndex() {
        return lookup("source_index", sourceIndex);
    }

    synchronized void rememberSourceIndex(GraphSourceIndex<K> index) {
        if (sourceIndex == null && index.recipes == catalog) sourceIndex = index;
    }

    /**
     * Inventory-independent backward closure, in the same source order as a cold
     * traversal. A structural size cutoff only declines this optional model; it
     * is never a missing-material result or an infeasibility certificate.
     */
    CountClosure<K> countClosure(K target, Set<K> additional, Set<String> excluded,
                                 int maxKeys, int maxRecipes, PlanningBudget budget) {
        var scope = new ClosureKey<>(target, List.copyOf(additional), Set.copyOf(excluded));
        boolean cacheable = additional.size() <= 256 && excluded.size() <= 256;
        CountClosure<K> known = null;
        ClosureSize limit = null;
        if (cacheable) synchronized (this) {
            known = lookup("count_closure", countClosures.get(scope));
            limit = lookup("closure_cutoff", countClosureLimits.get(scope));
        }
        if (known != null) {
            budget.compilationCheck();
            if (known.keys().size() > maxKeys || known.recipes().size() > maxRecipes) return null;
            budget.note("count_closure", "reused; recipes=" + known.recipes().size() + "; materials=" + known.keys().size());
            return known;
        }
        if (limit != null && (limit.keys() > maxKeys || limit.recipes() > maxRecipes)) {
            budget.compilationCheck();
            budget.note("count_closure", "known structural cutoff; recipes_at_least=" + limit.recipes() + "; materials_at_least=" + limit.keys());
            return null;
        }
        var found = new LinkedHashMap<String, GraphRecipe<K>>();
        var keys = new LinkedHashSet<K>();
        var queued = new LinkedHashSet<K>();
        var pending = new ArrayDeque<K>();
        queued.add(target);
        pending.add(target);
        for (K key : additional) if (queued.add(key)) pending.addLast(key);
        long incidences = 0;
        while (!pending.isEmpty()) {
            budget.compilationCheck();
            K key = pending.removeFirst();
            keys.add(key);
            if (keys.size() > maxKeys) {
                rememberClosureLimit(scope, cacheable, keys.size(), found.size());
                budget.note("count_model", "skipped; closure_keys=" + keys.size() + "; local_limit=" + maxKeys);
                return null;
            }
            for (var recipe : producers(key)) {
                budget.compilationCheck();
                if (excluded.contains(recipe.id()) || found.putIfAbsent(recipe.id(), recipe) != null) continue;
                if (found.size() > maxRecipes) {
                    rememberClosureLimit(scope, cacheable, keys.size(), found.size());
                    budget.note("count_model", "skipped; closure_recipes=" + found.size() + "; local_limit=" + maxRecipes);
                    return null;
                }
                incidences += recipe.inputs().size() + recipe.outputs().size();
                for (K input : recipe.inputs().keySet()) if (queued.add(input)) pending.addLast(input);
            }
        }
        var result = new CountClosure<K>(List.copyOf(found.values()), List.copyOf(keys), incidences);
        long weight = incidences + keys.size() + found.size();
        if (cacheable && weight <= 32_768) synchronized (this) {
            var previous = countClosures.put(scope, result);
            countClosureWeight += weight - (previous == null ? 0 : previous.weight());
            while (countClosures.size() > 16 || countClosureWeight > 32_768) {
                var removed = countClosures.remove(countClosures.keySet().iterator().next());
                countClosureWeight -= removed.weight();
                evicted("count_closure");
            }
            countClosureLimits.remove(scope);
        }
        return result;
    }

    private synchronized void rememberClosureLimit(ClosureKey<K> scope, boolean cacheable, int keys, int recipes) {
        if (!cacheable) return;
        var previous = countClosureLimits.get(scope);
        countClosureLimits.put(scope, new ClosureSize(Math.max(keys, previous == null ? 0 : previous.keys()),
                Math.max(recipes, previous == null ? 0 : previous.recipes())));
        while (countClosureLimits.size() > 32) {
            countClosureLimits.remove(countClosureLimits.keySet().iterator().next());
            evicted("closure_cutoff");
        }
    }

    record CountClosure<K>(List<GraphRecipe<K>> recipes, List<K> keys, long incidences) {

        long weight() {
            return incidences + recipes.size() + keys.size();
        }
    }

    private record ClosureKey<K>(K target, List<K> additional, Set<String> excluded) {}

    private record ClosureSize(int keys, int recipes) {}

    synchronized GraphCatalogIndex<K> catalogIndex() {
        catalogIndexReusable |= catalogIndexRequested;
        catalogIndexRequested = true;
        return lookup("catalog_index", catalogIndex);
    }

    synchronized boolean reuseCatalogIndex() {
        return catalogIndexReusable;
    }

    synchronized void rememberCatalogIndex(GraphCatalogIndex<K> index) {
        // Only immutable structure is retained. Inventory, active exclusions
        // and reachability conclusions always belong to the current analysis.
        if (catalogIndex == null && index.belongsTo(catalog)) catalogIndex = index;
    }

    synchronized CountCatalog<K> countCatalog(K target, Set<K> seeds, Set<String> excluded, Set<K> external) {
        if (!cacheableCountCatalog(0, seeds.size(), excluded.size(), external.size())) return null;
        countCatalogReusable |= countCatalogRequested;
        countCatalogRequested = true;
        // Preserve seed traversal order as well as membership: recipe ordering
        // affects bounded heuristics even when the feasible set is unchanged.
        return lookup("count_catalog", countCatalogs.get(new CountKey<>(target, List.copyOf(seeds), excluded, external)));
    }

    synchronized boolean reuseCountCatalogs() {
        // A one-shot compiler should pay only for its original sparse model.
        // Retain structural rows after observing reuse of this catalog, not
        // speculatively on its very first count-model request.
        return countCatalogReusable;
    }

    synchronized void rememberCountCatalog(K target, Set<K> seeds, Set<String> excluded, Set<K> external, CountCatalog<K> structure) {
        long weight = structure.weight();
        // This cache belongs to the immutable effective catalog, not the JVM.
        // Bound retained incidences as well as entry count, independently of
        // the request's transient memory reservation.
        if (!cacheableCountCatalog(weight, seeds.size(), excluded.size(), external.size())) return;
        var key = new CountKey<>(target, List.copyOf(seeds), Set.copyOf(excluded), Set.copyOf(external));
        var previous = countCatalogs.put(key, structure);
        countCatalogWeight += weight - (previous == null ? 0 : previous.weight());
        while (countCatalogs.size() > 32 || countCatalogWeight > 32_768) {
            var removed = countCatalogs.remove(countCatalogs.keySet().iterator().next());
            countCatalogWeight -= removed.weight();
            evicted("count_catalog");
        }
    }

    static boolean cacheableCountCatalog(long weight, int seeds, int excluded, int external) {
        return weight <= 32_768 && seeds <= 256 && excluded <= 256 && external <= 256;
    }

    synchronized List<QuantityCertificate<K>> quantityCertificates(Set<String> excluded) {
        var matches = quantityCertificates.stream().filter(certificate -> certificate.excluded().equals(excluded)).toList();
        lookup("quantity_certificate", matches.isEmpty() ? null : matches);
        return matches;
    }

    synchronized void rememberQuantityCertificate(Set<String> excluded, Map<K, BigInteger> weights) {
        // This compiler owns one immutable effective catalog. A replacement
        // pattern/multiplier creates another compiler and cannot inherit proofs.
        if (weights.size() > 128 || excluded.size() > 192) return;
        var entry = new QuantityCertificate<K>(Set.copyOf(excluded), Map.copyOf(weights));
        quantityCertificates.remove(entry);
        quantityCertificates.add(0, entry);
        while (quantityCertificates.size() > 16) {
            quantityCertificates.remove(quantityCertificates.size() - 1);
            evicted("quantity_certificate");
        }
    }

    record QuantityCertificate<K>(Set<String> excluded, Map<K, BigInteger> weights) {}

    public Compiled<K> compile(K target, Map<K, Integer> choices, Set<String> excluded, PlanningBudget budget) {
        Compiled<K> cached = cached(target, choices, excluded);
        if (cached != null) return cached;
        GraphCompilation<K> work = begin(target, choices, excluded, budget);
        try {
            while (!work.step()) { /* Same continuation, without an executor for synchronous callers. */ }
            publish(target, choices, excluded, work.result());
            return work.result();
        } finally {
            work.close();
        }
    }

    public GraphCompilation<K> begin(K target, Map<K, Integer> choices, Set<String> excluded, PlanningBudget budget) {
        return begin(target, Set.of(), choices, excluded, budget);
    }

    public GraphCompilation<K> begin(K target, Set<K> additional, Map<K, Integer> choices, Set<String> excluded, PlanningBudget budget) {
        return new GraphCompilation<>(this, target, additional, choices, excluded, budget);
    }

    /**
     * Positive-witness proposal only. Stock leaves are request-local assumptions:
     * an insufficient leaf must be reopened, and a failure says nothing about
     * the full catalog. These views never enter the inventory-independent cache.
     */
    GraphCompilation<K> beginStockView(K target, Set<K> additional, Map<K, Integer> choices, Set<String> excluded,
                                       Set<K> stockLeaves, PlanningBudget budget) {
        return new GraphCompilation<>(this, target, additional, choices, excluded, stockLeaves, budget);
    }

    public synchronized Compiled<K> cached(K target, Map<K, Integer> choices, Set<String> excluded) {
        return cached(target, Set.of(), choices, excluded);
    }

    public synchronized Compiled<K> cached(K target, Set<K> additional, Map<K, Integer> choices, Set<String> excluded) {
        return lookup("compiled", cache.get(new CacheKey<>(target, List.copyOf(additional), choices, excluded)));
    }

    public synchronized void publish(K target, Map<K, Integer> choices, Set<String> excluded, Compiled<K> result) {
        publish(target, Set.of(), choices, excluded, result);
    }

    public synchronized void publish(K target, Set<K> additional, Map<K, Integer> choices, Set<String> excluded, Compiled<K> result) {
        // Do not serialize entire calculations under the cache monitor. Only fully
        // constructed immutable results become visible to other orders.
        cache.put(new CacheKey<>(target, List.copyOf(additional), Map.copyOf(choices), Set.copyOf(excluded)), result);
        long retainedNodes = 0;
        for (Compiled<K> entry : cache.values()) retainedNodes += entry.recipes().size() + entry.selected().size();
        // A single successfully compiled graph has already passed request limits.
        // Retain it alone instead of discarding the result just published.
        while (cache.size() > 1 && (cache.size() > 128 || retainedNodes > 32_768)) {
            Compiled<K> removed = cache.remove(cache.keySet().iterator().next());
            retainedNodes -= removed.recipes().size() + removed.selected().size();
            evicted("compiled");
        }
    }

    public record Region<K>(List<GraphRecipe<K>> recipes, boolean cyclic) {}

    /** Regions are consumers first, for backward requirement propagation. */
    public record Compiled<K>(Map<String, GraphRecipe<K>> recipes, Map<K, GraphRecipe<K>> selected,
                              List<Region<K>> regions) {

        /** Recipe objects belong to the catalog; this view owns only indexes and region lists. */
        public long estimatedBytes() {
            return 256L + 72L * recipes.size() + 64L * selected.size() + 64L * regions.size();
        }
    }

    private record CacheKey<K>(K target, List<K> additional, Map<K, Integer> choices, Set<String> excluded) {}

    private record CountKey<K>(K target, List<K> seeds, Set<String> excluded, Set<K> external) {}
}
