package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.util.LinkedHashMap;
import java.util.Map;

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

    public void restore(Map<K, Long> escrow) {
        escrow.forEach(this::add);
    }
}
