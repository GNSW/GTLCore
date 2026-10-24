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
    private final long amount, started;
    private long allowance;
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
    private CountDemandProjection projection;
    private CountReverseSchedule<K> reverse;
    private CountScheduleTopology<K> topology;
    private boolean structuredSchedule;
    private boolean projectedCounts, reverseTried, structuralOnly;
    private GraphPlan<K> result;
    private long work, attemptStarted, memory;
    private int attempt, phase;
    private boolean done;
    private Map<K, Long> startupSeeds;
    private final Map<K, BigInteger> oldStartupGoals = new LinkedHashMap<>();
    private final Map<Integer, ExactLinearProgram.Constraint> oldStartupRows = new LinkedHashMap<>();
    private long startupMemory;
    private CountDemandAllocation startupAllocation;
    private BigInteger[] startupCounts;
    private boolean startupStructured, startupProjected, startupReverse;

    CountDemandSearch(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
            Set<K> external, Map<K, Long> seeds, Set<String> excluded, boolean preserve, boolean force,
            PlanningBudget budget, long started) {
        this.compiler = compiler; this.target = target; this.amount = amount; this.stock = stock;
        this.external = external; this.seeds = seeds; this.excluded = excluded;
        this.preserve = preserve; this.force = force; this.budget = budget; this.started = started;
        allowance = Math.min(2_000_000, budget.remainingWork() / 8);
    }

    /** A separately bounded terminal-boundary proposal, without repeating the
     * ordinary source portfolio. The caller retains its verified incumbent. */
    CountDemandSearch<K> structuralOnly(long maximumWork) {
        if (phase != 0) throw new IllegalStateException("Search already started");
        structuralOnly = true;
        allowance = Math.max(0, Math.min(maximumWork, budget.remainingWork()));
        return this;
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
                if (structuralOnly) { phase = 1; return beginProjection(); }
                reset(); phase = 1;
                return false;
            }
            if (candidate != null) {
                if (!candidate.step()) return false;
                if (candidate.plan != null && candidate.plan.feasible()) {
                    result = candidate.plan;
                    return finish();
                }
                if (completeStartup()) return false;
                return nextAttempt();
            }
            if (projection != null) {
                if (!projection.step()) return false;
                counts = projection.result(); projection.close(); projection = null;
                if (counts == null) return structuralOnly ? finish() : beginAllocation();
                projectedCounts = true;
                scheduleCounts();
                return false;
            }
            if (reverse != null) {
                if (!reverse.step()) return false;
                PlanStep word = reverse.witness(); reverse.close(); reverse = null;
                if (word == null) return nextAttempt();
                candidate = new AllocationSearch.Candidate<>(word, recipes, target, amount, stock,
                        activeSeeds(), external, preserve, force, false, budget, started);
                return false;
            }
            if (schedule != null) {
                if (!schedule.step()) return false;
                if (schedule.witness() == null) return nextAttempt();
                candidate = new AllocationSearch.Candidate<>(schedule.witness(), recipes, target, amount, stock,
                        activeSeeds(), external, preserve, force, false, budget, started);
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
            // Reserve half the shared scout quota for reversible allocation.
            // Two stalled feedback closures must not consume that strategy's
            // entire turn. Completed counts still reach physical verification.
            // Existing source search tries the preferred order, so scout its
            // opposite first; unused propagation quota remains available later.
            if (work - attemptStarted >= allowance / 4) return nextAttempt();
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
        long bytes = 4096L + (structuralOnly ? 0 : 640L * terms) + 512L * (model.recipes.size() + model.constraints.size());
        if (!budget.tryReserve(bytes)) return false;
        memory = bytes;
        var ids = new HashMap<String, Integer>();
        for (int i = 0; i < model.recipes.size(); i++) {
            budget.check(); var recipe = model.recipes.get(i);
            recipes.put(recipe.id(), recipe);
            if (!structuralOnly) { ids.put(recipe.id(), i); columns.add(new ArrayList<>()); }
        }
        if (structuralOnly) return true;
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
        restoreStartup();
        if (projectedCounts && !reverseTried) {
            reverseTried = true;
            reverse = new CountReverseSchedule<>(model, counts, budget, Math.min(1_048_576, allowance - work));
            return false;
        }
        if (structuredSchedule) {
            structuredSchedule = false;
            schedule = new CountSchedule<>(model, counts, budget, true);
            return false;
        }
        if (projectedCounts) {
            projectedCounts = false;
            return structuralOnly ? finish() : beginAllocation();
        }
        if (allocation != null) return allocation.reject() ? false : finish();
        if (++attempt == 2) {
            return beginAllocation();
        }
        reset(); return false;
    }

    private Map<K, Long> activeSeeds() { return startupSeeds == null ? seeds : startupSeeds; }

    /** A discovered startup loan belongs to this word, not to all source
     * choices. Tighten only this local proposal and continue its exact count
     * propagation; a failed repair restores the original model and portfolio. */
    private boolean completeStartup() {
        if (structuralOnly || startupSeeds != null || candidate.startupLoans.isEmpty() ||
                allowance - work < 32768) return false;
        long bytes = 256L + 16L * counts.length + 192L * (seeds.size() + candidate.startupLoans.size());
        if (!budget.tryReserve(bytes)) return false;
        startupMemory = bytes;
        startupSeeds = new LinkedHashMap<>(seeds);
        for (var loan : candidate.startupLoans.entrySet()) {
            budget.check();
            int row = -1;
            for (int i = 0; i < model.rowKeys.size(); i++) {
                budget.check(); if (model.rowKeys.get(i).equals(loan.getKey())) { row = i; break; }
            }
            if (row < 0) { restoreStartup(); return false; }
            long value = Math.max(startupSeeds.getOrDefault(loan.getKey(), 0L), loan.getValue());
            BigInteger goal = BigInteger.valueOf(value);
            if (target.equals(loan.getKey())) goal = goal.add(BigInteger.valueOf(amount));
            BigInteger previous = model.goal(loan.getKey());
            if (goal.compareTo(previous) <= 0) continue;
            startupSeeds.put(loan.getKey(), value);
            oldStartupGoals.put(loan.getKey(), model.goals.get(loan.getKey()));
            model.goals.put(loan.getKey(), goal);
            var original = model.constraints.get(row);
            oldStartupRows.put(row, original);
            model.constraints.set(row, new ExactLinearProgram.Constraint(original.terms(),
                    original.upper().subtract(goal.subtract(previous))));
        }
        if (oldStartupRows.isEmpty()) { restoreStartup(); return false; }
        candidate.close(); candidate = null;
        if (schedule != null) { schedule.close(); schedule = null; }
        startupAllocation = allocation; allocation = null;
        startupCounts = counts; counts = counts.clone();
        startupStructured = structuredSchedule; startupProjected = projectedCounts; startupReverse = reverseTried;
        structuredSchedule = projectedCounts = reverseTried = false;
        pending.clear(); queued.clear();
        for (int i = 0; i < model.constraints.size(); i++) {
            var row = model.constraints.get(i);
            BigInteger deficit = row.upper().negate();
            for (var term : row.terms().entrySet()) {
                budget.check(); deficit = deficit.add(term.getValue().multiply(counts[term.getKey()]));
            }
            deficits[i] = deficit;
            if (deficit.signum() > 0) { pending.addLast(i); queued.set(i); }
        }
        attemptStarted = work;
        budget.note("count_startup", "complete_existing_counts; loans=" + oldStartupRows.size());
        return true;
    }

    private void restoreStartup() {
        if (startupSeeds == null) return;
        oldStartupRows.forEach((row, constraint) -> model.constraints.set(row, constraint));
        oldStartupGoals.forEach((key, goal) -> {
            if (goal == null) model.goals.remove(key); else model.goals.put(key, goal);
        });
        if (startupCounts != null) {
            counts = startupCounts; startupCounts = null;
            allocation = startupAllocation; startupAllocation = null;
            structuredSchedule = startupStructured; projectedCounts = startupProjected; reverseTried = startupReverse;
        }
        startupSeeds = null; oldStartupRows.clear(); oldStartupGoals.clear();
        budget.release(startupMemory); startupMemory = 0;
    }

    private boolean beginProjection() {
        if (allowance - work < 32768) return finish();
        // Keep scheduling/physical verification room; numerical suggestions
        // cannot publish a count vector as if it were an execution.
        projection = new CountDemandProjection(model.recipes.size(), model.constraints, budget,
                Math.max(0, allowance - work) * 3 / 4);
        return false;
    }

    private boolean beginAllocation() {
        if (allowance - work < 32768) return finish();
        allocation = new CountDemandAllocation(model.recipes.size(), model.constraints, producers, budget);
        return false;
    }

    private void scheduleCounts() {
        reverseTried = false;
        // Independently re-evaluate every original row for every strategy.
        for (var row : model.constraints) {
            BigInteger total = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check(); total = total.add(counts[term.getKey()].multiply(term.getValue()));
            }
            if (total.compareTo(row.upper()) > 0) throw new IllegalStateException("Invalid deficit closure");
        }
        if (allocation == null && !projectedCounts) schedule = new CountSchedule<>(model, counts, budget, true);
        else topology = new CountScheduleTopology<>(model, counts, budget);
    }

    private boolean finish() {
        done = true;
        budget.note("count_demand", "witness=" + (result != null) + "; attempts=" + (attempt + 1) + "; work=" + work);
        return true;
    }

    GraphPlan<K> result() { return result; }

    @Override public void close() {
        restoreStartup();
        if (candidate != null) candidate.close(); candidate = null;
        if (schedule != null) schedule.close(); schedule = null;
        if (allocation != null) allocation.close(); allocation = null;
        if (projection != null) projection.close(); projection = null;
        if (reverse != null) reverse.close(); reverse = null;
        if (topology != null) topology.close(); topology = null;
        if (model != null) model.close(); model = null;
        budget.release(memory); memory = 0;
    }
}
