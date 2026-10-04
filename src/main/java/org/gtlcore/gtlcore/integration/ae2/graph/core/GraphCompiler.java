package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable catalog index. Inventory changes do not invalidate the compiled structure. */
public final class GraphCompiler<K> {

    private final List<GraphRecipe<K>> catalog;
    private final Map<K, List<GraphRecipe<K>>> producers;
    private final Map<CacheKey<K>, Compiled<K>> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<CountKey<K>, CountCatalog<K>> countCatalogs = new LinkedHashMap<>(16, 0.75f, true);
    private long countCatalogWeight;
    private boolean countCatalogRequested, countCatalogReusable;
    private final List<QuantityCertificate<K>> quantityCertificates = new ArrayList<>();
    final CountSessions countSessions = new CountSessions();

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

    synchronized CountCatalog<K> countCatalog(K target, Set<K> seeds, Set<String> excluded, Set<K> external) {
        if (!cacheableCountCatalog(0, seeds.size(), excluded.size(), external.size())) return null;
        countCatalogReusable |= countCatalogRequested;
        countCatalogRequested = true;
        if (countCatalogs.isEmpty()) return null;
        // Preserve seed traversal order as well as membership: recipe ordering
        // affects bounded heuristics even when the feasible set is unchanged.
        return countCatalogs.get(new CountKey<>(target, List.copyOf(seeds), excluded, external));
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
        }
    }

    static boolean cacheableCountCatalog(long weight, int seeds, int excluded, int external) {
        return weight <= 32_768 && seeds <= 256 && excluded <= 256 && external <= 256;
    }

    synchronized List<QuantityCertificate<K>> quantityCertificates(Set<String> excluded) {
        return quantityCertificates.stream().filter(certificate -> certificate.excluded().equals(excluded)).toList();
    }

    synchronized void rememberQuantityCertificate(Set<String> excluded, Map<K, BigInteger> weights) {
        // This compiler owns one immutable effective catalog. A replacement
        // pattern/multiplier creates another compiler and cannot inherit proofs.
        if (weights.size() > 128 || excluded.size() > 192) return;
        var entry = new QuantityCertificate<K>(Set.copyOf(excluded), Map.copyOf(weights));
        quantityCertificates.remove(entry);
        quantityCertificates.add(0, entry);
        while (quantityCertificates.size() > 16) quantityCertificates.remove(quantityCertificates.size() - 1);
    }

    record QuantityCertificate<K>(Set<String> excluded, Map<K, BigInteger> weights) {}

    public Compiled<K> compile(K target, Map<K, Integer> choices, Set<String> excluded, PlanningBudget budget) {
        Compiled<K> cached = cached(target, choices, excluded);
        if (cached != null) return cached;
        GraphCompilation<K> work = begin(target, choices, excluded, budget);
        while (!work.step()) { /* Same continuation, without an executor for synchronous callers. */ }
        publish(target, choices, excluded, work.result());
        return work.result();
    }

    public GraphCompilation<K> begin(K target, Map<K, Integer> choices, Set<String> excluded, PlanningBudget budget) {
        return begin(target, Set.of(), choices, excluded, budget);
    }

    public GraphCompilation<K> begin(K target, Set<K> additional, Map<K, Integer> choices, Set<String> excluded, PlanningBudget budget) {
        return new GraphCompilation<>(this, target, additional, choices, excluded, budget);
    }

    public synchronized Compiled<K> cached(K target, Map<K, Integer> choices, Set<String> excluded) {
        return cached(target, Set.of(), choices, excluded);
    }

    public synchronized Compiled<K> cached(K target, Set<K> additional, Map<K, Integer> choices, Set<String> excluded) {
        return cache.get(new CacheKey<>(target, additional, choices, excluded));
    }

    public synchronized void publish(K target, Map<K, Integer> choices, Set<String> excluded, Compiled<K> result) {
        publish(target, Set.of(), choices, excluded, result);
    }

    public synchronized void publish(K target, Set<K> additional, Map<K, Integer> choices, Set<String> excluded, Compiled<K> result) {
        // Do not serialize entire calculations under the cache monitor. Only fully
        // constructed immutable results become visible to other orders.
        cache.put(new CacheKey<>(target, Set.copyOf(additional), Map.copyOf(choices), Set.copyOf(excluded)), result);
        long retainedNodes = 0;
        for (Compiled<K> entry : cache.values()) retainedNodes += entry.recipes().size() + entry.selected().size();
        // A single successfully compiled graph has already passed request limits.
        // Retain it alone instead of discarding the result just published.
        while (cache.size() > 1 && (cache.size() > 128 || retainedNodes > 32_768)) {
            Compiled<K> removed = cache.remove(cache.keySet().iterator().next());
            retainedNodes -= removed.recipes().size() + removed.selected().size();
        }
    }

    public record Region<K>(List<GraphRecipe<K>> recipes, boolean cyclic) {}

    /** Regions are consumers first, for backward requirement propagation. */
    public record Compiled<K>(Map<String, GraphRecipe<K>> recipes, Map<K, GraphRecipe<K>> selected,
                              List<Region<K>> regions) {}

    private record CacheKey<K>(K target, Set<K> additional, Map<K, Integer> choices, Set<String> excluded) {}

    private record CountKey<K>(K target, List<K> seeds, Set<String> excluded, Set<K> external) {}
}
