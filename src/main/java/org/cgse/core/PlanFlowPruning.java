// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Deletes redundant turnover only after proving prefix and net-resource dominance. */
final class PlanFlowPruning<K> {

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private static final class Frame {

        final PlanStep step;
        int next;

        Frame(PlanStep step) {
            this.step = step;
        }

        PlanStep child() {
            if (step instanceof PlanStep.Repeat repeat) return next++ == 0 ? repeat.body() : null;
            if (step instanceof PlanStep.Sequence sequence && next < sequence.children().size()) return sequence.children().get(next++);
            return null;
        }
    }

    private final Map<String, GraphRecipe<K>> recipes;
    private final PlanningBudget budget;
    private final long started, allowance;
    private long memory;
    private int pruned;

    private PlanFlowPruning(Map<String, GraphRecipe<K>> recipes, PlanningBudget budget) {
        this.recipes = recipes;
        this.budget = budget;
        // The rewrite is synchronous, including summaries, on one count worker.
        started = budget.threadWork();
        allowance = Math.min(32_768, budget.remainingWork() / 32);
    }

    static <K> PlanStep optimize(PlanStep original, Map<String, GraphRecipe<K>> recipes, PlanningBudget budget) {
        if (original instanceof PlanStep.Batch || budget.remainingWork() < 16_384) return original;
        var pruning = new PlanFlowPruning<>(recipes, budget);
        try {
            return pruning.rewrite(original);
        } catch (Stop limit) {
            return original;
        } finally {
            budget.release(pruning.memory);
        }
    }

    private PlanStep rewrite(PlanStep original) {
        var memo = new IdentityHashMap<PlanStep, PlanStep>();
        var stack = new ArrayDeque<Frame>();
        reserve(128);
        stack.push(new Frame(original));
        while (!stack.isEmpty()) {
            charge();
            Frame frame = stack.peek();
            PlanStep child = frame.child();
            if (child != null) {
                if (!memo.containsKey(child)) {
                    reserve(128);
                    stack.push(new Frame(child));
                }
                continue;
            }
            PlanStep result = frame.step;
            if (result instanceof PlanStep.Repeat repeat) {
                var body = memo.get(repeat.body());
                if (body != repeat.body()) result = new PlanStep.Repeat(body, repeat.times());
            } else if (result instanceof PlanStep.Sequence sequence) {
                reserve(64L + 16L * sequence.children().size());
                var children = new ArrayList<PlanStep>();
                boolean changed = false;
                for (int i = 0; i < sequence.children().size();) {
                    charge();
                    var next = memo.get(sequence.children().get(i));
                    changed |= next != sequence.children().get(i);
                    if (!(next instanceof PlanStep.Batch)) {
                        children.add(next);
                        i++;
                        continue;
                    }
                    int end = i + 1;
                    while (end < sequence.children().size() && memo.get(sequence.children().get(end)) instanceof PlanStep.Batch) {
                        charge();
                        end++;
                    }
                    var run = new ArrayList<PlanStep>();
                    for (int j = i; j < end; j++) run.add(memo.get(sequence.children().get(j)));
                    var replacement = prune(run);
                    changed |= replacement != run;
                    children.addAll(replacement);
                    i = end;
                }
                if (changed) result = new PlanStep.Sequence(children);
            }
            memo.put(frame.step, result);
            stack.pop();
        }
        if (pruned > 0) budget.note("plan_flow_pruning", "proven_reductions=" + pruned + "; work=" + (budget.threadWork() - started));
        return memo.get(original);
    }

    private List<PlanStep> prune(List<PlanStep> run) {
        if (run.size() < 2 || run.size() > 256) return run;
        int entries = 0;
        for (var step : run) {
            var recipe = recipes.get(((PlanStep.Batch) step).recipe());
            // Some configurations are consumed once per dispatch, not per run.
            // Their batch boundaries are part of the material contract.
            if (recipe.batchSensitiveInputs()) return run;
            entries += recipe.inputs().size() + recipe.outputs().size();
            if (entries > 4096) return run;
        }
        long bytes = 512L + entries * 384L;
        reserve(bytes);
        try {
            // Ordinary chains cannot contain a turnover. Avoid constructing any
            // exact summaries for them, regardless of order quantity.
            Set<K> consumed = new HashSet<>();
            boolean feedback = false;
            for (var step : run) {
                var recipe = recipes.get(((PlanStep.Batch) step).recipe());
                for (K key : recipe.inputs().keySet()) {
                    charge();
                    consumed.add(key);
                }
                for (K key : recipe.outputs().keySet()) {
                    charge();
                    feedback |= consumed.contains(key);
                }
            }
            if (!feedback) return run;
            var original = summarize(new PlanStep.Sequence(run));
            Map<K, BigInteger> demand = new HashMap<>();
            original.delta().forEach((key, value) -> demand.put(key, value.max(BigInteger.ZERO)));
            var kept = new ArrayList<PlanStep>();
            boolean changed = false;
            for (int i = run.size() - 1; i >= 0; i--) {
                charge();
                var batch = (PlanStep.Batch) run.get(i);
                var recipe = recipes.get(batch.recipe());
                BigInteger needed = BigInteger.ZERO;
                for (var output : recipe.outputs().entrySet()) {
                    charge();
                    BigInteger gain = BigInteger.valueOf(output.getValue()).subtract(BigInteger.valueOf(recipe.inputs().getOrDefault(output.getKey(), 0L)));
                    if (gain.signum() > 0) needed = needed.max(CheckedAmounts.ceilDiv(demand.getOrDefault(output.getKey(), BigInteger.ZERO), gain));
                }
                long runs = needed.min(BigInteger.valueOf(batch.runs())).longValueExact();
                changed |= runs != batch.runs();
                if (runs == 0) continue;
                kept.add(runs == batch.runs() ? batch : new PlanStep.Batch(batch.recipe(), runs));
                BigInteger count = BigInteger.valueOf(runs);
                // Add consumed quantities before subtracting outputs, including
                // returned/configuration inputs. They cancel in the net balance.
                recipe.inputs().forEach((key, value) -> {
                    charge();
                    demand.merge(key, count.multiply(BigInteger.valueOf(value)), BigInteger::add);
                });
                recipe.outputs().forEach((key, value) -> {
                    charge();
                    demand.put(key, demand.getOrDefault(key, BigInteger.ZERO).subtract(count.multiply(BigInteger.valueOf(value))).max(BigInteger.ZERO));
                });
            }
            if (!changed) return run;
            Collections.reverse(kept);
            var replacement = summarize(new PlanStep.Sequence(kept));
            // The proposal above is only a heuristic. This exact certificate is
            // required before substituting it in any sequence or repeated body:
            // no resource needs more startup stock or has a worse final balance.
            for (K key : original.keys()) {
                charge();
                if (replacement.required(key).compareTo(original.required(key)) > 0 ||
                        replacement.delta(key).compareTo(original.delta(key)) < 0)
                    return run;
            }
            pruned++;
            return kept;
        } finally {
            memory -= bytes;
            budget.release(bytes);
        }
    }

    private SequenceSummary<K> summarize(PlanStep program) {
        try (var computation = new SummaryComputation<>(program, recipes, budget)) {
            while (!computation.step()) charge();
            return computation.result();
        }
    }

    private void charge() {
        budget.check();
        if (budget.threadWork() - started >= allowance) throw new Stop();
    }

    private void reserve(long bytes) {
        if (!budget.tryReserve(bytes)) throw new Stop();
        memory += bytes;
    }
}
