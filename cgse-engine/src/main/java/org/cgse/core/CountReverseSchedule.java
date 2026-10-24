// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** A positive-only schedule candidate grown backwards from the exact final
 * inventory. A forward greedy scan can consume a return path's startup stock;
 * the same fixed counts may be easy to order from the opposite endpoint.
 * Every returned word is checked again against the original recipes and stock. */
final class CountReverseSchedule<K> implements AutoCloseable {
    private static final int SINK = 0;
    private final RecipeCountModel<K> original;
    private final BigInteger[] counts;
    private final PlanningBudget budget;
    private final long allowance;
    private final Map<K, Integer> keys = new LinkedHashMap<>();
    private final Map<Integer, BigInteger> finalStock = new LinkedHashMap<>();
    private final Map<String, GraphRecipe<K>> originals = new LinkedHashMap<>();
    private final Map<String, BigInteger> expected = new LinkedHashMap<>();
    private final List<GraphRecipe<Integer>> recipes = new ArrayList<>();
    private final List<BigInteger> positiveCounts = new ArrayList<>();
    private final Map<PlanStep, PlanStep> reversed = new IdentityHashMap<>();
    private final Deque<Frame> frames = new ArrayDeque<>();
    private RecipeCountModel<Integer> model;
    private CountScheduleTopology<Integer> topology;
    private CountSchedule<Integer> schedule;
    private SummaryComputation<K> summary;
    private PlanCountComputation counting;
    private BigInteger[] values;
    private PlanStep backwards, proposed, witness;
    private int recipe, phase;
    private long memory, work;
    private boolean done;

    CountReverseSchedule(RecipeCountModel<K> original, BigInteger[] counts, PlanningBudget budget) {
        this(original, counts, budget, Math.min(1_048_576, budget.remainingWork() / 2));
    }

    CountReverseSchedule(RecipeCountModel<K> original, BigInteger[] counts, PlanningBudget budget, long maximumWork) {
        this.original = original;
        this.budget = budget;
        allowance = Math.max(0, Math.min(maximumWork, budget.remainingWork()));
        if (counts.length != original.recipes.size()) throw new IllegalArgumentException("Incomplete counts");
        this.counts = counts;
        if (allowance < 4096) { done = true; return; }
        // Covers the request-local key mapping, exact final amounts, reversed
        // ports and both recipe indices. Subsearches reserve their own state.
        long ports = 0, bits = 64, before = budget.threadWork();
        for (int i = 0; i < counts.length; i++) {
            budget.check();
            if (budget.threadWork() - before >= allowance) { done = true; return; }
            if (counts[i].signum() < 0) { done = true; return; }
            if (counts[i].signum() == 0) continue;
            var rule = original.recipes.get(i);
            ports += (long) rule.inputs().size() + rule.outputs().size();
            bits = Math.max(bits, counts[i].bitLength() + 64L);
            for (K key : rule.inputs().keySet()) {
                budget.check();
                if (budget.threadWork() - before >= allowance) { done = true; return; }
                if (!original.external.contains(key)) bits = Math.max(bits, PlanningInventory.exactAmount(original.stock, key).bitLength() + 64L);
            }
            for (K key : rule.outputs().keySet()) {
                budget.check();
                if (budget.threadWork() - before >= allowance) { done = true; return; }
                if (!original.external.contains(key)) bits = Math.max(bits, PlanningInventory.exactAmount(original.stock, key).bitLength() + 64L);
            }
        }
        work = budget.threadWork() - before;
        long bytes = 4096L + 640L * counts.length + (768L + 4L * ((bits + 7) / 8)) * ports;
        if (!budget.tryReserve(bytes)) { done = true; return; }
        memory = bytes;
    }

    boolean step() {
        if (done) return true;
        long before = budget.threadWork();
        try {
            budget.check();
            if (work >= allowance) return stop();
            if (phase == 0) {
                if (recipe < counts.length) return prepareRecipe(recipe++);
                for (var amount : finalStock.values()) {
                    budget.check();
                    if (amount.signum() < 0) return stop();
                }
                values = positiveCounts.toArray(BigInteger[]::new);
                model = RecipeCountModel.forShell(recipes, Map.of(), PlanningInventory.exactSnapshot(finalStock), Set.of(SINK), budget);
                if (model == null) return stop();
                topology = new CountScheduleTopology<>(model, values, budget);
                phase = 1;
                return false;
            }
            if (phase == 1) {
                if (!topology.step()) return false;
                int[] components = topology.result(); topology.close(); topology = null;
                if (components == null) return stop();
                // This is a separately budgeted candidate, not the ordinary
                // eight-pass preferred-order hint. No old continuation is lost.
                schedule = CountSchedule.componentOrdered(model, values, components, budget, 512);
                phase = 2;
                return false;
            }
            if (phase == 2) {
                if (!schedule.step()) return false;
                backwards = schedule.witness();
                if (backwards == null) return stop();
                if (!push(backwards)) return stop();
                phase = 3;
                return false;
            }
            if (phase == 3) {
                if (!reverseStep()) return false;
                proposed = reversed.get(backwards);
                counting = new PlanCountComputation(proposed);
                phase = 4;
                return false;
            }
            if (phase == 4) {
                if (!counting.step(budget)) return false;
                if (counting.result().size() != expected.size()) return stop();
                for (var entry : expected.entrySet()) {
                    budget.check();
                    if (!entry.getValue().equals(counting.result().get(entry.getKey()))) return stop();
                }
                summary = new SummaryComputation<>(proposed, originals, budget);
                phase = 5;
                return false;
            }
            if (!summary.step()) return false;
            for (var need : summary.result().required().entrySet()) {
                budget.check();
                if (!original.external.contains(need.getKey()) &&
                        need.getValue().compareTo(PlanningInventory.exactAmount(original.stock, need.getKey())) > 0)
                    return stop();
            }
            witness = proposed;
            return done = true;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private boolean prepareRecipe(int index) {
        BigInteger count = counts[index];
        if (count.signum() == 0) return false;
        GraphRecipe<K> rule = original.recipes.get(index);
        // Consumed configurations are per-push, not linear per firing. Only
        // their explicitly supplied ports can be projected away here. Reusable
        // configurations are exact read arcs and keep both sides of the arc.
        for (var slot : rule.slots()) {
            budget.check();
            if (slot.configuration() && !slot.reusable() && !original.external.contains(slot.key())) return stop();
        }
        if (originals.putIfAbsent(rule.id(), rule) != null) return stop();
        expected.put(rule.id(), count);
        var slots = new ArrayList<GraphRecipe.Slot<Integer>>();
        var outputs = new LinkedHashMap<Integer, Long>();
        for (var port : rule.inputs().entrySet()) {
            budget.check();
            if (original.external.contains(port.getKey())) continue;
            int id = key(port.getKey());
            outputs.put(id, port.getValue());
            finalStock.merge(id, BigInteger.valueOf(port.getValue()).multiply(count).negate(), BigInteger::add);
        }
        for (var port : rule.outputs().entrySet()) {
            budget.check();
            if (original.external.contains(port.getKey())) continue;
            int id = key(port.getKey());
            slots.add(new GraphRecipe.Slot<>(id, port.getValue()));
            finalStock.merge(id, BigInteger.valueOf(port.getValue()).multiply(count), BigInteger::add);
        }
        // GraphRecipe requires an output. A private, supplied sink represents
        // a reversed source without introducing any original resource supply.
        if (outputs.isEmpty()) outputs.put(SINK, 1L);
        recipes.add(new GraphRecipe<>(rule.id(), rule.binding(), slots, outputs));
        positiveCounts.add(count);
        return false;
    }

    private int key(K key) {
        Integer old = keys.get(key);
        if (old != null) return old;
        int id = keys.size() + 1;
        keys.put(key, id);
        finalStock.put(id, PlanningInventory.exactAmount(original.stock, key));
        return id;
    }

    private boolean push(PlanStep step) {
        long bytes = 256L + (step instanceof PlanStep.Sequence sequence ? 16L * sequence.children().size() : 0);
        if (!budget.tryReserve(bytes)) return false;
        memory += bytes;
        frames.push(new Frame(step));
        return true;
    }

    /** Reverse the compressed DAG, never its expanded firing occurrences. */
    private boolean reverseStep() {
        for (int operation = 0; operation < 32; operation++) {
            budget.check();
            if (frames.isEmpty()) return true;
            Frame frame = frames.peek();
            PlanStep child = frame.next();
            if (child != null && !reversed.containsKey(child)) {
                if (!push(child)) { stop(); return false; }
                continue;
            }
            if (child != null) { frame.add(reversed.get(child)); continue; }
            PlanStep value;
            if (frame.step instanceof PlanStep.Batch) value = frame.step;
            else if (frame.step instanceof PlanStep.Repeat repeat) value = new PlanStep.Repeat(frame.children.get(0), repeat.times());
            else value = new PlanStep.Sequence(frame.children);
            reversed.put(frame.step, value);
            frames.pop();
        }
        return false;
    }

    private static final class Frame {
        final PlanStep step;
        final List<PlanStep> children = new ArrayList<>();
        int position;
        Frame(PlanStep step) {
            this.step = step;
            position = step instanceof PlanStep.Sequence sequence ? sequence.children().size() - 1 : 0;
        }
        PlanStep next() {
            if (step instanceof PlanStep.Repeat repeat) return position == 0 ? repeat.body() : null;
            if (step instanceof PlanStep.Sequence sequence) return position >= 0 ? sequence.children().get(position) : null;
            return null;
        }
        void add(PlanStep child) { children.add(child); position += step instanceof PlanStep.Sequence ? -1 : 1; }
    }

    private boolean stop() { done = true; return true; }
    PlanStep witness() { return witness; }

    @Override public void close() {
        done = true;
        if (summary != null) { summary.close(); summary = null; }
        if (schedule != null) { schedule.close(); schedule = null; }
        if (topology != null) { topology.close(); topology = null; }
        if (model != null) { model.close(); model = null; }
        counting = null; values = null; backwards = proposed = null;
        recipes.clear(); positiveCounts.clear(); keys.clear(); finalStock.clear();
        originals.clear(); expected.clear(); reversed.clear(); frames.clear();
        budget.release(memory); memory = 0;
    }
}
