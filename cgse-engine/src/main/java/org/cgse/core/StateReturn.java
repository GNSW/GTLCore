// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Captured return semantics for one exact input state. The host supplies a
 * stable rule identity and sound outcomes; a sampled callback result is not a
 * complete contract. An empty outcome means consumption without a remainder.
 */
public final class StateReturn<K> {

    private final String semantics;
    private final List<Map<K, Long>> outcomes;
    private final boolean complete;

    private StateReturn(String semantics, List<Map<K, Long>> outcomes, boolean complete) {
        this.semantics = Objects.requireNonNull(semantics);
        if (semantics.isBlank()) throw new IllegalArgumentException("Missing return semantics identity");
        var unique = new LinkedHashSet<Map<K, Long>>();
        for (var outcome : outcomes) unique.add(GraphRecipe.amounts(outcome));
        if (complete && unique.isEmpty()) throw new IllegalArgumentException("Complete return contract without an outcome");
        this.outcomes = List.copyOf(unique);
        this.complete = complete;
    }

    public static <K> StateReturn<K> exact(String semantics, Map<K, Long> returned) {
        return new StateReturn<>(semantics, List.of(returned), true);
    }

    public static <K> StateReturn<K> consumed(String semantics) {
        return exact(semantics, Map.of());
    }

    /** Known possible outcomes, never an expected-value or worst-case approximation. */
    public static <K> StateReturn<K> alternatives(String semantics, List<Map<K, Long>> outcomes, boolean complete) {
        return new StateReturn<>(semantics, outcomes, complete);
    }

    public static <K> StateReturn<K> unknown(String semantics) {
        return new StateReturn<>(semantics, List.of(), false);
    }

    public String semantics() { return semantics; }

    public List<Map<K, Long>> outcomes() { return outcomes; }

    public boolean complete() { return complete; }

    public boolean deterministic() { return complete && outcomes.size() == 1; }

    /**
     * Existing count plans and physical output obligations require exact
     * returns. Incomplete or stochastic semantics must not become a fixed
     * graph, a missing-material proof, or a fabricated physical return.
     */
    public Map<K, Long> requireExact() {
        if (!deterministic()) throw new PlanningFailure(GraphPlan.Result.UNSUPPORTED_PATTERN_SEMANTICS,
                "state_return_requires_outcome_execution; semantics=" + semantics + "; complete=" + complete +
                        "; outcomes=" + outcomes.size());
        return outcomes.get(0);
    }

    /** Compatibility with hosts whose input slot returns at most one physical item. */
    public K requireSingleRemainder() {
        Map<K, Long> returned = requireExact();
        if (returned.isEmpty()) return null;
        if (returned.size() == 1) {
            var entry = returned.entrySet().iterator().next();
            if (entry.getValue() == 1L) return entry.getKey();
        }
        throw new PlanningFailure(GraphPlan.Result.UNSUPPORTED_PATTERN_SEMANTICS,
                "state_return_requires_multiple_remainders; semantics=" + semantics);
    }

    /** Tests a whole physical result; partial deliveries are not whole outcomes. */
    public boolean permits(Map<K, Long> returned) {
        return outcomes.contains(GraphRecipe.amounts(returned));
    }

    /** Unique discovered resource states, preserving declared outcome order. */
    public List<K> successors() {
        var keys = new LinkedHashSet<K>();
        for (var outcome : outcomes) keys.addAll(outcome.keySet());
        return new ArrayList<>(keys);
    }

    long estimatedBytes() {
        long bytes = 80L + 2L * semantics.length();
        for (var outcome : outcomes) bytes += 48L + 64L * outcome.size();
        return bytes;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StateReturn<?> that && semantics.equals(that.semantics) &&
                outcomes.equals(that.outcomes) && complete == that.complete;
    }

    @Override
    public int hashCode() { return Objects.hash(semantics, outcomes, complete); }
}
