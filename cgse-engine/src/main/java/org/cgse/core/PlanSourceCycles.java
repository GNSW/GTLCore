// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Compile fragmented source cycles into an adaptive repetition. This optional
 * rewrite keeps every firing and proves the complete original inventory contract. */
final class PlanSourceCycles<K> {
    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }
    private final Map<String, GraphRecipe<K>> recipes;
    private final PlanningBudget budget;
    private final long started, allowance;
    private final K target;
    private final long amount;
    private final Map<K, Long> seeds;
    private final boolean preserve;
    private long memory;
    private final IdentityHashMap<PlanStep, PlanStep> rewritten = new IdentityHashMap<>();
    private final List<PlanStep> order = new ArrayList<>();

    private PlanSourceCycles(Map<String, GraphRecipe<K>> recipes, PlanningBudget budget,
                             K target, long amount, Map<K, Long> seeds, boolean preserve) {
        this.recipes = recipes; this.budget = budget;
        this.target = target; this.amount = amount; this.seeds = seeds; this.preserve = preserve;
        started = budget.threadWork();
        allowance = Math.min(262144, budget.remainingWork() / 16);
    }

    static <K> PlanStep optimize(PlanStep original, Map<String, GraphRecipe<K>> recipes, PlanningBudget budget) {
        return optimize(original, recipes, budget, null, 0, Map.of(), false);
    }

    static <K> PlanStep optimize(PlanStep original, Map<String, GraphRecipe<K>> recipes, PlanningBudget budget,
                                 K target, long amount, Map<K, Long> seeds, boolean preserve) {
        // Count schedules emit completed passes as identity repeats. Ordinary
        // short words and already compact repetitions need no additional pass.
        if (!(original instanceof PlanStep.Sequence sequence) || sequence.children().size() < 8 ||
                budget.remainingWork() < 131072) return original;
        int fragments = 0;
        for (int i = 0; i < Math.min(16, sequence.children().size()); i++)
            if (sequence.children().get(i) instanceof PlanStep.Repeat repeat && repeat.times() == 1) fragments++;
        if (fragments < 4) return original;
        var pass = new PlanSourceCycles<K>(recipes, budget, target, amount, seeds, preserve);
        String detail = budget.failureDetail();
        try { return pass.compile(original); }
        catch (Stop ignored) { return original; }
        catch (PlanningBudget.Exhausted limit) {
            if (limit.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw limit;
            budget.failureDetail(detail); return original;
        } finally { budget.release(pass.memory); }
    }

    private PlanStep compile(PlanStep original) {
        index(original);
        var counts = counts(original);
        if (counts.size() > 128) return original;
        var used = new ArrayList<GraphRecipe<K>>();
        var sources = new HashMap<K, BitSet>();
        var maximumInput = new HashMap<K, Long>();
        for (String id : counts.keySet()) {
            charge(); var recipe = recipes.get(id);
            if (recipe == null) throw new IllegalArgumentException("Unknown source-cycle recipe");
            int position = used.size(); used.add(recipe);
            reserve(512L + 192L * (recipe.inputs().size() + recipe.outputs().size()));
            // Reusable virtual tokens are read arcs, not physical producer
            // dependencies. Their availability remains in the exact proof.
            for (K key : recipe.executionOutputs().keySet()) {
                charge(); sources.computeIfAbsent(key, ignored -> new BitSet()).set(position);
            }
            for (var input : recipe.inputs().entrySet()) {
                charge(); maximumInput.merge(input.getKey(), input.getValue(), Math::max);
            }
        }
        int size = used.size(); var edges = new BitSet[size]; var reach = new BitSet[size];
        reserve(1024L + 256L * size);
        for (int i = 0; i < size; i++) edges[i] = new BitSet(size);
        for (int i = 0; i < size; i++) for (var input : used.get(i).inputs().entrySet()) {
            charge();
            if (input.getValue() <= used.get(i).reusableInputs().getOrDefault(input.getKey(), 0L)) continue;
            var providers = sources.get(input.getKey());
            if (providers != null) for (int j = providers.nextSetBit(0); j >= 0; j = providers.nextSetBit(j + 1)) {
                charge(); edges[j].set(i);
            }
        }
        for (int i = 0; i < size; i++) { reach[i] = (BitSet) edges[i].clone(); reach[i].set(i); }
        for (int k = 0; k < size; k++) for (int i = 0; i < size; i++) {
            charge(); if (reach[i].get(k)) reach[i].or(reach[k]);
        }
        SequenceSummary<K> before = null;
        var seen = new BitSet(size);
        for (int first = 0; first < size; first++) {
            charge(); if (seen.get(first)) continue;
            var component = new BitSet(size);
            for (int i = 0; i < size; i++) if (reach[first].get(i) && reach[i].get(first)) component.set(i);
            seen.or(component);
            if (component.cardinality() > 8 || component.cardinality() == 1 && !edges[first].get(first)) continue;
            boolean source = true;
            for (int i = 0; i < size; i++) {
                charge();
                if (!component.get(i) && edges[i].intersects(component)) { source = false; break; }
            }
            if (!source) continue;
            var cycle = new ArrayList<GraphRecipe<K>>(); var selected = new HashSet<String>();
            for (int i = component.nextSetBit(0); i >= 0; i = component.nextSetBit(i + 1)) {
                var recipe = used.get(i);
                if (recipe.batchSensitiveInputs()) { source = false; break; }
                cycle.add(recipe); selected.add(recipe.id());
            }
            if (!source) continue;
            if (before == null) before = summary(original);
            PlanStep prefix = prefix(cycle, counts, before.required());
            if (prefix == null) continue;
            PlanStep suffix = without(selected);
            PlanStep candidate = new PlanStep.Sequence(List.of(prefix, suffix));
            var after = summary(candidate);
            if (contract(before, after, sources, maximumInput) && counts(candidate).equals(counts)) {
                budget.note("source_cycle_program", "verified; recipes=" + cycle.size() +
                        "; work=" + (budget.threadWork() - started));
                // Once a source cycle has been separated, its consumers can
                // still be split across many old passes. Only an acyclic
                // remainder is eligible; reuse the already bounded graph.
                String detail = budget.failureDetail();
                try {
                    PlanStep compact = compactDag(prefix, used, edges, component, counts, sources);
                    if (compact != null && contract(before, summary(compact), sources, maximumInput)
                            && counts(compact).equals(counts)) {
                        budget.note("source_cycle_dag_suffix", "verified; recipes=" + (size - component.cardinality()) +
                                "; work=" + (budget.threadWork() - started));
                        return compact;
                    }
                } catch (Stop ignored) { /* Keep the already verified source cycle. */ }
                catch (PlanningBudget.Exhausted limit) {
                    if (limit.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw limit;
                    budget.failureDetail(detail);
                }
                return candidate;
            }
        }
        return original;
    }

    private boolean contract(SequenceSummary<K> before, SequenceSummary<K> after,
                             Map<K, BitSet> sources, Map<K, Long> maximumInput) {
        var keys = before.keys(); keys.addAll(after.keys());
        for (K key : keys) {
            charge();
            BigInteger goal = BigInteger.valueOf(seeds.getOrDefault(key, 0L));
            if (preserve && sources.containsKey(key) && before.required(key).signum() > 0) {
                // Later assembly may infer a startup reserve, including
                // a net-consumed cyclic input. Conservatively leave room
                // for either the old prefix loan or a one-firing loan.
                goal = goal.max(before.required(key)).max(BigInteger.valueOf(maximumInput.getOrDefault(key, 0L)));
            }
            if (Objects.equals(key, target)) goal = goal.add(BigInteger.valueOf(amount));
            BigInteger initial = before.required(key).max(goal.subtract(before.delta(key)));
            if (after.required(key).compareTo(before.required(key)) > 0 ||
                    !after.delta(key).equals(before.delta(key)) ||
                    initial.add(after.peak(key)).compareTo(ExactAmounts.LONG_MAX) > 0) return false;
        }
        return true;
    }

    private PlanStep compactDag(PlanStep prefix, List<GraphRecipe<K>> used, BitSet[] edges,
                                BitSet removed, Map<String, BigInteger> counts, Map<K, BitSet> sources) {
        int size = used.size(), expected = size - removed.cardinality();
        if (expected < 2) return null;
        reserve(512L + 128L * size);
        int[] indegree = new int[size];
        for (int i = 0; i < size; i++) {
            charge(); if (removed.get(i)) continue;
            var recipe = used.get(i);
            // Consumed configurations can save one charge when batches merge.
            // A source-only configuration cannot gain inventory; an internally
            // produced configuration could, so retain its original boundaries.
            for (var config : recipe.configurationInputs().entrySet()) {
                charge();
                if (config.getValue() > recipe.reusableInputs().getOrDefault(config.getKey(), 0L)
                        && sources.containsKey(config.getKey())) return null;
            }
            for (int next = edges[i].nextSetBit(0); next >= 0; next = edges[i].nextSetBit(next + 1)) {
                charge(); if (!removed.get(next)) indegree[next]++;
            }
        }
        var ready = new ArrayDeque<Integer>();
        for (int i = 0; i < size; i++) { charge(); if (!removed.get(i) && indegree[i] == 0) ready.addLast(i); }
        var suffix = new ArrayList<PlanStep>(expected);
        while (!ready.isEmpty()) {
            charge(); int index = ready.removeFirst(); var recipe = used.get(index);
            suffix.add(PlanStep.batch(recipe.id(), counts.get(recipe.id())));
            for (int next = edges[index].nextSetBit(0); next >= 0; next = edges[index].nextSetBit(next + 1)) {
                charge(); if (!removed.get(next) && --indegree[next] == 0) ready.addLast(next);
            }
        }
        if (suffix.size() != expected) return null;
        return new PlanStep.Sequence(List.of(prefix, new PlanStep.Sequence(suffix)));
    }

    private PlanStep prefix(List<GraphRecipe<K>> cycle, Map<String, BigInteger> totals, Map<K, BigInteger> initial) {
        var held = new LinkedHashMap<>(initial);
        var body = new ArrayList<PlanStep>();
        var pending = new ArrayList<>(cycle);
        BigInteger repeats = null;
        for (var recipe : cycle) {
            charge(); var count = totals.get(recipe.id());
            repeats = repeats == null ? count : repeats.min(count);
        }
        if (repeats == null || repeats.compareTo(BigInteger.TWO) < 0 || repeats.bitLength() > 63) return null;
        while (!pending.isEmpty()) {
            boolean progressed = false;
            for (var iterator = pending.iterator(); iterator.hasNext();) {
                var recipe = iterator.next(); charge();
                if (!funded(recipe, held)) continue;
                apply(recipe, BigInteger.ONE, held); body.add(new PlanStep.Batch(recipe.id(), 1));
                iterator.remove(); progressed = true;
            }
            if (!progressed) return null;
        }
        PlanStep turn = new PlanStep.Sequence(body);
        var turnSummary = summary(turn);
        var internal = new HashSet<K>();
        for (var recipe : cycle) internal.addAll(recipe.executionOutputs().keySet());
        boolean growing = false;
        for (K key : internal) {
            charge(); if (turnSummary.delta(key).signum() < 0) return null;
            growing |= turnSummary.delta(key).signum() > 0;
        }
        if (!growing) return null;
        var loop = new PlanStep.Repeat(turn, repeats.longValueExact());
        var loopSummary = summary(loop);
        held = new LinkedHashMap<>(initial);
        for (K key : loopSummary.keys()) {
            charge();
            if (loopSummary.required(key).compareTo(held.getOrDefault(key, BigInteger.ZERO)) > 0) return null;
            held.merge(key, loopSummary.delta(key), BigInteger::add);
        }
        var prefix = new ArrayList<PlanStep>(); prefix.add(loop);
        var remaining = new LinkedHashMap<GraphRecipe<K>, BigInteger>();
        for (var recipe : cycle) remaining.put(recipe, totals.get(recipe.id()).subtract(repeats));
        for (int pass = 0; pass < 16; pass++) {
            boolean progressed = false, complete = true;
            for (var recipe : cycle) {
                charge(); var count = remaining.get(recipe);
                if (count.signum() == 0) continue;
                complete = false;
                if (!funded(recipe, held)) continue;
                for (var input : recipe.inputs().entrySet()) {
                    charge();
                    BigInteger drain = BigInteger.valueOf(input.getValue()).subtract(BigInteger.valueOf(recipe.outputs().getOrDefault(input.getKey(), 0L)));
                    if (drain.signum() > 0) count = count.min(held.getOrDefault(input.getKey(), BigInteger.ZERO)
                            .subtract(BigInteger.valueOf(input.getValue())).divide(drain).add(BigInteger.ONE));
                }
                if (count.signum() <= 0) continue;
                prefix.add(PlanStep.batch(recipe.id(), count)); apply(recipe, count, held);
                remaining.put(recipe, remaining.get(recipe).subtract(count)); progressed = true;
            }
            if (complete || remaining.values().stream().allMatch(v -> v.signum() == 0)) return new PlanStep.Sequence(prefix);
            if (!progressed) return null;
        }
        return null;
    }

    private boolean funded(GraphRecipe<K> recipe, Map<K, BigInteger> held) {
        for (var input : recipe.inputs().entrySet()) {
            charge(); if (held.getOrDefault(input.getKey(), BigInteger.ZERO).compareTo(BigInteger.valueOf(input.getValue())) < 0) return false;
        }
        return true;
    }
    private void apply(GraphRecipe<K> recipe, BigInteger count, Map<K, BigInteger> held) {
        recipe.inputs().forEach((key, amount) -> { charge(); held.merge(key, count.multiply(BigInteger.valueOf(amount)).negate(), BigInteger::add); });
        recipe.outputs().forEach((key, amount) -> { charge(); held.merge(key, count.multiply(BigInteger.valueOf(amount)), BigInteger::add); });
    }

    /** Index the immutable DAG once; preserve multiplicities through its wrappers. */
    private void index(PlanStep root) {
        var stack = new ArrayDeque<Frame>(); var seen = Collections.newSetFromMap(new IdentityHashMap<PlanStep, Boolean>());
        var sizes = new IdentityHashMap<PlanStep, BigInteger>();
        BigInteger maximum = ExactAmounts.LONG_MAX.multiply(BigInteger.valueOf(32));
        seen.add(root); stack.push(new Frame(root)); reserve(256);
        while (!stack.isEmpty()) {
            charge(); var frame = stack.peek(); var child = frame.next();
            if (child == null) {
                BigInteger size;
                if (frame.step instanceof PlanStep.Batch batch) size = BigInteger.valueOf(batch.runs());
                else if (frame.step instanceof PlanStep.Repeat repeat) size = sizes.get(repeat.body()).multiply(BigInteger.valueOf(repeat.times()));
                else {
                    size = BigInteger.ZERO;
                    for (PlanStep member : ((PlanStep.Sequence) frame.step).children()) {
                        charge(); size = size.add(sizes.get(member));
                        if (size.compareTo(maximum) > 0) throw new Stop();
                    }
                }
                // This optional compiler never needs unbounded nested count
                // arithmetic. The original large word remains executable.
                if (size.compareTo(maximum) > 0) throw new Stop();
                sizes.put(frame.step, size); order.add(frame.step); stack.pop();
            }
            else if (seen.add(child)) {
                if (seen.size() > 4096) throw new Stop();
                reserve(256); stack.push(new Frame(child));
            }
        }
    }
    private PlanStep without(Set<String> selected) {
        rewritten.clear();
        for (PlanStep step : order) {
            charge(); PlanStep result = step;
            if (step instanceof PlanStep.Batch batch) {
                if (selected.contains(batch.recipe())) result = new PlanStep.Sequence(List.of());
            } else if (step instanceof PlanStep.Repeat repeat) {
                PlanStep body = rewritten.get(repeat.body());
                result = empty(body) || repeat.times() == 0 ? new PlanStep.Sequence(List.of()) :
                        repeat.times() == 1 ? body : new PlanStep.Repeat(body, repeat.times());
            } else {
                var children = new ArrayList<PlanStep>();
                for (PlanStep child : ((PlanStep.Sequence) step).children()) {
                    charge(); var next = rewritten.get(child); if (!empty(next)) children.add(next);
                }
                reserve(64L + 16L * children.size());
                result = children.size() == 1 ? children.get(0) : new PlanStep.Sequence(children);
            }
            rewritten.put(step, result);
        }
        return rewritten.get(order.get(order.size() - 1));
    }
    private static boolean empty(PlanStep step) { return step instanceof PlanStep.Sequence sequence && sequence.children().isEmpty(); }
    private Map<String, BigInteger> counts(PlanStep word) {
        var computation = new PlanCountComputation(word);
        while (!computation.step(budget)) charge();
        return computation.result();
    }
    private SequenceSummary<K> summary(PlanStep word) {
        try (var computation = new SummaryComputation<>(word, recipes, budget)) {
            while (!computation.step()) charge();
            return computation.result();
        }
    }
    private void charge() { budget.check(); if (budget.threadWork() - started >= allowance) throw new Stop(); }
    private void reserve(long bytes) { if (!budget.tryReserve(bytes)) throw new Stop(); memory += bytes; }
    private static final class Frame {
        final PlanStep step; int child;
        Frame(PlanStep step) { this.step = step; }
        PlanStep next() {
            if (step instanceof PlanStep.Repeat repeat) return child++ == 0 ? repeat.body() : null;
            if (step instanceof PlanStep.Sequence sequence && child < sequence.children().size()) return sequence.children().get(child++);
            return null;
        }
    }
}
