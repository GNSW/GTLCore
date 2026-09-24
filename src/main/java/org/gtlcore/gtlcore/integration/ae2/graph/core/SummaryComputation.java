package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit traversal/fold stack: yielding never recomputes a sequence prefix. */
public final class SummaryComputation<K> {

    private final Map<String, GraphRecipe<K>> recipes;
    private final PlanningBudget budget;
    private final Deque<Frame> stack = new ArrayDeque<>();
    private SequenceSummary<K> result;

    public SummaryComputation(PlanStep step, Map<String, GraphRecipe<K>> recipes, PlanningBudget budget) {
        this.recipes = recipes;
        this.budget = budget;
        stack.push(new Frame(step));
    }

    public boolean step() {
        budget.check();
        if (result != null) return true;
        Frame frame = stack.peek();
        if (frame.ordinary != null) {
            for (int operation = 0; operation < 32; operation++) {
                budget.check();
                if (frame.ordinaryEntries.hasNext()) {
                    var entry = frame.ordinaryEntries.next();
                    K key = entry.getKey();
                    BigInteger quantity = entry.getValue() == 1 ? frame.ordinaryRuns :
                            BigInteger.valueOf(entry.getValue()).multiply(frame.ordinaryRuns);
                    BigInteger change = frame.change.getOrDefault(key, BigInteger.ZERO);
                    if (frame.ordinaryInputs) {
                        frame.need.merge(key, quantity.subtract(change).max(BigInteger.ZERO), BigInteger::max);
                        frame.peak.merge(key, change, BigInteger::max);
                        frame.change.put(key, change.subtract(quantity));
                    } else {
                        BigInteger after = change.add(quantity);
                        frame.need.merge(key, change.negate().max(BigInteger.ZERO), BigInteger::max);
                        frame.peak.merge(key, after, BigInteger::max);
                        frame.change.put(key, after);
                    }
                    continue;
                }
                if (frame.ordinaryInputs) {
                    frame.ordinaryInputs = false;
                    frame.ordinaryEntries = frame.ordinary.outputs().entrySet().iterator();
                    continue;
                }
                frame.ordinary = null;
                break;
            }
            if (frame.ordinary != null) return false;
        }
        if (frame.pending != null) {
            if (frame.keys.hasNext()) {
                K key = frame.keys.next();
                BigInteger change = frame.change.getOrDefault(key, BigInteger.ZERO);
                frame.need.merge(key, frame.pending.required(key).subtract(change).max(BigInteger.ZERO), BigInteger::max);
                frame.peak.merge(key, change.add(frame.pending.peak(key)), BigInteger::max);
                frame.change.put(key, change.add(frame.pending.delta(key)));
                return false;
            }
            frame.pending = null;
            frame.keys = null;
            if (frame.step instanceof PlanStep.Repeat repeat) frame.repeat = repeat.times();
        }
        if (frame.step instanceof PlanStep.Batch batch) {
            if (frame.entries == null && frame.phase == 0) {
                var recipe = recipes.get(batch.recipe());
                if (recipe == null) throw new IllegalArgumentException("Unknown recipe " + batch.recipe());
                frame.entries = recipe.inputs().entrySet().iterator();
            }
            if (frame.phase == 0) {
                if (frame.entries.hasNext()) {
                    var input = frame.entries.next();
                    frame.need.put(input.getKey(), BigInteger.valueOf(input.getValue()));
                    frame.change.put(input.getKey(), BigInteger.valueOf(input.getValue()).negate());
                    return false;
                }
                frame.entries = recipes.get(batch.recipe()).outputs().entrySet().iterator();
                frame.phase = 1;
            }
            if (frame.phase == 1) {
                if (frame.entries.hasNext()) {
                    var output = frame.entries.next();
                    frame.change.merge(output.getKey(), BigInteger.valueOf(output.getValue()), BigInteger::add);
                    return false;
                }
                frame.repeat = batch.runs();
                frame.phase = 2;
            }
        } else if (frame.step instanceof PlanStep.Sequence sequence) {
            if (frame.child < sequence.children().size()) {
                PlanStep child = sequence.children().get(frame.child++);
                if (child instanceof PlanStep.Batch batch && batch.runs() > 0) {
                    var recipe = recipes.get(batch.recipe());
                    if (recipe == null) throw new IllegalArgumentException("Unknown recipe " + batch.recipe());
                    if (Collections.disjoint(recipe.inputs().keySet(), recipe.outputs().keySet())) {
                        // For an ordinary batch, exact prefix need and peak have
                        // a closed form. Fold directly into the parent; allocating
                        // and copying three tiny maps per recipe is unnecessary.
                        frame.ordinary = recipe;
                        frame.ordinaryRuns = BigInteger.valueOf(batch.runs());
                        frame.ordinaryInputs = true;
                        frame.ordinaryEntries = recipe.inputs().entrySet().iterator();
                        return false;
                    }
                }
                stack.push(new Frame(child));
                return false;
            }
        } else if (frame.child == 0) {
            frame.child++;
            stack.push(new Frame(((PlanStep.Repeat) frame.step).body()));
            return false;
        }
        // A sequence already contains its exact prefix need, delta and peak.
        // Rewriting every entry for repeat(1) only repeats the same arithmetic
        // and budget bookkeeping. Batches still need their peak initialized.
        if (frame.keys == null) frame.keys = frame.repeat == 1 && !(frame.step instanceof PlanStep.Batch) ?
                Collections.emptyIterator() : frame.change.keySet().iterator();
        if (frame.keys.hasNext()) {
            K key = frame.keys.next();
            BigInteger delta = frame.change.get(key);
            BigInteger n = BigInteger.valueOf(frame.repeat);
            if (frame.repeat == 0) {
                frame.need.put(key, BigInteger.ZERO);
                frame.change.put(key, BigInteger.ZERO);
                frame.peak.put(key, BigInteger.ZERO);
            } else {
                BigInteger peak = frame.step instanceof PlanStep.Batch ? delta.max(BigInteger.ZERO) : frame.peak.getOrDefault(key, BigInteger.ZERO);
                frame.need.put(key, frame.need.getOrDefault(key, BigInteger.ZERO)
                        .add(delta.negate().max(BigInteger.ZERO).multiply(n.subtract(BigInteger.ONE))));
                frame.change.put(key, delta.multiply(n));
                frame.peak.put(key, peak.add(delta.max(BigInteger.ZERO).multiply(n.subtract(BigInteger.ONE))));
            }
            return false;
        }
        SequenceSummary<K> complete = new SequenceSummary<>(frame.need, frame.change, frame.peak);
        stack.pop();
        if (stack.isEmpty()) result = complete;
        else {
            Frame parent = stack.peek();
            parent.pending = complete;
            parent.keys = complete.delta().keySet().iterator();
        }
        return result != null;
    }

    public SequenceSummary<K> result() {
        if (result == null) throw new IllegalStateException("Summary is incomplete");
        return result;
    }

    private final class Frame {

        private final PlanStep step;
        private final Map<K, BigInteger> need = new LinkedHashMap<>(), change = new LinkedHashMap<>(), peak = new LinkedHashMap<>();
        private Iterator<Map.Entry<K, Long>> entries;
        private Iterator<K> keys;
        private SequenceSummary<K> pending;
        private GraphRecipe<K> ordinary;
        private Iterator<Map.Entry<K, Long>> ordinaryEntries;
        private boolean ordinaryInputs;
        private BigInteger ordinaryRuns;
        private int child, phase;
        private long repeat = 1;

        private Frame(PlanStep step) {
            this.step = step;
        }
    }
}
