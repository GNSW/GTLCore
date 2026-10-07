package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** O(plan depth) live state even for trillions of repeated operations. */
public final class PlanCursor {

    private final List<PlanStep> nodes = new ArrayList<>();
    private final Map<PlanStep, Integer> ids = new IdentityHashMap<>();
    private final Map<PlanStep, Homogeneous> homogeneous = new IdentityHashMap<>();
    private final Map<PlanStep, BigInteger[]> prefixes = new IdentityHashMap<>();
    private final Set<PlanStep> work = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<PlanStep, Map<String, BigInteger>> loopCounts = new IdentityHashMap<>();
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

    private void index(PlanStep root) {
        // Preserve the original pre-order node IDs used by saved cursors, but
        // fold shared children only once and keep traversal off the Java stack.
        var pending = new ArrayList<IndexFrame>();
        ids.put(root, 0);
        nodes.add(root);
        pending.add(new IndexFrame(root));
        while (!pending.isEmpty()) {
            IndexFrame frame = pending.get(pending.size() - 1);
            PlanStep child = null;
            if (frame.step instanceof PlanStep.Sequence sequence && frame.child < sequence.children().size())
                child = sequence.children().get(frame.child++);
            else if (frame.step instanceof PlanStep.Repeat repeat && frame.child++ == 0) child = repeat.body();
            if (child == null) {
                summarize(frame.step);
                pending.remove(pending.size() - 1);
            } else if (!ids.containsKey(child)) {
                ids.put(child, nodes.size());
                nodes.add(child);
                pending.add(new IndexFrame(child));
            }
        }
    }

    private void summarize(PlanStep step) {
        if (step instanceof PlanStep.Batch batch) {
            if (batch.runs() > 0) work.add(step);
            homogeneous.put(step, new Homogeneous(batch.recipe(), BigInteger.valueOf(batch.runs())));
        } else if (step instanceof PlanStep.Repeat repeat) {
            if (repeat.times() > 0 && work.contains(repeat.body())) work.add(step);
            Homogeneous body = homogeneous.get(repeat.body());
            if (repeat.times() == 0) homogeneous.put(step, new Homogeneous(null, BigInteger.ZERO));
            else if (body != null) homogeneous.put(step, new Homogeneous(body.recipe, body.runs.multiply(BigInteger.valueOf(repeat.times()))));
        } else {
            var children = ((PlanStep.Sequence) step).children();
            for (PlanStep child : children) if (work.contains(child)) {
                work.add(step);
                break;
            }
            String recipe = null;
            BigInteger runs = BigInteger.ZERO;
            BigInteger[] sums = new BigInteger[children.size() + 1];
            sums[0] = BigInteger.ZERO;
            for (int i = 0; i < children.size(); i++) {
                PlanStep child = children.get(i);
                Homogeneous part = homogeneous.get(child);
                if (part == null) return;
                sums[i + 1] = runs = runs.add(part.runs);
                if (part.runs.signum() == 0) continue;
                if (recipe != null && !recipe.equals(part.recipe)) return;
                recipe = part.recipe;
            }
            homogeneous.put(step, new Homogeneous(recipe, runs));
            prefixes.put(step, sums);
        }
    }

    private void push(PlanStep step) {
        long remaining = step instanceof PlanStep.Batch batch ? batch.runs() :
                step instanceof PlanStep.Repeat repeat ? repeat.times() : ((PlanStep.Sequence) step).children().size();
        if (!work.contains(step)) remaining = 0;
        stack.add(new Frame(step, remaining));
    }

    public PlanStep.Batch current() {
        while (!stack.isEmpty()) {
            Frame frame = stack.get(stack.size() - 1);
            if (frame.remaining == 0) {
                stack.remove(stack.size() - 1);
                continue;
            }
            if (frame.step instanceof PlanStep.Batch batch) return new PlanStep.Batch(batch.recipe(), frame.remaining);
            // A shared sequence can contain exponentially many occurrences of
            // one recipe too. Batch its unvisited suffix without expanding it.
            if (frame.step instanceof PlanStep.Sequence sequence && homogeneous.containsKey(frame.step)) {
                Homogeneous summary = homogeneous.get(frame.step);
                BigInteger count = summary.runs.subtract(prefixes.get(frame.step)[sequence.children().size() - Math.toIntExact(frame.remaining)]);
                if (count.signum() > 0) return new PlanStep.Batch(summary.recipe, ExactAmounts.capped(count));
                frame.remaining = 0;
                continue;
            }
            if (frame.step instanceof PlanStep.Repeat repeat) {
                Homogeneous body = homogeneous.get(repeat.body());
                if (body != null && body.runs.signum() > 0)
                    return new PlanStep.Batch(body.recipe, ExactAmounts.capped(body.runs.multiply(BigInteger.valueOf(frame.remaining))));
            }
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

    /** Null keeps the original witness; an empty list asks the pipeline to make room. */
    <K> List<PlanStep.Batch> takeLoopBatch(Map<String, GraphRecipe<K>> recipes, int room,
                                           Supplier<Function<K, BigInteger>> stock) {
        while (!stack.isEmpty()) {
            Frame frame = stack.get(stack.size() - 1);
            if (frame.remaining == 0) {
                stack.remove(stack.size() - 1);
            } else if (frame.step instanceof PlanStep.Sequence sequence) {
                if (homogeneous.containsKey(frame.step)) return null;
                PlanStep child = sequence.children().get(sequence.children().size() - Math.toIntExact(frame.remaining));
                frame.remaining--;
                push(child);
            } else if (frame.step instanceof PlanStep.Repeat repeat && frame.remaining > 1) {
                Map<String, BigInteger> counts = loopCounts.computeIfAbsent(repeat.body(), LoopBatching::counts);
                if (counts.size() < 2) return null;
                Function<K, BigInteger> available = stock.get();
                if (available == null) return null;
                var group = LoopBatching.group(counts, frame.remaining, recipes, available);
                if (group == null) return null;
                if (counts.size() > room) return List.of();
                var batches = new ArrayList<PlanStep.Batch>(counts.size());
                group.counts().forEach((recipe, count) -> batches.add(new PlanStep.Batch(recipe,
                        count.multiply(BigInteger.valueOf(group.iterations())).longValueExact())));
                frame.remaining -= group.iterations();
                return batches;
            } else return null;
        }
        return null;
    }

    public void dispatched(long runs) {
        PlanStep.Batch batch = current();
        if (batch == null || runs <= 0 || runs > batch.runs()) throw new IllegalArgumentException("Invalid accepted batch");
        BigInteger left = BigInteger.valueOf(runs);
        while (left.signum() > 0) {
            Frame frame = stack.get(stack.size() - 1);
            if (frame.remaining == 0) {
                stack.remove(stack.size() - 1);
            } else if (frame.step instanceof PlanStep.Batch) {
                long used = Math.min(frame.remaining, left.longValueExact());
                frame.remaining -= used;
                left = left.subtract(BigInteger.valueOf(used));
            } else if (frame.step instanceof PlanStep.Repeat repeat) {
                BigInteger perIteration = homogeneous.get(repeat.body()).runs;
                if (perIteration.signum() == 0) {
                    frame.remaining = 0;
                    continue;
                }
                long whole = left.divide(perIteration).min(BigInteger.valueOf(frame.remaining)).longValueExact();
                frame.remaining -= whole;
                left = left.subtract(perIteration.multiply(BigInteger.valueOf(whole)));
                if (left.signum() > 0 && frame.remaining > 0) {
                    frame.remaining--;
                    push(repeat.body());
                }
            } else {
                var sequence = (PlanStep.Sequence) frame.step;
                PlanStep child = sequence.children().get(sequence.children().size() - Math.toIntExact(frame.remaining));
                frame.remaining--;
                Homogeneous part = homogeneous.get(child);
                if (part != null && left.compareTo(part.runs) >= 0) left = left.subtract(part.runs);
                else push(child);
            }
        }
    }

    public List<Position> snapshot() {
        return stack.stream().map(frame -> new Position(ids.get(frame.step), frame.remaining)).toList();
    }

    public Map<String, Long> remainingCounts() {
        return ExactAmounts.longView(remainingCountsExact());
    }

    public Map<String, BigInteger> remainingCountsExact() {
        return PlanCountComputation.of(remainingSteps());
    }

    /** The live inner suffix precedes its ancestors' unvisited siblings. */
    PlanStep remainingSteps() {
        // Count all live suffixes in a single shared traversal. Counting each
        // ancestor's suffix independently is quadratic for nested shared calls.
        var remaining = new ArrayList<PlanStep>();
        for (int i = stack.size() - 1; i >= 0; i--) {
            Frame frame = stack.get(i);
            if (frame.remaining == 0) continue;
            if (frame.step instanceof PlanStep.Batch batch) {
                remaining.add(new PlanStep.Batch(batch.recipe(), frame.remaining));
            } else if (frame.step instanceof PlanStep.Repeat repeat) {
                remaining.add(new PlanStep.Repeat(repeat.body(), frame.remaining));
            } else {
                var children = ((PlanStep.Sequence) frame.step).children();
                remaining.addAll(children.subList(children.size() - Math.toIntExact(frame.remaining), children.size()));
            }
        }
        return remaining.size() == 1 ? remaining.get(0) : new PlanStep.Sequence(remaining);
    }

    public record Position(int node, long remaining) {}

    private record Homogeneous(String recipe, BigInteger runs) {}

    private static final class IndexFrame {

        final PlanStep step;
        int child;

        IndexFrame(PlanStep step) {
            this.step = step;
        }
    }

    private static final class Frame {

        final PlanStep step;
        long remaining;

        Frame(PlanStep step, long remaining) {
            this.step = step;
            this.remaining = remaining;
        }
    }
}
