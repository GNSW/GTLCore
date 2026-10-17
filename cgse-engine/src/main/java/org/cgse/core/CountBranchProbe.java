// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Bounded inference branching: probe both sides of fractional candidates and
 * prefer balanced propagation gains, as in SCIP's inference/strong branching.
 * A score changes the branching order only. Both complementary children remain
 * present even when a probe times out or reports a contradiction.
 */
final class CountBranchProbe implements AutoCloseable {

    private final PlanningBudget budget;
    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final ExactRational[] point;
    private final List<Integer> candidates = new ArrayList<>();
    private final long allowance;
    private CountBounds propagating;
    private CountBounds.Seed seed;
    private int cursor, side, chosen;
    private double firstGain, bestScore = -1;
    private long memory, work, probeStart;
    private boolean initialized, complete;
    private CountBranchHistory history;
    private int[] representatives;
    private int reused;
    private BigInteger[] objective;
    private ExactRational parentObjective;
    private ExactLinearProgram strong;
    private double pendingGain;
    private long strongStart;
    private int lpProbes;

    CountBranchProbe objective(BigInteger[] value) {
        objective = value.clone();
        parentObjective = ExactRational.ZERO;
        for (int i = 0; i < point.length; i++) parentObjective = parentObjective.add(point[i].multiply(ExactRational.of(value[i])));
        return this;
    }

    CountBranchProbe history(CountBranchHistory value, int[] ids) {
        history = value;
        representatives = ids;
        return this;
    }

    CountBranchProbe(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                     ExactRational[] point, int fallback, PlanningBudget budget) {
        this(rows, lower, upper, point, fallback, budget, false);
    }

    CountBranchProbe(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                     ExactRational[] point, int fallback, PlanningBudget budget, boolean feedback) {
        this.rows = new ArrayList<>(rows);
        this.lower = lower;
        this.upper = upper;
        this.point = point;
        this.budget = budget;
        chosen = fallback;
        allowance = Math.min(32768, budget.remainingWork() / 32);
        if (lower.length > 128 || rows.size() > 512 || allowance < 4096) {
            complete = true;
            return;
        }
        // On acyclic, wide allocation domains, counting bound updates alone is
        // a poor substitute for the existing cost/activity order. In feedback
        // regions, propagation also exposes coupled recycle/startup decisions.
        for (int i = 0; !feedback && i < point.length; i++) if (!point[i].integral() &&
                (upper[i] == null || upper[i].subtract(lower[i]).compareTo(BigInteger.ONE) > 0)) {
                    complete = true;
                    return;
                }
        long bytes = 1024 + 512L * lower.length + 32L * rows.size();
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        for (int i = 0; i < lower.length; i++) {
            this.rows.add(bound(i, lower[i], true));
            if (upper[i] != null) this.rows.add(bound(i, upper[i], false));
            if (!point[i].integral()) candidates.add(i);
        }
        int[] degree = new int[lower.length];
        for (var row : rows) for (int id : row.terms().keySet()) degree[id]++;
        candidates.sort(Comparator.<Integer>comparingInt(i -> i == fallback ? Integer.MIN_VALUE : -degree[i]).thenComparingInt(i -> i));
        if (candidates.size() < 2) complete = true;
    }

    boolean step() {
        if (complete) return true;
        long before = budget.threadWork();
        try {
            budget.check();
            if (work >= allowance || cursor >= Math.min(4, candidates.size())) return finish();
            int id = candidates.get(cursor);
            if (strong != null) {
                boolean done = strong.step();
                if (!done && work - strongStart < 4096) return false;
                double gain = pendingGain;
                if (done && strong.result() == ExactLinearProgram.Result.INFEASIBLE) gain += 4L * lower.length;
                else if (done && strong.result() == ExactLinearProgram.Result.OPTIMAL) {
                    ExactRational value = ExactRational.ZERO;
                    var candidate = strong.point();
                    for (int i = 0; i < candidate.length; i++) {
                        budget.check();
                        value = value.add(candidate[i].multiply(ExactRational.of(objective[i])));
                    }
                    ExactRational delta = parentObjective.subtract(value);
                    if (delta.signum() > 0) {
                        var scale = parentObjective.signum() < 0 ? parentObjective.negate() : parentObjective;
                        var normalized = delta.divide(scale.add(ExactRational.ONE));
                        gain += lower.length * Math.min(1, normalized.numerator().doubleValue() / normalized.denominator().doubleValue());
                    }
                }
                strong.close();
                strong = null;
                lpProbes++;
                acceptGain(id, gain);
                return false;
            }
            if (side == 0 && history != null && history.reliable(representatives[id])) {
                double score = history.score(representatives[id], point[id]);
                if (score > bestScore) {
                    bestScore = score;
                    chosen = id;
                }
                cursor++;
                reused++;
                return false;
            }
            if (!initialized) {
                if (propagating == null) propagating = new CountBounds(lower.length, rows, budget, rows.size());
                if (!propagating.step()) return false;
                if (!propagating.blocked()) seed = propagating.snapshot();
                propagating.close();
                propagating = null;
                initialized = true;
                return false;
            }
            if (propagating == null) {
                var input = new ArrayList<>(rows);
                input.add(bound(id, side == 0 ? point[id].floor() : point[id].ceil(), side != 0));
                probeStart = work;
                propagating = new CountBounds(lower.length, input, budget, rows.size(), seed);
            }
            if (work - probeStart < 4096 && !propagating.step()) return false;
            double gain = 0;
            BigInteger[] low = propagating.lowerBounds(), high = propagating.upperBounds();
            for (int i = 0; i < lower.length; i++) {
                budget.check();
                if (low[i].compareTo(lower[i]) > 0) gain++;
                if (high[i] != null && (upper[i] == null || high[i].compareTo(upper[i]) < 0)) gain++;
                if (!lower[i].equals(upper[i]) && low[i].equals(high[i])) gain += 2;
            }
            if (propagating.blocked()) gain += 4L * lower.length;
            boolean blocked = propagating.blocked();
            propagating.close();
            propagating = null;
            if (!blocked && objective != null && lower.length <= 48 && work + 4096 < allowance) {
                var input = new ArrayList<>(rows);
                input.add(bound(id, side == 0 ? point[id].floor() : point[id].ceil(), side != 0));
                pendingGain = gain;
                strongStart = work;
                strong = new ExactLinearProgram(lower.length, input, objective, budget);
                return false;
            }
            acceptGain(id, gain);
            return false;
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private void acceptGain(int id, double gain) {
        if (!Double.isFinite(gain)) gain = pendingGain;
        if (history != null) history.observe(representatives[id], side, point[id], gain);
        if (side == 0) {
            firstGain = gain;
            side = 1;
        } else {
            double score = Math.min(firstGain, gain) + 0.125 * Math.max(firstGain, gain);
            if (score > bestScore) {
                bestScore = score;
                chosen = id;
            }
            side = 0;
            cursor++;
        }
    }

    private static ExactLinearProgram.Constraint bound(int id, BigInteger value, boolean minimum) {
        return new ExactLinearProgram.Constraint(Map.of(id, minimum ? BigInteger.ONE.negate() : BigInteger.ONE), minimum ? value.negate() : value);
    }

    private boolean finish() {
        complete = true;
        budget.note("count_branch_probe", "candidates=" + cursor + "; selected=" + chosen + "; reused=" + reused + "; lp_probes=" + lpProbes + "; work=" + work + "; both_children_retained");
        return true;
    }

    int chosen() {
        return chosen;
    }

    /** Structural classification for a heuristic only; no feasibility conclusion. */
    static <K> boolean feedback(RecipeCountModel<K> model, PlanningBudget budget) {
        int resources = model.keys.size(), size = resources + model.recipes.size();
        if (size > 2048) return false;
        long terms = model.recipes.stream().mapToLong(r -> r.inputs().size() + r.outputs().size()).sum();
        long bytes = 256L + 128L * size + 32L * terms;
        if (!budget.tryReserve(bytes)) return false;
        try {
            List<List<Integer>> edges = new ArrayList<>();
            int[] incoming = new int[size];
            for (int i = 0; i < size; i++) edges.add(new ArrayList<>());
            for (int i = 0; i < model.recipes.size(); i++) {
                var recipe = model.recipes.get(i);
                int node = resources + i;
                for (K key : recipe.inputs().keySet()) {
                    budget.check();
                    Integer id = model.ids.get(key);
                    if (id != null) {
                        edges.get(id).add(node);
                        incoming[node]++;
                    }
                }
                for (K key : recipe.outputs().keySet()) {
                    budget.check();
                    Integer id = model.ids.get(key);
                    if (id != null) {
                        edges.get(node).add(id);
                        incoming[id]++;
                    }
                }
            }
            Deque<Integer> ready = new ArrayDeque<>();
            for (int i = 0; i < size; i++) if (incoming[i] == 0) ready.addLast(i);
            int visited = 0;
            while (!ready.isEmpty()) {
                budget.check();
                visited++;
                for (int next : edges.get(ready.removeFirst())) if (--incoming[next] == 0) ready.addLast(next);
            }
            return visited != size;
        } finally {
            budget.release(bytes);
        }
    }

    @Override
    public void close() {
        if (propagating != null) propagating.close();
        if (strong != null) strong.close();
        strong = null;
        propagating = null;
        if (seed != null) seed.close();
        seed = null;
        budget.release(memory);
        memory = 0;
    }
}
