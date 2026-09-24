package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** O(plan depth) live state even for trillions of repeated operations. */
public final class PlanCursor {

    private final List<PlanStep> nodes = new ArrayList<>();
    private final Map<PlanStep, Integer> ids = new IdentityHashMap<>();
    private final List<Frame> stack = new ArrayList<>();

    public PlanCursor(PlanStep root) {
        index(root);
        push(root);
    }

    public PlanCursor(PlanStep root, List<Position> saved) {
        index(root);
        if (saved.size() > nodes.size()) throw new IllegalArgumentException("Invalid cursor depth");
        for (Position position : saved) {
            if (position.node() < 0 || position.node() >= nodes.size()) throw new IllegalArgumentException("Invalid cursor node");
            PlanStep step = nodes.get(position.node());
            long limit = step instanceof PlanStep.Batch batch ? batch.runs() :
                    step instanceof PlanStep.Repeat repeat ? repeat.times() : ((PlanStep.Sequence) step).children().size();
            if (position.remaining() < 0 || position.remaining() > limit) throw new IllegalArgumentException("Invalid cursor amount");
            if (stack.isEmpty()) {
                if (position.node() != 0) throw new IllegalArgumentException("Cursor root mismatch");
            } else {
                Frame parent = stack.get(stack.size() - 1);
                PlanStep expected;
                if (parent.step instanceof PlanStep.Sequence sequence) {
                    int child = sequence.children().size() - Math.toIntExact(parent.remaining) - 1;
                    if (child < 0 || child >= sequence.children().size()) throw new IllegalArgumentException("Invalid sequence cursor");
                    expected = sequence.children().get(child);
                } else if (parent.step instanceof PlanStep.Repeat repeat && parent.remaining < repeat.times()) {
                    expected = repeat.body();
                } else throw new IllegalArgumentException("Invalid cursor parent");
                if (expected != step) throw new IllegalArgumentException("Cursor path mismatch");
            }
            stack.add(new Frame(step, position.remaining()));
        }
    }

    private void index(PlanStep step) {
        if (ids.containsKey(step)) return;
        ids.put(step, nodes.size());
        nodes.add(step);
        if (step instanceof PlanStep.Sequence sequence) for (PlanStep child : sequence.children()) index(child);
        else if (step instanceof PlanStep.Repeat repeat) index(repeat.body());
    }

    private void push(PlanStep step) {
        long remaining = step instanceof PlanStep.Batch batch ? batch.runs() :
                step instanceof PlanStep.Repeat repeat ? repeat.times() : ((PlanStep.Sequence) step).children().size();
        if (!hasWork(step)) remaining = 0;
        stack.add(new Frame(step, remaining));
    }

    private static boolean hasWork(PlanStep step) {
        if (step instanceof PlanStep.Batch batch) return batch.runs() > 0;
        if (step instanceof PlanStep.Repeat repeat) return repeat.times() > 0 && hasWork(repeat.body());
        return ((PlanStep.Sequence) step).children().stream().anyMatch(PlanCursor::hasWork);
    }

    public PlanStep.Batch current() {
        while (!stack.isEmpty()) {
            Frame frame = stack.get(stack.size() - 1);
            if (frame.remaining == 0) {
                stack.remove(stack.size() - 1);
                continue;
            }
            if (frame.step instanceof PlanStep.Batch batch) return new PlanStep.Batch(batch.recipe(), frame.remaining);
            if (frame.step instanceof PlanStep.Sequence sequence) {
                PlanStep child = sequence.children().get(sequence.children().size() - Math.toIntExact(frame.remaining));
                frame.remaining--;
                push(child);
            } else {
                frame.remaining--;
                push(((PlanStep.Repeat) frame.step).body());
            }
        }
        return null;
    }

    public void dispatched(long runs) {
        if (stack.isEmpty()) throw new IllegalStateException("No active step");
        Frame frame = stack.get(stack.size() - 1);
        if (!(frame.step instanceof PlanStep.Batch) || runs <= 0 || runs > frame.remaining) throw new IllegalArgumentException("Invalid accepted batch");
        frame.remaining -= runs;
    }

    public List<Position> snapshot() {
        return stack.stream().map(frame -> new Position(ids.get(frame.step), frame.remaining)).toList();
    }

    public Map<String, Long> remainingCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Frame frame : stack) {
            if (frame.step instanceof PlanStep.Batch batch) {
                if (frame.remaining > 0) result.merge(batch.recipe(), frame.remaining, CheckedAmounts::add);
            } else if (frame.step instanceof PlanStep.Repeat repeat) {
                count(repeat.body(), frame.remaining, result);
            } else {
                var children = ((PlanStep.Sequence) frame.step).children();
                for (int i = children.size() - Math.toIntExact(frame.remaining); i < children.size(); i++) count(children.get(i), 1, result);
            }
        }
        return result;
    }

    private static void count(PlanStep step, long multiplier, Map<String, Long> counts) {
        if (multiplier == 0) return;
        if (step instanceof PlanStep.Batch batch) {
            long runs = CheckedAmounts.multiply(batch.runs(), multiplier);
            if (runs > 0) counts.merge(batch.recipe(), runs, CheckedAmounts::add);
        } else if (step instanceof PlanStep.Repeat repeat) count(repeat.body(), CheckedAmounts.multiply(repeat.times(), multiplier), counts);
        else for (PlanStep child : ((PlanStep.Sequence) step).children()) count(child, multiplier, counts);
    }

    public record Position(int node, long remaining) {}

    private static final class Frame {

        final PlanStep step;
        long remaining;

        Frame(PlanStep step, long remaining) {
            this.step = step;
            this.remaining = remaining;
        }
    }
}
