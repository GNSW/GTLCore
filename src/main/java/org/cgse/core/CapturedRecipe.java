package org.cgse.core;

import java.util.*;

/** Immutable callback results. Combination expansion never touches a live pattern or inventory. */
public final class CapturedRecipe<K> {

    public static final int MAX_VARIANTS = 256;

    public record Amount<K>(K what, long amount) {}

    public record Resolved<K>(List<GraphRecipe.Slot<K>> slots, Map<K, Long> outputs) {

        public Resolved {
            slots = List.copyOf(slots);
            outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
        }
    }

    public record Candidate<K>(Amount<K> stack, K remaining, boolean configuration, boolean reusable) {

        public Candidate(Amount<K> stack, K remaining, boolean configuration) {
            this(stack, remaining, configuration, false);
        }
    }

    public record Input<K>(long multiplier, List<Candidate<K>> candidates) {

        public Input {
            candidates = List.copyOf(candidates);
        }
    }

    private final List<Input<K>> inputs;
    private final List<Amount<K>> outputs;
    private final boolean external;
    private final int[] choices;
    private final int size;
    private final boolean bounded;

    public CapturedRecipe(List<Input<K>> inputs, List<Amount<K>> outputs, boolean external, boolean bounded) {
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
        this.external = external;
        choices = new int[inputs.size()];
        int product = 1;
        for (int slot = 0; slot < inputs.size(); slot++) {
            Input<K> input = inputs.get(slot);
            int count = input.candidates().size();
            if (!external && input.multiplier() <= 9 && count > 1) {
                // Weak compositions, capped before either enumeration or overflow.
                // Singles come first; mixed selections fill the remaining slots.
                if (input.multiplier() > 0) {
                    long combinations = 1;
                    for (int n = 1; n <= input.multiplier(); n++) {
                        combinations = combinations * (count - 1 + n) / n;
                        if (combinations >= MAX_VARIANTS) break;
                    }
                    count = (int) Math.min(MAX_VARIANTS, combinations);
                }
                bounded |= count >= MAX_VARIANTS;
            }
            choices[slot] = count;
            product = Math.min(MAX_VARIANTS + 1, product * count);
        }
        this.size = Math.min(MAX_VARIANTS, product);
        this.bounded = bounded || product > MAX_VARIANTS;
    }

    public int size() {
        return size;
    }

    public boolean bounded() {
        return bounded;
    }

    /**
     * First occurrences in exactly the old Cartesian traversal order, without
     * constructing variants. Candidate c in slot s first appears at c times the
     * suffix product. Mixed selections introduce no keys after the singles.
     * Excluded alternatives must not discover extra providers or consume stock.
     */
    public Iterator<K> dependencies() {
        if (size == 0) return Collections.emptyIterator();
        if (size == 1) return inputs.stream().map(input -> input.candidates().get(0).stack().what()).iterator();
        record Frontier(int slot, int candidate, int stride) {

            int ordinal() {
                return candidate * stride;
            }
        }
        PriorityQueue<Frontier> pending = new PriorityQueue<>(Comparator.comparingInt(Frontier::ordinal)
                .thenComparingInt(Frontier::slot));
        int stride = 1;
        for (int slot = inputs.size() - 1; slot >= 0; slot--) {
            pending.add(new Frontier(slot, 0, stride));
            stride = Math.min(MAX_VARIANTS, stride * choices[slot]);
        }
        return new Iterator<>() {

            @Override
            public boolean hasNext() {
                return !pending.isEmpty();
            }

            @Override
            public K next() {
                if (pending.isEmpty()) throw new NoSuchElementException();
                Frontier next = pending.remove();
                Input<K> input = inputs.get(next.slot());
                int candidate = next.candidate() + 1;
                if (candidate < input.candidates().size() && candidate * next.stride() < size)
                    pending.add(new Frontier(next.slot(), candidate, next.stride()));
                return input.candidates().get(next.candidate()).stack().what();
            }
        };
    }

    public Expansion expand(PlanningBudget budget) {
        return new Expansion(budget);
    }

    public final class Expansion {

        private final PlanningBudget budget;
        private final List<List<List<Picked<K>>>> selections = new ArrayList<>();
        private final int[] indices = new int[inputs.size()];
        private int generated;

        private Expansion(PlanningBudget budget) {
            this.budget = budget;
        }

        public boolean hasNext() {
            return generated < size;
        }

        /** At most 256 variants per pattern; callers yield between variants. */
        public Resolved<K> next() {
            if (!hasNext()) throw new NoSuchElementException();
            budget.compilationCheck();
            if (selections.isEmpty() && !inputs.isEmpty()) {
                for (int slot = 0; slot < inputs.size(); slot++) {
                    Input<K> input = inputs.get(slot);
                    budget.reserve(32L * choices[slot] * (1 + Math.min(9, Math.max(1, input.multiplier()))));
                    List<List<Picked<K>>> selected = new ArrayList<>();
                    for (Candidate<K> candidate : input.candidates()) selected.add(List.of(new Picked<>(candidate, input.multiplier())));
                    if (!external && input.multiplier() <= 9 && input.candidates().size() > 1)
                        mixed(input.candidates(), 0, input.multiplier(), new ArrayList<>(), selected, budget);
                    if (selected.size() != choices[slot]) throw new IllegalStateException("Captured alternative count differs");
                    selections.add(selected);
                }
            }
            int slotCount = 0;
            for (int slot = 0; slot < indices.length; slot++) slotCount += selections.get(slot).get(indices[slot]).size();
            // Include possible return entries before allocating maps or encoded recipes.
            budget.reserve(256L + 64L * (2L * slotCount + outputs.size()));
            List<GraphRecipe.Slot<K>> slots = new ArrayList<>(slotCount);
            Map<K, Long> produced = new LinkedHashMap<>();
            for (var output : outputs) produced.merge(output.what(), output.amount(), CheckedAmounts::add);
            for (int slot = 0; slot < indices.length; slot++) {
                for (Picked<K> picked : selections.get(slot).get(indices[slot])) {
                    Candidate<K> candidate = picked.candidate();
                    slots.add(new GraphRecipe.Slot<>(candidate.stack().what(), CheckedAmounts.multiply(candidate.stack().amount(), picked.copies()),
                            slot, candidate.configuration(), candidate.reusable()));
                    if (candidate.reusable()) produced.merge(candidate.stack().what(),
                            CheckedAmounts.multiply(candidate.stack().amount(), picked.copies()), CheckedAmounts::add);
                    if (candidate.remaining() != null) produced.merge(candidate.remaining(), picked.copies(), CheckedAmounts::add);
                }
            }
            generated++;
            for (int slot = indices.length - 1; slot >= 0; slot--) {
                if (++indices[slot] < choices[slot]) break;
                indices[slot] = 0;
            }
            return new Resolved<>(slots, produced);
        }
    }

    /** Same descending-copy order as the previous DFS, skipping its empty branches. */
    private static <K> void mixed(List<Candidate<K>> candidates, int start, long left, List<Picked<K>> selected,
                                  List<List<Picked<K>>> out, PlanningBudget budget) {
        for (int at = start; at < candidates.size() && out.size() < MAX_VARIANTS; at++) {
            for (long count = left; count > 0 && out.size() < MAX_VARIANTS; count--) {
                budget.compilationCheck();
                selected.add(new Picked<>(candidates.get(at), count));
                if (count == left) {
                    if (selected.size() > 1) out.add(List.copyOf(selected));
                } else mixed(candidates, at + 1, left - count, selected, out, budget);
                selected.remove(selected.size() - 1);
            }
        }
    }

    private record Picked<K>(Candidate<K> candidate, long copies) {}
}
