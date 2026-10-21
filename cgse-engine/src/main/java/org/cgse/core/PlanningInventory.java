// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Planning estimates and suffix funding requirements, without physical inventory access. */
public final class PlanningInventory {

    private PlanningInventory() {}

    /**
     * Immutable planning input. Map access is the bounded one-transfer view;
     * material balances use exactAmount/exactAmounts. Preserve this snapshot
     * when passing it to another planning operation (do not copy its long view).
     */
    @SuppressWarnings("unchecked")
    public static <K> Map<K, Long> exactSnapshot(Map<K, ? extends Number> source) {
        if (source instanceof FrozenAmounts<?> frozen) return (Map<K, Long>) frozen;
        Map<K, Long> values = new LinkedHashMap<>();
        Map<K, BigInteger> wide = new LinkedHashMap<>();
        source.forEach((key, number) -> {
            Objects.requireNonNull(key);
            BigInteger count = ExactAmounts.of(number);
            if (count.signum() == 0) return;
            values.put(key, ExactAmounts.capped(count));
            if (count.compareTo(ExactAmounts.LONG_MAX) > 0) wide.put(key, count);
        });
        return new FrozenAmounts<>(Collections.unmodifiableMap(values), Collections.unmodifiableMap(wide));
    }

    public static BigInteger exactAmount(Map<?, Long> stock, Object key) {
        if (stock instanceof FrozenAmounts<?> frozen) {
            BigInteger wide = frozen.wide.get(key);
            if (wide != null) return wide;
        }
        return BigInteger.valueOf(stock.getOrDefault(key, 0L));
    }

    public static <K> Map<K, BigInteger> exactAmounts(Map<K, Long> stock) {
        Map<K, BigInteger> result = new LinkedHashMap<>();
        stock.forEach((key, value) -> result.put(key, exactAmount(stock, key)));
        return Collections.unmodifiableMap(result);
    }

    public static boolean hasWideAmounts(Map<?, Long> stock) {
        return stock instanceof FrozenAmounts<?> frozen && !frozen.wide.isEmpty();
    }

    static <K> boolean sameAmounts(Map<K, Long> left, Map<K, Long> right) {
        return left == right || left.equals(right) &&
                (!(hasWideAmounts(left) || hasWideAmounts(right)) || exactAmounts(left).equals(exactAmounts(right)));
    }

    static <K> Map<K, Long> withAmount(Map<K, Long> source, K key, BigInteger amount) {
        var values = new LinkedHashMap<>(exactAmounts(source));
        values.put(key, amount);
        return exactSnapshot(values);
    }

    public static <K> Map<K, Long> availability(Map<K, Long> network, Map<K, Long> forecast) {
        return availability(network, forecast, null);
    }

    /** Capture once; continuations can share this owned, read-only value without copying it again. */
    static <K> Map<K, Long> snapshot(Map<K, Long> source) {
        if (source instanceof FrozenAmounts<?>) return source;
        return new FrozenAmounts<>(GraphRecipe.amounts(source), Map.of());
    }

    static <K> Map<K, Long> availability(Map<K, Long> network, Map<K, Long> forecast, K excluded) {
        if (hasWideAmounts(network) || hasWideAmounts(forecast) || forecast.entrySet().stream().anyMatch(
                entry -> entry.getValue() > Long.MAX_VALUE - network.getOrDefault(entry.getKey(), 0L))) {
            Map<K, BigInteger> exact = new LinkedHashMap<>(exactAmounts(network));
            exactAmounts(forecast).forEach((key, count) -> exact.merge(key, count, BigInteger::add));
            if (excluded != null) exact.remove(excluded);
            return exactSnapshot(exact);
        }
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
        return new FrozenAmounts<>(Collections.unmodifiableMap(result), Map.of());
    }

    static final class FrozenAmounts<K> extends AbstractMap<K, Long> {
        private final Map<K, Long> values;
        private final Map<K, BigInteger> wide;

        private FrozenAmounts(Map<K, Long> values, Map<K, BigInteger> wide) {
            this.values = values;
            this.wide = wide;
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
