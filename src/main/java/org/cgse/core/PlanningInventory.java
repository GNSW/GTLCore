// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Planning estimates and suffix funding requirements, without physical inventory access. */
public final class PlanningInventory {

    private PlanningInventory() {}

    public static <K> Map<K, Long> availability(Map<K, Long> network, Map<K, Long> forecast) {
        Map<K, Long> result = new LinkedHashMap<>(network);
        forecast.forEach((key, count) -> result.merge(key, CheckedAmounts.nonNegative(count), (stored, owned) -> {
            // Availability is a lower bound for a long-sized request, not an
            // ownership ledger. Physical inputs, expected outputs and settlement
            // continue to use exact arithmetic.
            CheckedAmounts.nonNegative(stored);
            return stored + Math.min(owned, Long.MAX_VALUE - stored);
        }));
        return result;
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
