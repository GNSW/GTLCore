// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Only physically held material lives here. Expected returns are a separate account. */
public final class ResourceLedger<K> {

    private final Map<K, Long> owned = new LinkedHashMap<>();

    public ResourceLedger(Map<K, Long> initial) {
        owned.putAll(GraphRecipe.amounts(initial));
    }

    public long get(K key) {
        return owned.getOrDefault(key, 0L);
    }

    public Map<K, Long> snapshot() {
        return GraphRecipe.amounts(owned);
    }

    public boolean isEmpty() {
        return owned.isEmpty();
    }

    public void add(K key, long amount) {
        long next = CheckedAmounts.add(get(key), amount);
        if (next != 0) owned.put(key, next);
    }

    public void remove(K key, long amount) {
        CheckedAmounts.nonNegative(amount);
        long current = get(key);
        if (current < amount) throw new IllegalStateException("Material not held: " + key);
        if (current == amount) owned.remove(key);
        else owned.put(key, current - amount);
    }

    public Map<K, Long> take(Map<K, Long> inputs, long batch) {
        Map<K, Long> escrow = new LinkedHashMap<>();
        inputs.forEach((key, amount) -> {
            long required = CheckedAmounts.multiply(amount, batch);
            if (get(key) < required) throw new IllegalStateException("Unfunded dispatch");
            escrow.put(key, required);
        });
        escrow.forEach(this::remove);
        return GraphRecipe.amounts(escrow);
    }

    /**
     * Restore one escrow atomically with respect to invalid keys/amounts and
     * arithmetic overflow. Validate and compute every balance before publishing
     * any of them. Keys must retain stable equals/hashCode behavior; this is not
     * a rollback guarantee for allocation failures or arbitrary key callbacks.
     */
    public void restore(Map<K, Long> escrow) {
        Map<K, Long> balances = new LinkedHashMap<>();
        escrow.forEach((key, amount) -> {
            Objects.requireNonNull(key, "Null material key");
            long next = CheckedAmounts.add(get(key), Objects.requireNonNull(amount, "Null material amount"));
            if (next != 0) balances.put(key, next);
        });
        owned.putAll(balances);
    }
}
