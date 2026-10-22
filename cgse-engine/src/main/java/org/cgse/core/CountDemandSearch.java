// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Sparse deficit propagation proposes counts; only a checked execution is returned.
 * A failed producer choice, open cycle or local limit is never an infeasibility proof. */
final class CountDemandSearch<K> implements AutoCloseable {
    private record Incidence(int row, BigInteger coefficient) {}

    private final GraphCompiler<K> compiler;
    private final K target;
    private final long amount, started, allowance;
    private final Map<K, Long> stock, seeds;
    private final Set<K> external;
    private final Set<String> excluded;
    private final boolean preserve, force;
    private final PlanningBudget budget;
    private final Map<String, GraphRecipe<K>> recipes = new LinkedHashMap<>();
    private final List<List<Incidence>> columns = new ArrayList<>();
    private final List<int[]> producers = new ArrayList<>();
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private final BitSet queued = new BitSet();
    private RecipeCountModel<K> model;
    private BigInteger[] counts, deficits;
    private CountSchedule<K> schedule;
    private AllocationSearch.Candidate<K> candidate;
    private CountDemandAllocation allocation;
    private CountScheduleTopology<K> topology;
    private boolean structuredSchedule;
    private GraphPlan<K> result;
    private long work, attemptStarted, memory;
    private int attempt, phase;
    private boolean done;

    CountDemandSearch(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
            Set<K> external, Map<K, Long> seeds, Set<String> excluded, boolean preserve, boolean force,
            PlanningBudget budget, long started) {
        this.compiler = compiler; this.target = target; this.amount = amount; this.stock = stock;
        this.external = external; this.seeds = seeds; this.excluded = excluded;
        this.preserve = preserve; this.force = force; this.budget = budget; this.started = started;
        allowance = Math.min(2_000_000, budget.remainingWork() / 8);
    }

    boolean step() {
        if (done) return true;
        long before = budget.threadWork();
        String previous = budget.failureDetail();
        try {
            budget.checkpoint();
            if (work >= allowance) return finish();
            if (phase == 0) {
                if (!prepare()) return finish();
                reset(); phase = 1;
                return false;
            }
            if (candidate != null) {
                if (!candidate.step()) return false;
                if (candidate.plan != null && candidate.plan.feasible()) {
                    result = candidate.plan;
                    return finish();
                }
                return nextAttempt();
            }
            if (schedule != null) {
                if (!schedule.step()) return false;
                if (schedule.witness() == null) return nextAttempt();
                candidate = new AllocationSearch.Candidate<>(schedule.witness(), recipes, target, amount, stock,
                        seeds, external, preserve, force, false, budget, started);
                return false;
            }
            if (topology != null) {
                if (!topology.step()) return false;
                int[] components = topology.result(); topology.close(); topology = null;
                structuredSchedule = components != null;
                schedule = components == null ? new CountSchedule<>(model, counts, budget, true) :
                        CountSchedule.componentOrdered(model, counts, components, budget);
                return false;
            }
            if (allocation != null) {
                if (!allocation.step()) return false;
                counts = allocation.result();
                if (counts == null) return finish();
                scheduleCounts();
                return false;
            }
            // Give both deterministic producer orders room. Existing source
            // search already tries the preferred order, so scout its opposite first.
            if (work - attemptStarted >= allowance / 2) return nextAttempt();
            for (int visit = 0; visit < 32; visit++) {
                budget.check();
                if (pending.isEmpty()) {
                    scheduleCounts();
                    return false;
                }
                int row = pending.removeFirst(); queued.clear(row);
                if (deficits[row].signum() <= 0) continue;
                int[] choices = producers.get(row);
                if (choices.length == 0) return nextAttempt();
                int recipe = choices[attempt == 0 ? choices.length - 1 : 0];
                BigInteger gain = model.constraints.get(row).terms().get(recipe).negate();
                BigInteger runs = CheckedAmounts.ceilDiv(deficits[row], gain);
                BigInteger next = counts[recipe].add(runs);
                // This bounds optional workspace, not the supported request amount.
                if (next.bitLength() > 1024) return nextAttempt();
                counts[recipe] = next;
                for (var incidence : columns.get(recipe)) {
                    budget.charge(1 + (runs.bitLength() + incidence.coefficient.bitLength()) / 64);
                    int id = incidence.row;
                    deficits[id] = deficits[id].add(incidence.coefficient.multiply(runs));
                    if (deficits[id].bitLength() > 2048) return nextAttempt();
                    if (deficits[id].signum() > 0 && !queued.get(id)) { queued.set(id); pending.addLast(id); }
                }
            }
            return false;
        } catch (PlanningBudget.Exhausted limit) {
            if (limit.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw limit;
            close(); budget.failureDetail(previous);
            return finish();
        } finally { work += budget.threadWork() - before; }
    }

    private boolean prepare() {
        if (allowance < 32768) return false;
        model = RecipeCountModel.create(compiler, target, amount, stock, seeds, external, excluded, force, budget);
        if (model == null) return false;
        long terms = 0;
        for (var row : model.constraints) {
            budget.check(); terms += row.terms().size();
            if (row.upper().bitLength() > 1024) return false;
        }
        // Includes 1024-bit counts, 2048-bit deficits, incidence and producer indexes.
        long bytes = 4096L + 640L * terms + 512L * (model.recipes.size() + model.constraints.size());
        if (!budget.tryReserve(bytes)) return false;
        memory = bytes;
        var ids = new HashMap<String, Integer>();
        for (int i = 0; i < model.recipes.size(); i++) {
            budget.check(); var recipe = model.recipes.get(i);
            recipes.put(recipe.id(), recipe); ids.put(recipe.id(), i); columns.add(new ArrayList<>());
        }
        for (int i = 0; i < model.constraints.size(); i++) {
            var row = model.constraints.get(i); var choices = new LinkedHashSet<Integer>();
            if (i < model.rowKeys.size()) for (var recipe : compiler.producers(model.rowKeys.get(i))) {
                budget.check(); Integer id = ids.get(recipe.id());
                if (id != null && row.terms().getOrDefault(id, BigInteger.ZERO).signum() < 0) choices.add(id);
            }
            for (var term : row.terms().entrySet()) {
                budget.check(); columns.get(term.getKey()).add(new Incidence(i, term.getValue()));
                // Also handles gross-production rows with no material key.
                if (term.getValue().signum() < 0) choices.add(term.getKey());
            }
            producers.add(choices.stream().mapToInt(Integer::intValue).toArray());
        }
        counts = new BigInteger[model.recipes.size()]; deficits = new BigInteger[model.constraints.size()];
        return true;
    }

    private void reset() {
        pending.clear(); queued.clear(); Arrays.fill(counts, BigInteger.ZERO);
        for (int i = 0; i < deficits.length; i++) {
            budget.check(); deficits[i] = model.constraints.get(i).upper().negate();
            if (deficits[i].signum() > 0) { queued.set(i); pending.addLast(i); }
        }
        attemptStarted = work;
    }

    private boolean nextAttempt() {
        if (candidate != null) candidate.close(); candidate = null;
        if (schedule != null) schedule.close(); schedule = null;
        if (structuredSchedule) {
            structuredSchedule = false;
            schedule = new CountSchedule<>(model, counts, budget, true);
            return false;
        }
        if (allocation != null) return allocation.reject() ? false : finish();
        if (++attempt == 2) {
            if (allowance - work < 32768) return finish();
            allocation = new CountDemandAllocation(model.recipes.size(), model.constraints, producers, budget);
            return false;
        }
        reset(); return false;
    }

    private void scheduleCounts() {
        // Independently re-evaluate every original row for every strategy.
        for (var row : model.constraints) {
            BigInteger total = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check(); total = total.add(counts[term.getKey()].multiply(term.getValue()));
            }
            if (total.compareTo(row.upper()) > 0) throw new IllegalStateException("Invalid deficit closure");
        }
        if (allocation == null) schedule = new CountSchedule<>(model, counts, budget, true);
        else topology = new CountScheduleTopology<>(model, counts, budget);
    }

    private boolean finish() {
        done = true;
        budget.note("count_demand", "witness=" + (result != null) + "; attempts=" + (attempt + 1) + "; work=" + work);
        return true;
    }

    GraphPlan<K> result() { return result; }

    @Override public void close() {
        if (candidate != null) candidate.close(); candidate = null;
        if (schedule != null) schedule.close(); schedule = null;
        if (allocation != null) allocation.close(); allocation = null;
        if (topology != null) topology.close(); topology = null;
        if (model != null) model.close(); model = null;
        budget.release(memory); memory = 0;
    }
}
