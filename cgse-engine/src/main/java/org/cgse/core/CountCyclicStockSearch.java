// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Positive-only count proposal retaining funded cyclic stock while arranging
 * shared inputs. Optional terminal goals are never missing-material or UNSAT
 * evidence, and the resulting word is checked against the original request. */
final class CountCyclicStockSearch<K> implements AutoCloseable {
    private final GraphCompiler<K> compiler;
    private final K target;
    private final long amount, started, allowance;
    private final Map<K, Long> stock, seeds;
    private final Set<K> external;
    private final Set<String> excluded;
    private final boolean preserve, force;
    private final PlanningBudget budget;
    private final Map<String, GraphRecipe<K>> recipes = new LinkedHashMap<>();
    private final Map<Integer, ExactLinearProgram.Constraint> originalRows = new LinkedHashMap<>();
    private final Map<K, BigInteger> originalGoals = new LinkedHashMap<>();
    private final BitSet recipeComponents = new BitSet();
    private RecipeCountModel<K> model;
    private CountDemandProjection projection;
    private CountScheduleTopology<K> topology;
    private CountSchedule<K> schedule;
    private CountReverseSchedule<K> reverse;
    private AllocationSearch.Candidate<K> candidate;
    private BigInteger[] counts, originalCounts;
    private int[] originalComponents;
    private GraphPlan<K> result;
    private long work, memory;
    private int row, phase, retainedGoals;
    private boolean tightened, originalTried, reverseTried, reverseCandidate, done;

    CountCyclicStockSearch(GraphCompiler<K> compiler, K target, long amount, Map<K, Long> stock,
                           Set<K> external, Map<K, Long> seeds, Set<String> excluded,
                           boolean preserve, boolean force, PlanningBudget budget, long started) {
        this.compiler = compiler; this.target = target; this.amount = amount; this.stock = stock;
        this.external = external; this.seeds = seeds; this.excluded = excluded;
        this.preserve = preserve; this.force = force; this.budget = budget; this.started = started;
        // Leave at least half of the remaining account for the ordinary
        // portfolio. This candidate has no independent or replenished budget.
        allowance = Math.min(8_388_608, budget.remainingWork() / 2);
    }

    boolean step() {
        if (done) return true;
        long before = budget.threadWork();
        String detail = budget.failureDetail();
        try {
            budget.checkpoint();
            if (work >= allowance) return finish();
            if (phase == 0) {
                if (allowance < 65536) return finish();
                model = RecipeCountModel.create(compiler, target, amount, stock, seeds, external, excluded, force, budget);
                if (model == null) return finish();
                long bytes = 4096L + 768L * (model.recipes.size() + model.rowKeys.size());
                if (!budget.tryReserve(bytes)) return finish();
                memory = bytes;
                for (var recipe : model.recipes) { budget.check(); recipes.put(recipe.id(), recipe); }
                phase = 1;
                return project();
            }
            if (projection != null) {
                if (!projection.step()) return false;
                counts = projection.result(); projection.close(); projection = null;
                if (counts == null) return tryOriginal();
                if (originalCounts == null) originalCounts = counts;
                topology = new CountScheduleTopology<>(model, counts, budget, true);
                return false;
            }
            if (topology != null && phase == 1) {
                if (!topology.step()) return false;
                int[] components = topology.result();
                if (components == null) { topology.close(); topology = null; return tryOriginal(); }
                if (originalComponents == null) {
                    originalComponents = components;
                    for (int i = 0; i < counts.length; i++) {
                        budget.check();
                        if (counts[i].signum() > 0) recipeComponents.set(components[i]);
                    }
                    phase = 2;
                } else {
                    topology.close(); topology = null;
                    schedule = CountSchedule.componentBalanced(model, counts, components, budget, 512);
                }
                return false;
            }
            if (phase == 2) {
                if (row < model.rowKeys.size()) { retainCyclicStock(row++); return false; }
                topology.close(); topology = null;
                phase = 1;
                if (originalRows.isEmpty()) return tryOriginal();
                tightened = true; retainedGoals = originalRows.size();
                budget.note("count_cyclic_stock", "optional_terminal_goals=" + retainedGoals);
                return project();
            }
            if (reverse != null) return stepReverse();
            if (candidate != null) {
                if (!candidate.step()) return false;
                if (candidate.plan != null && candidate.plan.feasible()) {
                    result = candidate.plan;
                    return finish();
                }
                candidate.close(); candidate = null;
                if (reverseCandidate) return afterReverse();
                return tryReverse();
            }
            if (schedule != null) {
                // A long balanced scan can make tiny progress on a large
                // shared-input cycle. Try the other endpoint before spending
                // the whole account, keeping this exact forward continuation.
                if (!reverseTried && schedule.completedPasses() >= 64) return tryReverse();
                if (!schedule.step()) return false;
                PlanStep word = schedule.witness(); schedule.close(); schedule = null;
                if (word == null) return tryReverse();
                // The optional terminal stock was a proposal aid, not an
                // extra caller seed contract or a synthetic inventory credit.
                candidate = new AllocationSearch.Candidate<>(word, recipes, target, amount, stock,
                        seeds, external, preserve, force, false, budget, started);
                reverseCandidate = false;
                return false;
            }
            return finish();
        } catch (PlanningBudget.Exhausted limit) {
            if (limit.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw limit;
            close(); budget.failureDetail(detail);
            return finish();
        } finally { work += budget.threadWork() - before; }
    }

    private boolean project() {
        if (allowance - work < 32768) return finish();
        projection = new CountDemandProjection(model.recipes.size(), model.constraints, budget,
                Math.max(0, allowance - work) * 3 / 4, 512);
        return false;
    }

    private void retainCyclicStock(int index) {
        budget.check();
        K key = model.rowKeys.get(index);
        int component = topology.component(key);
        if (component < 0 || !recipeComponents.get(component) || external.contains(key)) return;
        BigInteger available = PlanningInventory.exactAmount(stock, key);
        if (available.signum() <= 0) return;
        var constraint = model.constraints.get(index);
        BigInteger spent = BigInteger.ZERO;
        for (var term : constraint.terms().entrySet()) {
            budget.check(); spent = spent.add(term.getValue().multiply(counts[term.getKey()]));
        }
        if (spent.signum() <= 0) return;
        BigInteger goal = target.equals(key) ? available.add(BigInteger.valueOf(amount)) : available;
        BigInteger previous = model.goal(key);
        if (goal.compareTo(previous) <= 0 || goal.bitLength() > 1024) return;
        originalRows.put(index, constraint);
        originalGoals.put(key, model.goals.get(key));
        model.goals.put(key, goal);
        model.constraints.set(index, new ExactLinearProgram.Constraint(constraint.terms(),
                constraint.upper().subtract(goal.subtract(previous))));
    }

    private boolean tryOriginal() {
        if (originalTried || originalCounts == null || originalComponents == null) return finish();
        originalTried = true;
        reverseTried = false;
        restore();
        counts = originalCounts;
        phase = 1;
        schedule = CountSchedule.componentBalanced(model, counts, originalComponents, budget, 512);
        return false;
    }

    private boolean tryReverse() {
        if (reverseTried) return afterReverse();
        reverseTried = true;
        long remaining = Math.max(0, allowance - work);
        // Keep room for the suspended forward scan as well as the ordinary
        // portfolio outside this optional proposal. No budget is replenished.
        reverse = new CountReverseSchedule<>(model, counts, budget, Math.min(1_048_576, remaining / 2));
        budget.note("count_cyclic_stock_reverse", "forward_passes=" +
                (schedule == null ? "finished" : schedule.completedPasses()));
        return false;
    }

    private boolean stepReverse() {
        String detail = budget.failureDetail();
        PlanStep word;
        try {
            if (!reverse.step()) return false;
            word = reverse.witness();
        } catch (PlanningBudget.Exhausted limit) {
            if (limit.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw limit;
            // This extra candidate cannot dispose a funded forward scan just
            // because its own workspace did not fit alongside that scan.
            reverse.close(); reverse = null;
            budget.failureDetail(detail);
            return afterReverse();
        }
        reverse.close(); reverse = null;
        if (word == null) return afterReverse();
        candidate = new AllocationSearch.Candidate<>(word, recipes, target, amount, stock,
                seeds, external, preserve, force, false, budget, started);
        reverseCandidate = true;
        return false;
    }

    private boolean afterReverse() {
        return schedule != null ? false : tryOriginal();
    }

    private void restore() {
        if (model == null) return;
        originalRows.forEach((index, constraint) -> model.constraints.set(index, constraint));
        originalGoals.forEach((key, goal) -> {
            if (goal == null) model.goals.remove(key); else model.goals.put(key, goal);
        });
        originalRows.clear(); originalGoals.clear();
    }

    private boolean finish() {
        done = true;
        budget.note("count_cyclic_stock", "witness=" + (result != null) + "; goals=" + retainedGoals +
                "; tightened=" + tightened + "; original_retry=" + originalTried + "; work=" + work);
        return true;
    }

    GraphPlan<K> result() { return result; }

    @Override public void close() {
        if (candidate != null) candidate.close(); candidate = null;
        if (schedule != null) schedule.close(); schedule = null;
        if (reverse != null) reverse.close(); reverse = null;
        if (projection != null) projection.close(); projection = null;
        if (topology != null) topology.close(); topology = null;
        restore();
        if (model != null) model.close(); model = null;
        budget.release(memory); memory = 0;
    }
}
