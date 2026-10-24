// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Necessary startup conditions, not a replacement for exact recovery search.
 *
 * An enabled recipe supplies all its outputs without limit and without spending
 * its inputs in this relaxation. Thus a closed deficit proves that the actual
 * final inventory cannot restart that input, even after replenishing terminal
 * materials. An open deficit does NOT prove a recovery word exists.
 */
final class SeedStartupGuard<K> implements AutoCloseable {
    private record Wait(int recipe, long amount) {}
    private final PlanningBudget budget;
    private final Collection<GraphRecipe<K>> available;
    private final Set<String> excluded;
    private final SequenceSummary<K> summary;
    private final Map<K, Long> stock, retained;
    private final Set<K> external;
    private final K target;
    private final long amount;
    private Map<K, List<GraphRecipe<K>>> producers = new LinkedHashMap<>();
    private Map<K, List<GraphRecipe<K>>> returned = new LinkedHashMap<>();
    private final GraphRecipeCatalog<K> catalog;
    private boolean borrowedIndex;
    private long indexMemory;
    private final Map<K, Long> lost = new LinkedHashMap<>();
    private final Set<K> visited = new HashSet<>(), unlimited = new HashSet<>();
    private final ArrayDeque<K> pending = new ArrayDeque<>(), supplied = new ArrayDeque<>();
    private final Map<K, List<Wait>> waiting = new HashMap<>();
    private final List<GraphRecipe<K>> actions = new ArrayList<>();
    private final Set<GraphRecipe<K>> scanned = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<K> scanKeys = new HashSet<>();
    private final Set<K> unresolvedRoots = new HashSet<>();
    private final ArrayDeque<K> scanPending = new ArrayDeque<>();
    private final List<Integer> blocked = new ArrayList<>();
    private Iterator<K> keys;
    private Iterator<GraphRecipe<K>> recipes;
    private GraphRecipe<K> indexing, scanning, enabling;
    private Iterator<K> indexedOutputs, enabledOutputs;
    private Iterator<Map.Entry<K, Long>> scannedInputs, walkedInputs;
    private Iterator<GraphRecipe<K>> walkedRecipes;
    private final ArrayDeque<GraphRecipe<K>> enabled = new ArrayDeque<>();
    private Iterator<Wait> activeConsumers;
    private K suppliedKey;
    private int scanIndex, scanBlocked;
    private SeedConversionCertificate<K> conversion;
    private K current, scanRoot;
    private long minimum, memory;
    private int phase, visitedCapacity;
    private boolean targetChecked;

    SeedStartupGuard(Collection<GraphRecipe<K>> available, SequenceSummary<K> summary,
                     Map<K, Long> stock, Map<K, Long> retained, Set<K> external,
                     K target, long amount, PlanningBudget budget) {
        this(available, Set.of(), summary, stock, retained, external, target, amount, budget);
    }

    SeedStartupGuard(Collection<GraphRecipe<K>> available, Set<String> excluded, SequenceSummary<K> summary,
                     Map<K, Long> stock, Map<K, Long> retained, Set<K> external,
                     K target, long amount, PlanningBudget budget) {
        this.available = available; this.summary = summary; this.stock = stock;
        this.excluded = excluded;
        this.retained = retained; this.external = external; this.target = target;
        this.amount = amount; this.budget = budget;
        catalog = excluded.isEmpty() && available instanceof GraphRecipeCatalog<K> indexed ? indexed : null;
        recipes = available.iterator();
    }

    boolean step() {
        budget.check();
        // A wide recipe or high-fanout material must not monopolize the
        // caller's continuation. Retain cursors for every adjacency scan.
        for (int work = 0; work < 24; work++) if (advance()) return true;
        return false;
    }

    private boolean advance() {
        budget.check();
        if (phase == 1 && unresolvedRoots.isEmpty()) { phase = 4; return true; }
        if (enabledOutputs != null || !enabled.isEmpty()) {
            if (enabledOutputs == null) {
                enabling = enabled.removeFirst(); enabledOutputs = enabling.outputs().keySet().iterator();
            }
            if (enabledOutputs.hasNext()) {
                K key = enabledOutputs.next();
                if (enabling.outputs().get(key) > enabling.inputs().getOrDefault(key, 0L) && unlimited.add(key)) {
                    reserve(96);
                    unresolvedRoots.remove(key);
                    // Later inputs observe unlimited directly. Only consumers
                    // already registered need a propagation event.
                    if (waiting.containsKey(key)) supplied.add(key);
                }
                if (!enabledOutputs.hasNext()) { enabledOutputs = null; enabling = null; }
            } else { enabledOutputs = null; enabling = null; }
            return false;
        }
        if ((phase == 1 || phase == 2) && (activeConsumers != null || !supplied.isEmpty())) {
            if (activeConsumers == null) {
                suppliedKey = supplied.removeFirst();
                activeConsumers = waiting.getOrDefault(suppliedKey, List.of()).iterator();
            }
            if (activeConsumers.hasNext()) {
                var consumer = activeConsumers.next();
                if (unlimited.contains(suppliedKey) || finalStock(suppliedKey).compareTo(BigInteger.valueOf(consumer.amount())) >= 0) {
                    activeConsumers.remove(); int index = consumer.recipe();
                    int count = blocked.get(index) - 1; blocked.set(index, count);
                    if (count == 0) enable(actions.get(index));
                }
            }
            if (!activeConsumers.hasNext()) { activeConsumers = null; suppliedKey = null; }
            return false;
        }
        if (phase == 0) {
            if (catalog != null && catalog.startup() != null) {
                borrowIndex(catalog.startup());
                finishIndex();
                return false;
            }
            if (indexedOutputs != null) {
                if (!indexedOutputs.hasNext()) { indexedOutputs = null; indexing = null; return false; }
                K key = indexedOutputs.next();
                if (indexing.outputs().get(key) > indexing.inputs().getOrDefault(key, 0L)) {
                    reserve(96); indexMemory += 96;
                    producers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(indexing);
                } else {
                    reserve(96); indexMemory += 96;
                    returned.computeIfAbsent(key, ignored -> new ArrayList<>()).add(indexing);
                }
                if (!indexedOutputs.hasNext()) { indexedOutputs = null; indexing = null; }
            } else if (recipes.hasNext()) {
                indexing = recipes.next();
                if (excluded.contains(indexing.id())) { indexing = null; return false; }
                // An unused producer still makes its output a produced
                // material. Never mistake that output for raw supply.
                indexedOutputs = indexing.outputs().keySet().iterator();
            } else {
                if (catalog != null) {
                    var known = catalog.rememberStartup(producers, returned, indexMemory);
                    if (known != null) borrowIndex(known);
                }
                finishIndex();
            }
        } else if (phase == 5) {
            // Only consumed, produced inputs can lose a restart token. If the
            // candidate spends only terminal material, the closure cannot add
            // a seed obligation. Inspect each key once before building it.
            if (keys.hasNext() || !targetChecked && !summary.required().containsKey(target)) {
                K key;
                if (keys.hasNext()) key = keys.next();
                else { key = target; targetChecked = true; }
                if (!external.contains(key) && spendsAfterDelivery(summary, key, target, amount) && producers.containsKey(key)) {
                    if (unresolvedRoots.add(key)) reserve(48);
                    enqueueScan(key);
                }
            } else {
                keys = null;
                if (scanPending.isEmpty()) { phase = 4; return true; }
                phase = 1;
            }
        } else if (phase == 1) {
            if (scannedInputs == null && scanRoot != null && unlimited.contains(scanRoot)) recipes = null;
            if (scannedInputs != null) {
                if (scannedInputs.hasNext()) {
                    var input = scannedInputs.next(); K key = input.getKey();
                    if (!external.contains(key) && !unlimited.contains(key) && producers.containsKey(key) && finalStock(key).compareTo(BigInteger.valueOf(input.getValue())) < 0) {
                        reserve(96); waiting.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Wait(scanIndex, input.getValue())); scanBlocked++;
                        enqueueScan(key);
                    }
                }
                if (!scannedInputs.hasNext()) finishScanning();
            } else if (recipes != null && recipes.hasNext()) {
                scanning = recipes.next();
                if (!scanned.add(scanning)) { scanning = null; return false; }
                reserve(96); scanIndex = actions.size(); actions.add(scanning); scanBlocked = 0;
                scannedInputs = scanning.inputs().entrySet().iterator();
                if (!scannedInputs.hasNext()) finishScanning();
            } else if (!scanPending.isEmpty()) {
                K key = scanPending.removeFirst();
                scanRoot = key;
                recipes = unlimited.contains(key) ? null : producers.getOrDefault(key, List.of()).iterator();
            } else {
                // Only the backward dependencies of spent inputs can affect
                // their restart closure. The producer index still covers the
                // entire allowed catalog, including unused alternatives.
                recipes = null;
                reserve(48L * (summary.required().size() + 1L));
                var ordered = new ArrayList<>(summary.required().keySet());
                if (!summary.required().containsKey(target)) ordered.add(target);
                ordered.sort(Comparator.comparing(summary::required));
                keys = ordered.iterator(); phase = 2;
            }
        } else if (phase == 2) {
            if (keys.hasNext()) {
                K key = keys.next();
                if (!external.contains(key) && spendsAfterDelivery(summary, key, target, amount) &&
                        !unlimited.contains(key) && producers.containsKey(key)) {
                    // Only still-closed inputs need a cycle walk. Ordinary
                    // stocked or terminal-fed materials stop at the closure.
                    current = key; minimum = 0; visited.clear(); enqueue(key); phase = 3;
                }
            } else { phase = 4; return true; }
        } else if (phase == 3) {
            if (walkedInputs != null) {
                if (walkedInputs.hasNext()) {
                    var input = walkedInputs.next();
                    if (input.getKey().equals(current)) {
                        minimum = minimum == 0 ? input.getValue() : Math.min(minimum, input.getValue());
                        // This is a sufficient finite startup. Further walk
                        // entries can only reduce the required threshold.
                        if (finalStock(current).compareTo(BigInteger.valueOf(minimum)) >= 0) {
                            pending.clear(); walkedInputs = null; walkedRecipes = null; current = null; phase = 2;
                            return false;
                        }
                    }
                    enqueue(input.getKey());
                } else walkedInputs = null;
            } else if (walkedRecipes != null) {
                if (walkedRecipes.hasNext()) walkedInputs = walkedRecipes.next().inputs().entrySet().iterator();
                else walkedRecipes = null;
            } else if (pending.isEmpty()) {
                if (minimum > 0 && finalStock(current).compareTo(BigInteger.valueOf(minimum)) < 0) {
                    if (conversion == null) conversion = new SeedConversionCertificate<>(producers, returned, visited, current, budget);
                    if (!conversion.step()) return false;
                    boolean renewable = !conversion.certified(); conversion.close(); conversion = null;
                    if (renewable) {
                        reserve(96); lost.put(current, minimum);
                        // Propagate one finite reserve before asking for another
                        // representation of the same startup.
                        supplied.add(current);
                    }
                }
                current = null; phase = 2;
            } else {
                K key = pending.removeFirst();
                walkedRecipes = producers.getOrDefault(key, List.of()).iterator();
            }
        } else return true;
        return false;
    }

    private BigInteger finalStock(K key) {
        BigInteger goal = BigInteger.valueOf(retained.getOrDefault(key, 0L));
        if (key.equals(target)) goal = goal.add(BigInteger.valueOf(amount));
        // A preview's stated material funding is real for this conditional
        // check. An executable candidate still has to pass stock validation.
        BigInteger initial = PlanningInventory.exactAmount(stock, key).max(summary.required(key)).max(goal.subtract(summary.delta(key)));
        BigInteger result = initial.add(summary.delta(key));
        if (key.equals(target)) result = result.subtract(BigInteger.valueOf(amount));
        return result.max(BigInteger.valueOf(lost.getOrDefault(key, 0L)));
    }

    private void finishIndex() {
        recipes = null; indexing = null; indexedOutputs = null;
        keys = summary.required().keySet().iterator(); phase = 5;
    }

    private void borrowIndex(GraphRecipeCatalog.Startup<K> index) {
        // A completed catalog index contains no stock, exclusions, pending
        // checks or decisions. A cancelled partial index is never published.
        producers = index.growing(); returned = index.returned(); borrowedIndex = true;
        budget.release(indexMemory); memory -= indexMemory; indexMemory = 0;
    }

    static <K> boolean spendsAfterDelivery(SequenceSummary<K> summary, K key, K target, long amount) {
        return summary.delta(key).compareTo(key.equals(target) ? BigInteger.valueOf(amount) : BigInteger.ZERO) < 0;
    }

    private void finishScanning() {
        blocked.add(scanBlocked);
        if (scanBlocked == 0) enable(scanning);
        scannedInputs = null; scanning = null;
    }

    private void enable(GraphRecipe<K> recipe) {
        enabled.add(recipe);
    }

    private void enqueueScan(K key) {
        if (scanKeys.add(key)) { reserve(96); scanPending.addLast(key); }
    }

    private void enqueue(K key) {
        if (!visited.add(key)) return;
        if (visited.size() > visitedCapacity) { reserve(96); visitedCapacity = visited.size(); }
        pending.add(key);
    }

    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    Map<K, Long> lost() { return lost; }
    @Override public void close() {
        if (conversion != null) { conversion.close(); conversion = null; }
        budget.release(memory); memory = 0;
        if (!borrowedIndex && producers != null) { producers.clear(); returned.clear(); }
        producers = null; returned = null; indexMemory = 0;
        pending.clear(); supplied.clear(); enabled.clear(); waiting.clear(); actions.clear(); blocked.clear(); visited.clear(); unlimited.clear();
        scanned.clear(); scanKeys.clear(); scanPending.clear(); unresolvedRoots.clear();
        indexedOutputs = null; enabledOutputs = null; scannedInputs = null; walkedInputs = null; walkedRecipes = null;
        activeConsumers = null; indexing = null; scanning = null; enabling = null; suppliedKey = null;
    }
}
