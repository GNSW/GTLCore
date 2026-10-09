// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Portable graph/program rows. Hosts supply icons, identity, encoding and byte-bounded pages. */
public final class PlanRingView<K> {

    public static final int MAX_ROWS = 400_000;
    public static final int MAX_SLOTS = 512;

    public enum Kind {
        RESOURCE,
        RECIPE,
        BATCH,
        SEQUENCE,
        REPEAT,
        REFERENCE
    }

    public record Amount<K>(K key, BigInteger amount) {

        public Amount {
            ExactAmounts.of(amount);
        }
    }

    /** Resource is populated only for resource rows; recipe IDs and reference indices are opaque text. */
    public record Row<K>(Kind kind, String id, K resource, BigInteger count, BigInteger seed, BigInteger missing,
                         List<Amount<K>> inputs, List<Amount<K>> outputs, int parent) {

        public Row {
            ExactAmounts.of(count);
            ExactAmounts.of(seed);
            ExactAmounts.of(missing);
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
        }
    }

    private final Amount<K> target;
    private final boolean preserveSeeds;
    private final List<Row<K>> rows;
    private final int graphRows;

    public PlanRingView(GraphPlan<K> plan, Collection<String> selected) {
        target = new Amount<>(plan.target(), BigInteger.valueOf(plan.amount()));
        preserveSeeds = plan.preserveSeeds();
        var cursor = new Cursor<>(plan, selected);
        List<Row<K>> collected = new ArrayList<>();
        while (!cursor.advance()) {
            if (cursor.row() != null) collected.add(cursor.row());
        }
        rows = List.copyOf(collected);
        graphRows = cursor.graphRows();
    }

    public Amount<K> target() {
        return target;
    }

    public boolean preserveSeeds() {
        return preserveSeeds;
    }

    public List<Row<K>> rows() {
        return rows;
    }

    public int graphRows() {
        return graphRows;
    }

    /** Stable index slices; transport byte limits remain the responsibility of the host. */
    public List<Row<K>> rows(int offset, int limit) {
        if (offset < 0 || offset > rows.size() || limit < 0)
            throw new IllegalArgumentException("Invalid graph display slice");
        return rows.subList(offset, offset + Math.min(limit, rows.size() - offset));
    }

    /**
     * One traversal operation per advance, including empty iterator transitions.
     * A host may convert each emitted row immediately without retaining a second full view.
     */
    public static final class Cursor<K> {

        private final GraphPlan<K> plan;
        private final Deque<Iterator<K>> resources = new ArrayDeque<>();
        private final Set<K> seenResources = new HashSet<>();
        private final Iterator<String> selected;
        private final IdentityHashMap<PlanStep, Integer> programs = new IdentityHashMap<>();
        private final Deque<Pending> pending = new ArrayDeque<>();
        private Row<K> row;
        private int size;
        private int graphRows;

        private record Pending(Iterator<PlanStep> children, int parent) {}

        public Cursor(GraphPlan<K> plan, Collection<String> selected) {
            this.plan = plan;
            resources.add(plan.initialExact().keySet().iterator());
            resources.add(plan.seeds().keySet().iterator());
            resources.add(plan.missingExact().keySet().iterator());
            this.selected = selected.iterator();
            pending.push(new Pending(List.of(plan.steps()).iterator(), -1));
        }

        /** Returns true when complete; row() is null when this operation did not emit a row. */
        public boolean advance() {
            row = null;
            if (!resources.isEmpty()) {
                if (!resources.peek().hasNext()) {
                    resources.pop();
                    return false;
                }
                K key = resources.peek().next();
                if (!seenResources.add(key)) return false;
                row = new Row<>(Kind.RESOURCE, "", key,
                        plan.initialExact().getOrDefault(key, BigInteger.ZERO),
                        BigInteger.valueOf(plan.seeds().getOrDefault(key, 0L)),
                        plan.missingExact().getOrDefault(key, BigInteger.ZERO), List.of(), List.of(), -1);
            } else if (selected.hasNext()) {
                String id = selected.next();
                var recipe = plan.recipes().get(id);
                if (recipe.inputs().size() > MAX_SLOTS || recipe.outputs().size() > MAX_SLOTS)
                    throw new IllegalArgumentException("Too many graph display slots");
                row = new Row<>(Kind.RECIPE, id, null, plan.patternTimesExact().get(id), BigInteger.ZERO,
                        BigInteger.ZERO, amounts(recipe.inputs()), amounts(recipe.executionOutputs()), -1);
            } else if (!pending.isEmpty()) {
                if (programs.isEmpty()) graphRows = size;
                var next = pending.peek();
                if (!next.children().hasNext()) {
                    pending.pop();
                    return false;
                }
                var step = next.children().next();
                int index = size;
                Integer reference = programs.putIfAbsent(step, index);
                if (reference != null) row = program(Kind.REFERENCE, reference.toString(), BigInteger.ONE, next.parent());
                else if (step instanceof PlanStep.Batch batch)
                    row = program(Kind.BATCH, batch.recipe(), BigInteger.valueOf(batch.runs()), next.parent());
                else if (step instanceof PlanStep.Repeat repeat) {
                    row = program(Kind.REPEAT, "", BigInteger.valueOf(repeat.times()), next.parent());
                    pending.push(new Pending(List.of(repeat.body()).iterator(), index));
                } else {
                    row = program(Kind.SEQUENCE, "", BigInteger.ONE, next.parent());
                    pending.push(new Pending(((PlanStep.Sequence) step).children().iterator(), index));
                }
            } else return true;
            if (++size > MAX_ROWS) throw new IllegalArgumentException("Graph display exceeds row limit");
            return false;
        }

        public Row<K> row() {
            return row;
        }

        public int size() {
            return size;
        }

        public int graphRows() {
            return graphRows;
        }

        private Row<K> program(Kind kind, String id, BigInteger count, int parent) {
            return new Row<>(kind, id, null, count, BigInteger.ZERO, BigInteger.ZERO, List.of(), List.of(), parent);
        }

        private static <K> List<Amount<K>> amounts(Map<K, Long> values) {
            return values.entrySet().stream()
                    .map(entry -> new Amount<>(entry.getKey(), BigInteger.valueOf(entry.getValue()))).toList();
        }
    }
}
