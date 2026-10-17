// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;

/**
 * Positive-only startup proposals without one feedback coproduct's supply credit.
 * Removing that credit can expose an ordinary upstream producer that a selected
 * SCC otherwise tries to fund from its own returns. No projected failure or
 * proof is shared: accepted programs are rebuilt with all original outputs.
 */
final class GraphOutputView<K> implements AutoCloseable {
    private record Omission<K>(String recipe, K output, String targetSource) {}

    private final GraphCompiler<K> compiler;
    private final Map<K, GraphRecipe<K>> selected;
    private final K target;
    private final long amount, started;
    private final Map<K, Long> stock, seeds;
    private final Set<K> external;
    private final Set<String> excluded;
    private final boolean preserve, force;
    private final CatalystPolicy policy;
    private final PlanningBudget budget;
    private final List<Omission<K>> omissions = new ArrayList<>();
    private final Map<String, GraphRecipe<K>> originals = new LinkedHashMap<>();
    private final long allowance;
    private long work, memory, graphMemory;
    private int next;
    private GraphCompilation<K> compiling;
    private GraphSolve<K> solving;
    private AllocationSearch.Candidate<K> restoring;
    private GraphPlan<K> result;
    private boolean complete;

    static <K> boolean eligible(GraphCompiler.Compiled<K> graph, PlanningBudget budget) {
        for (var region : graph.regions()) {
            budget.check();
            if (!region.cyclic()) continue;
            for (var recipe : region.recipes()) {
                budget.check();
                if (recipe.executionOutputs().size() > 1) return true;
            }
        }
        return false;
    }

    GraphOutputView(GraphCompiler<K> compiler, GraphCompiler.Compiled<K> graph, K target, long amount,
                    Map<K, Long> stock, Map<K, Long> seeds, Set<K> external, Set<String> excluded,
                    boolean preserve, boolean force, CatalystPolicy policy, PlanningBudget budget, long started) {
        this.compiler = compiler;
        selected = graph.selected();
        this.target = target;
        this.amount = amount;
        this.stock = stock;
        this.seeds = seeds;
        this.external = external;
        this.excluded = excluded;
        this.preserve = preserve;
        this.force = force;
        this.policy = policy;
        this.budget = budget;
        this.started = started;
        allowance = Math.min(262144, budget.remainingWork() / 16);
        if (allowance < 8192 || compiler.catalog().size() > 8192) { complete = true; return; }
        long before = budget.threadSearchWork();
        try {
            if (!eligible(graph, budget)) { complete = true; return; }
            long bytes = 4096L + 192L * compiler.catalog().size();
            for (var recipe : compiler.catalog()) {
                budget.check();
                // Recipe objects are borrowed. Account the copied indexes,
                // replacement slots/maps, and temporary dependency sets.
                bytes += 128L * (recipe.slots().size() + recipe.outputs().size());
            }
            if (budget.threadSearchWork() - before >= allowance || !budget.tryReserve(bytes)) {
                complete = true;
                return;
            }
            memory = bytes;
            // Target feedback first. Omitting a coproduct never removes its
            // real producer from the canonical compiler or changes its order.
            for (int pass = 0; pass < 2 && omissions.size() < 8; pass++) for (var region : graph.regions()) {
                budget.check();
                if (!region.cyclic() || omissions.size() == 8) continue;
                Set<K> consumed = new HashSet<>();
                for (var recipe : region.recipes()) {
                    for (K input : recipe.inputs().keySet()) {
                        budget.check();
                        consumed.add(input);
                    }
                }
                for (var recipe : region.recipes()) {
                    budget.check();
                    if (recipe.executionOutputs().size() < 2 || recipe.outputs().containsKey(target) != (pass == 0)) continue;
                    for (K output : recipe.outputs().keySet()) {
                        budget.check();
                        if (!output.equals(target) && !recipe.reusableInputs().containsKey(output) &&
                                consumed.contains(output) && omissions.size() < 8)
                            omissions.add(new Omission<>(recipe.id(), output, null));
                    }
                }
            }
            complete = omissions.isEmpty();
            if (complete) close();
            else {
                // A return-only target source can be balanced yet have no
                // positive gain. Keep the selected choices first, then allow
                // another target producer in the same bounded proposal queue.
                var initial = List.copyOf(omissions);
                var sources = compiler.producers(target);
                // A bounded positive scout must not spend all its slots on
                // unavailable distractors merely because they were listed
                // first. Reachability only orders these trials; the canonical
                // source frontier and every producer remain unchanged.
                boolean crowded = sources.size() > 1 + (8 - initial.size()) / initial.size();
                try (var ranking = crowded ? GraphSourceRanking.create(compiler, stock, external, target, force,
                        budget, Math.min(32768, allowance / 4)) : null) {
                    for (var producer : ranking == null ? sources : ranking.sources(target, false)) {
                        budget.check();
                        if (excluded.contains(producer.id()) || producer.equals(selected.get(target))) continue;
                        for (var omission : initial) {
                            if (omissions.size() == 8) break;
                            omissions.add(new Omission<>(omission.recipe(), omission.output(), producer.id()));
                        }
                        if (omissions.size() == 8) break;
                    }
                }
                for (var recipe : compiler.catalog()) {
                    budget.check();
                    originals.put(recipe.id(), recipe);
                }
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        } finally { work += budget.threadSearchWork() - before; }
    }

    boolean step() {
        if (complete) return true;
        long before = budget.threadSearchWork();
        String failureBefore = budget.failureDetail();
        try {
            budget.checkpoint();
            if (work >= allowance) { complete = true; return true; }
            if (restoring != null) {
                if (!restoring.step()) return false;
                result = restoring.plan;
                if (result != null) {
                    budget.note("output_view", "original_outputs_restored; witness; trials=" + next + "; work=" + work);
                    complete = true;
                    return true;
                }
                restoring.close();
                restoring = null;
            }
            if (solving != null) {
                if (!solving.step()) return false;
                var plan = solving.result();
                solving.close();
                solving = null;
                if (plan.feasible()) {
                    // Discard the projected inventory/seed summaries. Rebuild
                    // and verify every prefix against the original recipes,
                    // including the omitted outputs and physical amount limits.
                    restoring = new AllocationSearch.Candidate<>(plan.steps(), originals, target, amount, stock,
                            seeds, external, preserve, force, false, budget, started);
                    return false;
                }
            }
            if (compiling != null) {
                if (!compiling.step()) return false;
                var graph = compiling.result();
                long bytes = graph.estimatedBytes();
                budget.reserve(bytes);
                graphMemory = bytes;
                compiling.close();
                compiling = null;
                solving = new GraphSolve<>(graph, target, amount, stock, external, seeds, preserve, force,
                        budget, started, policy, stock).scoutRegions();
                return false;
            }
            budget.release(graphMemory);
            graphMemory = 0;
            if (next == omissions.size()) { complete = true; return true; }
            var omission = omissions.get(next++);
            var source = originals.get(omission.recipe());
            budget.charge(source.outputs().size() + (long) source.slots().size());
            Map<K, Long> outputs = new LinkedHashMap<>(source.outputs());
            outputs.remove(omission.output());
            var replacement = new GraphRecipe<>(source.id(), source.binding(), source.slots(), outputs);
            var recipes = new ArrayList<GraphRecipe<K>>();
            Map<K, List<GraphRecipe<K>>> order = new LinkedHashMap<>();
            for (var recipe : compiler.catalog()) {
                budget.check();
                recipes.add(recipe == source ? replacement : recipe);
                for (K key : recipe.executionOutputs().keySet()) {
                    budget.check();
                    if (order.containsKey(key)) continue;
                    var producers = new ArrayList<GraphRecipe<K>>();
                    for (var producer : compiler.producers(key)) {
                        budget.check();
                        if (producer != source) producers.add(producer);
                        else if (replacement.executionOutputs().containsKey(key)) producers.add(replacement);
                    }
                    order.put(key, List.copyOf(producers));
                }
            }
            Map<K, Integer> choices = new LinkedHashMap<>();
            for (var entry : order.entrySet()) {
                GraphRecipe<K> preferred = selected.get(entry.getKey());
                String wanted = entry.getKey().equals(target) && omission.targetSource() != null ?
                        omission.targetSource() : preferred == null ? null : preferred.id();
                int index = 0;
                for (var producer : entry.getValue()) {
                    budget.check();
                    if (excluded.contains(producer.id())) continue;
                    if (producer.id().equals(wanted)) { if (index > 0) choices.put(entry.getKey(), index); break; }
                    index++;
                }
            }
            compiling = new GraphCompiler<>(recipes, order).begin(target, seeds.keySet(), choices, excluded, budget);
            budget.note("output_view", "restricted_credit; trial=" + next + "; no_negative_transfer");
            return false;
        } catch (PlanningBudget.Exhausted failure) {
            if (failure.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw failure;
            budget.failureDetail(failureBefore);
            complete = true;
            return true;
        } finally { work += budget.threadSearchWork() - before; }
    }

    GraphPlan<K> result() { return result; }
    boolean compilationActive() { return compiling != null; }

    @Override public void close() {
        if (compiling != null) compiling.close();
        if (solving != null) solving.close();
        if (restoring != null) restoring.close();
        compiling = null;
        solving = null;
        restoring = null;
        budget.release(memory + graphMemory);
        memory = graphMemory = 0;
        originals.clear();
        omissions.clear();
        complete = true;
    }
}
