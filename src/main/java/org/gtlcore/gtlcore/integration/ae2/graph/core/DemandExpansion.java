package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Builds an executable prefix before repeating it. This bounded allocation
 * heuristic can represent nested returns; its result still needs verification.
 */
final class DemandExpansion<K> {

    private final Map<String, GraphRecipe<K>> recipes;
    private final Map<K, List<GraphRecipe<K>>> producers = new HashMap<>();
    private final Map<K, BigInteger> held;
    private final Map<K, BigInteger> goals;
    private final PlanningBudget budget;
    private final Iterator<GraphRecipe<K>> indexing;
    private final Deque<Frame> frames = new ArrayDeque<>();
    private final Map<K, Frame> active = new HashMap<>();
    private final List<Change<K>> undo = new ArrayList<>();
    private final List<PlanStep> path = new ArrayList<>();
    private final long started, allowance;
    private long memory;
    private int phase, rootPasses;
    private boolean failed;
    private PlanStep result;
    private Frame satisfiedAncestor;

    DemandExpansion(Map<String, GraphRecipe<K>> recipes, Map<K, BigInteger> held, Map<K, BigInteger> goals, PlanningBudget budget) {
        this.recipes = recipes;
        this.held = new HashMap<>(held);
        this.goals = goals;
        this.budget = budget;
        indexing = recipes.values().iterator();
        started = budget.nodes();
        allowance = Math.min(1_000_000L, 32_768L + 256L * recipes.size());
        reserve(256L + 96L * (held.size() + goals.size()));
    }

    boolean step() {
        budget.check();
        if (failed || budget.nodes() - started > allowance) return true;
        if (phase == 0) {
            if (indexing.hasNext()) {
                GraphRecipe<K> recipe = indexing.next();
                for (K key : recipe.outputs().keySet()) {
                    budget.check();
                    producers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(recipe);
                    reserve(96);
                }
                return false;
            }
            phase = 1;
        }
        if (satisfiedAncestor != null) {
            Frame satisfied = satisfiedAncestor;
            satisfiedAncestor = null;
            // Making a selected input may already supply an ancestor's actual
            // goal as a coproduct. Keep this executable prefix and discard the
            // now-unnecessary intermediate request, not its produced material.
            if (active.get(satisfied.key) == satisfied && amount(satisfied.key).compareTo(satisfied.wanted) >= 0) {
                while (frames.peek() != satisfied) {
                    budget.check();
                    pop(true);
                }
            }
        }
        if (frames.isEmpty()) {
            for (var goal : goals.entrySet()) {
                budget.check();
                if (amount(goal.getKey()).compareTo(goal.getValue()) < 0) {
                    if (++rootPasses > 64) return true;
                    push(goal.getKey(), goal.getValue());
                    return false;
                }
            }
            result = new PlanStep.Sequence(path);
            return true;
        }
        Frame frame = frames.peek();
        if (frame.summary != null) {
            if (!frame.summary.step()) return false;
            SequenceSummary<K> summary = frame.summary.result();
            frame.summary = null;
            BigInteger gain = summary.delta(frame.key);
            if (gain.signum() <= 0) {
                if (frame.prefixSummary) {
                    frame.prefixSummary = false;
                    frame.body = null;
                } else reject(frame);
                return false;
            }
            BigInteger gap = frame.wanted.subtract(amount(frame.key));
            BigInteger repeats = gap.signum() <= 0 ? BigInteger.ZERO : CheckedAmounts.ceilDiv(gap, gain);
            for (K key : summary.keys()) {
                budget.check();
                BigInteger current = amount(key), need = summary.required(key), change = summary.delta(key);
                if (current.compareTo(need) < 0) {
                    repeats = BigInteger.ZERO;
                    break;
                }
                if (change.signum() < 0)
                    repeats = repeats.min(current.subtract(need).divide(change.negate()).add(BigInteger.ONE));
                Frame ancestor = active.get(key);
                if (change.signum() > 0 && ancestor != null && ancestor != frame) {
                    // Stop at the first useful coproduct breakpoint. Fully
                    // expanding the selected intermediate can manufacture far
                    // more than needed, or consume another branch's seed.
                    BigInteger ancestorGap = ancestor.wanted.subtract(current);
                    if (ancestorGap.signum() > 0)
                        repeats = repeats.min(CheckedAmounts.ceilDiv(ancestorGap, change));
                }
            }
            // One iteration has already run. Replace its trace by a compressed
            // body, avoiding duplicate subtrees at every nesting level.
            BigInteger extra = repeats;
            if (extra.signum() > 0) {
                for (var change : summary.delta().entrySet()) {
                    budget.check();
                    set(change.getKey(), amount(change.getKey()).add(change.getValue().multiply(extra)));
                }
                truncate(frame.prefixSummary ? frame.originalPath : frame.unitPath);
                if (frame.body instanceof PlanStep.Batch batch)
                    append(PlanStep.batch(batch.recipe(), BigInteger.valueOf(batch.runs()).multiply(extra.add(BigInteger.ONE))));
                else append(PlanStep.repeat(frame.body, extra.add(BigInteger.ONE)));
            }
            frame.body = null;
            frame.unitStarted = false;
            if (!frame.prefixSummary && frames.size() == 1 && frame.iterations > 1 &&
                    amount(frame.key).compareTo(frame.wanted) < 0) {
                // A shared cycle may alternate several different input routes:
                // each individual iteration drains a finite intermediate, while
                // their composition restores it. Compile that actual prefix as
                // one macro and apply the same exact requirement/delta check.
                // Never extrapolate from net target output alone.
                frame.prefixSummary = true;
                List<PlanStep> prefix = path.subList(frame.originalPath, path.size());
                frame.body = new PlanStep.Sequence(prefix);
                reserve(64L + 8L * prefix.size());
                frame.summary = new SummaryComputation<>(frame.body, recipes, budget);
            } else frame.prefixSummary = false;
            return false;
        }
        if (amount(frame.key).compareTo(frame.wanted) >= 0) {
            pop(true);
            return false;
        }
        if (frame.recipe == null) {
            if (frame.producer >= frame.alternatives.size()) {
                pop(false);
                return false;
            }
            frame.recipe = frame.alternatives.get(frame.producer++);
        }
        if (!frame.unitStarted) {
            if (++frame.iterations > 256) {
                pop(false);
                return false;
            }
            frame.unitStarted = true;
            frame.unitUndo = undo.size();
            frame.unitPath = path.size();
        }
        // Recheck all inputs after a child completes: producing a later input
        // may temporarily consume and return an earlier catalyst.
        for (var input : frame.recipe.inputs().entrySet()) {
            budget.check();
            if (amount(input.getKey()).compareTo(BigInteger.valueOf(input.getValue())) >= 0) continue;
            if (active.containsKey(input.getKey()) || frames.size() >= 4096) reject(frame);
            else push(input.getKey(), BigInteger.valueOf(input.getValue()));
            return false;
        }
        for (var input : frame.recipe.inputs().entrySet()) {
            budget.check();
            set(input.getKey(), amount(input.getKey()).subtract(BigInteger.valueOf(input.getValue())));
        }
        for (var output : frame.recipe.outputs().entrySet()) {
            budget.check();
            set(output.getKey(), amount(output.getKey()).add(BigInteger.valueOf(output.getValue())));
        }
        append(new PlanStep.Batch(frame.recipe.id(), 1));
        List<PlanStep> unit = path.subList(frame.unitPath, path.size());
        frame.body = unit.size() == 1 ? unit.get(0) : new PlanStep.Sequence(unit);
        reserve(64L + 8L * unit.size());
        frame.summary = new SummaryComputation<>(frame.body, recipes, budget);
        return false;
    }

    private BigInteger amount(K key) {
        return held.getOrDefault(key, BigInteger.ZERO);
    }

    private void push(K key, BigInteger wanted) {
        reserve(256);
        Frame frame = new Frame(key, wanted);
        frames.push(frame);
        active.put(key, frame);
    }

    private void pop(boolean success) {
        Frame frame = frames.pop();
        active.remove(frame.key);
        release(256);
        if (!success) {
            rollback(frame.originalUndo);
            truncate(frame.originalPath);
            if (frames.isEmpty()) failed = true;
            else reject(frames.peek());
        }
    }

    private void reject(Frame frame) {
        rollback(frame.unitUndo);
        truncate(frame.unitPath);
        frame.recipe = null;
        frame.unitStarted = false;
        frame.summary = null;
        frame.body = null;
        frame.prefixSummary = false;
    }

    private void set(K key, BigInteger value) {
        if (value.signum() < 0) throw new IllegalStateException("Unfunded demand expansion");
        BigInteger previous = amount(key);
        if (previous.equals(value)) return;
        reserve(128);
        undo.add(new Change<>(key, previous));
        if (value.signum() == 0) held.remove(key);
        else held.put(key, value);
        Frame ancestor = active.get(key);
        if (ancestor != null && ancestor != frames.peek() && value.compareTo(ancestor.wanted) >= 0)
            satisfiedAncestor = ancestor;
    }

    private void rollback(int mark) {
        while (undo.size() > mark) {
            budget.check();
            Change<K> change = undo.remove(undo.size() - 1);
            if (change.previous().signum() == 0) held.remove(change.key());
            else held.put(change.key(), change.previous());
            release(128);
        }
    }

    private void append(PlanStep step) {
        reserve(64);
        path.add(step);
    }

    private void truncate(int mark) {
        // Removed nodes may still be referenced by a Repeat body. Keep their
        // conservative reservation until this whole speculative pass is closed.
        while (path.size() > mark) {
            budget.check();
            path.remove(path.size() - 1);
        }
    }

    PlanStep result() {
        return result;
    }

    void close() {
        budget.release(memory);
        memory = 0;
    }

    private void reserve(long bytes) {
        budget.reserve(bytes);
        memory += bytes;
    }

    private void release(long bytes) {
        budget.release(bytes);
        memory -= bytes;
    }

    private record Change<K>(K key, BigInteger previous) {}

    private final class Frame {

        final K key;
        final BigInteger wanted;
        final List<GraphRecipe<K>> alternatives;
        final int originalUndo = undo.size(), originalPath = path.size();
        int producer, unitUndo, unitPath, iterations;
        boolean unitStarted, prefixSummary;
        GraphRecipe<K> recipe;
        PlanStep body;
        SummaryComputation<K> summary;

        Frame(K key, BigInteger wanted) {
            this.key = key;
            this.wanted = wanted;
            alternatives = producers.getOrDefault(key, List.of());
        }
    }
}
