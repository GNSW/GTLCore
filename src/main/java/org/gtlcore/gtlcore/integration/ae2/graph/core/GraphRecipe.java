package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One resolved input choice. Binding refers to the registered pattern, not a synthetic pattern. */
public final class GraphRecipe<K> {

    private final String id;
    private final String binding;
    private final List<Slot<K>> slots;
    private final Map<K, Long> outputs;
    private final Map<K, Long> inputs;
    private final Map<K, Long> configurationInputs;
    private final Map<K, Long> reusableInputs;
    private final Map<K, Long> executionOutputs;

    public GraphRecipe(String id, String binding, List<Slot<K>> slots, Map<K, Long> outputs) {
        this.id = Objects.requireNonNull(id);
        this.binding = Objects.requireNonNull(binding);
        this.slots = List.copyOf(slots);
        this.outputs = amounts(outputs);
        if (this.outputs.isEmpty()) throw new IllegalArgumentException("Pattern without outputs");
        Map<K, Long> aggregated = new LinkedHashMap<>();
        Map<K, Long> configuration = new LinkedHashMap<>();
        Map<K, Long> reusable = new LinkedHashMap<>();
        for (Slot<K> slot : this.slots) aggregated.merge(slot.key(), slot.amount(), CheckedAmounts::add);
        for (Slot<K> slot : this.slots) if (slot.configuration()) configuration.merge(slot.key(), slot.amount(), CheckedAmounts::add);
        for (Slot<K> slot : this.slots) if (slot.reusable()) reusable.merge(slot.key(), slot.amount(), CheckedAmounts::add);
        this.inputs = Collections.unmodifiableMap(aggregated);
        this.configurationInputs = Collections.unmodifiableMap(configuration);
        this.reusableInputs = reusable.isEmpty() ? Map.of() : Collections.unmodifiableMap(reusable);
        if (reusable.isEmpty()) this.executionOutputs = this.outputs;
        else {
            Map<K, Long> actual = new LinkedHashMap<>(this.outputs);
            reusable.forEach((key, amount) -> {
                long returned = actual.getOrDefault(key, 0L);
                if (returned < amount) throw new IllegalArgumentException("Missing reusable input balance");
                if (returned == amount) actual.remove(key);
                else actual.put(key, returned - amount);
            });
            if (actual.isEmpty()) throw new IllegalArgumentException("Pattern without physical outputs");
            this.executionOutputs = Collections.unmodifiableMap(actual);
        }
    }

    public String id() {
        return id;
    }

    public String binding() {
        return binding;
    }

    public List<Slot<K>> slots() {
        return slots;
    }

    public Map<K, Long> outputs() {
        return outputs;
    }

    public record Slot<K>(K key, long amount, int inputSlot, boolean configuration, boolean reusable) {

        public Slot(K key, long amount, int inputSlot, boolean configuration) {
            this(key, amount, inputSlot, configuration, false);
        }

        public Slot(K key, long amount, int inputSlot) {
            this(key, amount, inputSlot, false);
        }

        public Slot(K key, long amount) {
            this(key, amount, -1, false);
        }

        public Slot {
            Objects.requireNonNull(key);
            if (amount <= 0) throw new IllegalArgumentException("Non-positive input");
            if (inputSlot < -1) throw new IllegalArgumentException("Invalid input slot");
            if (reusable && !configuration) throw new IllegalArgumentException("Reusable input must be configuration");
        }
    }

    public Map<K, Long> inputs() {
        return inputs;
    }

    public Map<K, Long> configurationInputs() {
        return configurationInputs;
    }

    /** Sealed virtual supply tokens remain owned by the CPU and can configure every push. */
    public Map<K, Long> reusableInputs() {
        return reusableInputs;
    }

    /** Real machine returns; excludes the logical self-return of virtual supply tokens. */
    public Map<K, Long> executionOutputs() {
        return executionOutputs;
    }

    /** Logical planning reserves a safe upper bound; each real push consumes one configuration. */
    public Map<K, Long> dispatchInputs(long batch) {
        Map<K, Long> result = new LinkedHashMap<>();
        for (Slot<K> slot : slots) if (!slot.reusable()) result.merge(slot.key(), CheckedAmounts.multiply(slot.amount(), slot.configuration() ? 1 : batch), CheckedAmounts::add);
        return amounts(result);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GraphRecipe<?> recipe && id.equals(recipe.id) && binding.equals(recipe.binding) &&
                slots.equals(recipe.slots) && outputs.equals(recipe.outputs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, binding, slots, outputs);
    }

    @Override
    public String toString() {
        return "GraphRecipe[id=" + id + ", binding=" + binding + ", slots=" + slots + ", outputs=" + outputs + "]";
    }

    public static <K> Map<K, Long> amounts(Map<K, Long> source) {
        Map<K, Long> result = new LinkedHashMap<>();
        source.forEach((key, amount) -> {
            Objects.requireNonNull(key);
            CheckedAmounts.nonNegative(amount);
            if (amount != 0) result.put(key, amount);
        });
        return Collections.unmodifiableMap(result);
    }
}
