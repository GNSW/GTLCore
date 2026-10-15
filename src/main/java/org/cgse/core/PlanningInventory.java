// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;

/** Planning estimates and suffix funding requirements, without physical inventory access. */
public final class PlanningInventory {

    private PlanningInventory() {}

    public static <K> Map<K, Long> availability(Map<K, Long> network, Map<K, Long> forecast) {
        return availability(network, forecast, null);
    }

    /** Capture once; continuations can share this owned, read-only value without copying it again. */
    static <K> Map<K, Long> snapshot(Map<K, Long> source) {
        if (source instanceof FrozenAmounts<?>) return source;
        return new FrozenAmounts<>(GraphRecipe.amounts(source));
    }

    static <K> Map<K, Long> availability(Map<K, Long> network, Map<K, Long> forecast, K excluded) {
        Map<K, Long> result = new LinkedHashMap<>();
        network.forEach((key, count) -> {
            Objects.requireNonNull(key);
            CheckedAmounts.nonNegative(count);
            if (count != 0) result.put(key, count);
        });
        forecast.forEach((key, count) -> {
            Objects.requireNonNull(key);
            CheckedAmounts.nonNegative(count);
            if (count == 0) return;
            result.merge(key, count, (stored, owned) -> {
            // Availability is a lower bound for a long-sized request, not an
            // ownership ledger. Physical inputs, expected outputs and settlement
            // continue to use exact arithmetic.
            return stored + Math.min(owned, Long.MAX_VALUE - stored);
            });
        });
        if (excluded != null) result.remove(excluded);
        return new FrozenAmounts<>(Collections.unmodifiableMap(result));
    }

    private static final class FrozenAmounts<K> extends AbstractMap<K, Long> {
        private final Map<K, Long> values;

        private FrozenAmounts(Map<K, Long> values) {
            this.values = values;
        }

        @Override
        public Set<Entry<K, Long>> entrySet() {
            return values.entrySet();
        }

        @Override
        public Long get(Object key) {
            return values.get(key);
        }

        @Override
        public Long getOrDefault(Object key, Long fallback) {
            return values.getOrDefault(key, fallback);
        }

        @Override
        public boolean containsKey(Object key) {
            return values.containsKey(key);
        }

        @Override
        public int size() {
            return values.size();
        }
    }

    public static <K> Requirements<K> requirements(Map<K, Long> initial, Map<K, Long> forecast, Map<K, Long> external) {
        Map<K, Long> needed = new LinkedHashMap<>(), emitted = new LinkedHashMap<>();
        initial.forEach((key, count) -> {
            long extra = Math.max(0, count - forecast.getOrDefault(key, 0L));
            long supplied = Math.min(extra, external.getOrDefault(key, 0L));
            if (supplied > 0) emitted.put(key, supplied);
            if (extra > supplied) needed.put(key, extra - supplied);
        });
        return new Requirements<>(needed, emitted);
    }

    public record Requirements<K>(Map<K, Long> needed, Map<K, Long> emitted) {}
}
