// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Optional, resumable certificate for nonrenewable one-to-one conversions.
 * Pure neutral conversions may be consumed. If an action has a positive
 * coproduct, every complete loop must strictly lose weighted material.
 * Declining this certificate never proves that an execution is impossible.
 */
final class SeedConversionCertificate<K> implements AutoCloseable {
    private record Edge(int from, int to, ExactRational ratio) {}
    private enum Phase { COUNT, ALLOCATE, FORWARD, COMPONENT, COLLECT, RECIPES, OUTPUTS, INPUTS, EXTRA,
        WEIGHTS, RELAX, EQUAL_SETUP, EQUAL, READY, REMOVE, DONE }
    private final Map<K, List<GraphRecipe<K>>> producers;
    private final Map<K, List<GraphRecipe<K>>> returned;
    private final Set<GraphRecipe<K>> collected = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<GraphRecipe<K>> relevant = new ArrayList<>();
    private final Set<K> visited;
    private final PlanningBudget budget;
    private final K current;
    private final Map<K, List<K>> forward = new HashMap<>();
    private final Map<K, Integer> ids = new LinkedHashMap<>();
    private final ArrayDeque<K> queue = new ArrayDeque<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<List<Integer>> equal = new ArrayList<>();
    private final ArrayDeque<Integer> ready = new ArrayDeque<>();
    private Iterator<K> keys, ports, neighbors;
    private Iterator<GraphRecipe<K>> recipes;
    private Iterator<Map.Entry<K, Long>> outputs;
    private Iterator<Integer> children;
    private GraphRecipe<K> recipe;
    private K producing, source, destination;
    private ExactRational[] weights;
    private int[] incoming;
    private int terms, index, round, removed;
    private boolean changed, extraProduct, certified, collectingReturns;
    private boolean uniform = true, simple = true, recipeSimple, recipeExtra;
    private BigInteger internalInput, internalOutput;
    private long memory;
    private Phase phase = Phase.COUNT;

    SeedConversionCertificate(Map<K, List<GraphRecipe<K>>> producers, Map<K, List<GraphRecipe<K>>> returned,
                              Set<K> visited, K current,
                              PlanningBudget budget) {
        this.producers = producers; this.returned = returned;
        this.visited = visited; this.current = current; this.budget = budget;
        if (visited.size() > 128 || !reserve(512)) phase = Phase.DONE;
        else keys = visited.iterator();
    }

    boolean step() {
        budget.check();
        try { return advance(); }
        catch (ExactRational.PrecisionLimit unsupported) { phase = Phase.DONE; return true; }
    }

    private boolean advance() {
        switch (phase) {
            case COUNT -> {
                if (recipes != null && recipes.hasNext()) {
                    terms += recipes.next().inputs().size();
                    if (terms > 1024) phase = Phase.DONE;
                } else if (keys.hasNext()) recipes = producers.getOrDefault(keys.next(), List.of()).iterator();
                else phase = Phase.ALLOCATE;
            }
            case ALLOCATE -> {
                if (!reserve(4096L + 4096L * visited.size() + 256L * terms)) phase = Phase.DONE;
                else { keys = visited.iterator(); recipes = null; phase = Phase.FORWARD; }
            }
            case FORWARD -> {
                if (ports != null && ports.hasNext()) forward.computeIfAbsent(ports.next(), ignored -> new ArrayList<>()).add(producing);
                else if (recipes != null && recipes.hasNext()) ports = recipes.next().inputs().keySet().iterator();
                else if (keys.hasNext()) { producing = keys.next(); recipes = producers.getOrDefault(producing, List.of()).iterator(); }
                else { ids.put(current, 0); queue.add(current); neighbors = null; phase = Phase.COMPONENT; }
            }
            case COMPONENT -> {
                if (neighbors != null && neighbors.hasNext()) {
                    K next = neighbors.next();
                    if (!ids.containsKey(next)) { ids.put(next, ids.size()); queue.add(next); }
                    if (ids.size() > 64) phase = Phase.DONE;
                } else if (!queue.isEmpty()) neighbors = forward.getOrDefault(queue.removeFirst(), List.of()).iterator();
                else { keys = ids.keySet().iterator(); recipes = null; phase = Phase.COLLECT; }
            }
            case COLLECT -> {
                // Net-zero returns can still yield a coproduct. Include both
                // output indexes, so an unused productive action cannot be
                // hidden by selecting only the word's recipes.
                if (recipes != null && recipes.hasNext()) {
                    var next = recipes.next();
                    if (collected.add(next)) {
                        if (relevant.size() >= 1024 || !reserve(96)) phase = Phase.DONE;
                        else relevant.add(next);
                    }
                } else if (collectingReturns) {
                    collectingReturns = false; recipes = returned.getOrDefault(producing, List.of()).iterator();
                } else if (keys.hasNext()) {
                    producing = keys.next(); recipes = producers.getOrDefault(producing, List.of()).iterator();
                    collectingReturns = true;
                } else { recipes = relevant.iterator(); phase = Phase.RECIPES; }
            }
            case RECIPES -> {
                if (!recipes.hasNext()) {
                    if (uniform) { certified = true; phase = Phase.DONE; }
                    else if (!simple) phase = Phase.DONE;
                    else { weights = new ExactRational[ids.size()]; index = 0; phase = Phase.WEIGHTS; }
                }
                else {
                    recipe = recipes.next();
                    source = null; destination = null; recipeSimple = true; recipeExtra = false;
                    internalInput = internalOutput = BigInteger.ZERO;
                    ports = recipe.outputs().keySet().iterator(); phase = Phase.OUTPUTS;
                }
            }
            case OUTPUTS -> {
                if (ports.hasNext()) {
                    K key = ports.next();
                    if (ids.containsKey(key)) {
                        internalOutput = internalOutput.add(BigInteger.valueOf(recipe.outputs().get(key)));
                        if (destination != null) recipeSimple = false;
                        else destination = key;
                    }
                } else if (destination == null) phase = Phase.RECIPES;
                else if (edges.size() >= 1024 || !reserve(256)) phase = Phase.DONE;
                else { ports = recipe.inputs().keySet().iterator(); phase = Phase.INPUTS; }
            }
            case INPUTS -> {
                if (ports.hasNext()) {
                    K key = ports.next();
                    if (ids.containsKey(key)) {
                        internalInput = internalInput.add(BigInteger.valueOf(recipe.inputs().get(key)));
                        if (recipe.configurationInputs().containsKey(key)) { uniform = false; recipeSimple = false; }
                        if (source != null) recipeSimple = false;
                        else source = key;
                    }
                } else if (source == null) phase = Phase.DONE;
                else { outputs = recipe.outputs().entrySet().iterator(); phase = Phase.EXTRA; }
            }
            case EXTRA -> {
                if (outputs.hasNext()) {
                    var output = outputs.next();
                    if (!ids.containsKey(output.getKey()) && output.getValue() > recipe.inputs().getOrDefault(output.getKey(), 0L)) recipeExtra = extraProduct = true;
                } else {
                    // A finite nonincreasing internal token balance is also
                    // valid for multi-input state transitions. Any positive
                    // coproduct must strictly spend that balance.
                    if (internalInput.compareTo(internalOutput.add(recipeExtra ? BigInteger.ONE : BigInteger.ZERO)) < 0) uniform = false;
                    simple &= recipeSimple;
                    if (recipeSimple) edges.add(new Edge(ids.get(source), ids.get(destination), new ExactRational(
                            BigInteger.valueOf(recipe.inputs().get(source)), BigInteger.valueOf(recipe.outputs().get(destination)))));
                    phase = !uniform && !simple ? Phase.DONE : Phase.RECIPES;
                }
            }
            case WEIGHTS -> {
                if (index < weights.length) weights[index++] = ExactRational.ONE;
                else { index = 0; round = 0; changed = false; phase = Phase.RELAX; }
            }
            case RELAX -> {
                if (index < edges.size()) {
                    var edge = edges.get(index++); var upper = weights[edge.from()].multiply(edge.ratio());
                    if (weights[edge.to()].compareTo(upper) > 0) { weights[edge.to()] = upper; changed = true; }
                } else if (!changed) {
                    if (!extraProduct) { certified = true; phase = Phase.DONE; }
                    else { incoming = new int[weights.length]; phase = Phase.EQUAL_SETUP; }
                } else if (++round >= weights.length) phase = Phase.DONE;
                else { index = 0; changed = false; }
            }
            case EQUAL_SETUP -> {
                if (equal.size() < weights.length) equal.add(new ArrayList<>());
                else { index = 0; phase = Phase.EQUAL; }
            }
            case EQUAL -> {
                if (index < edges.size()) {
                    var edge = edges.get(index++);
                    if (weights[edge.to()].compareTo(weights[edge.from()].multiply(edge.ratio())) == 0) {
                        equal.get(edge.from()).add(edge.to()); incoming[edge.to()]++;
                    }
                } else { index = 0; phase = Phase.READY; }
            }
            case READY -> {
                if (index < incoming.length) { if (incoming[index] == 0) ready.add(index); index++; }
                else phase = Phase.REMOVE;
            }
            case REMOVE -> {
                if (children != null && children.hasNext()) { int next = children.next(); if (--incoming[next] == 0) ready.add(next); }
                else if (!ready.isEmpty()) { int key = ready.removeFirst(); removed++; children = equal.get(key).iterator(); }
                else { certified = removed == weights.length; phase = Phase.DONE; }
            }
            case DONE -> { return true; }
        }
        return phase == Phase.DONE;
    }

    boolean certified() { return certified; }
    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) return false;
        memory += bytes; return true;
    }
    @Override public void close() {
        budget.release(memory); memory = 0;
        forward.clear(); ids.clear(); queue.clear(); edges.clear(); equal.clear(); ready.clear();
        collected.clear(); relevant.clear();
        keys = null; ports = null; neighbors = null; recipes = null; outputs = null; children = null;
        recipe = null; weights = null; incoming = null;
    }
}
