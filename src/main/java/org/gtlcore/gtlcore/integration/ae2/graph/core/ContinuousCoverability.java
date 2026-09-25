package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Continuous coverability via the state equation and forward/reverse firing
 * supports (Fraca-Haddad characterization). Drains reduce covering to reaching.
 * A local cutoff is UNKNOWN; a positive answer is not an integer execution plan.
 */
final class ContinuousCoverability<K> implements AutoCloseable {

    enum Result { POSSIBLE, BLOCKED, UNKNOWN }

    private record Transition(Map<Integer, BigInteger> inputs, Map<Integer, BigInteger> outputs) {}

    private final RecipeCountModel<K> model;
    private final PlanningBudget budget;
    private final List<Transition> transitions = new ArrayList<>();
    private final List<ExactLinearProgram.Constraint> equations = new ArrayList<>();
    private final BitSet active = new BitSet(), support = new BitSet();
    private ExactLinearProgram solving;
    private Result result;
    private final long allowance;
    private long work, memory;

    ContinuousCoverability(RecipeCountModel<K> model, PlanningBudget budget) {
        this.model = model;
        this.budget = budget;
        allowance = Math.min(750_000, budget.remainingWork() / 4);
        if (model.recipes.size() > 64 || model.keys.size() > 64 || !budget.tryReserve(512L << 10)) {
            result = Result.UNKNOWN;
            return;
        }
        memory = 512L << 10;
        if (model.keys.stream().allMatch(key -> BigInteger.valueOf(model.stock.getOrDefault(key, 0L)).compareTo(model.goal(key)) >= 0)) {
            finish(Result.POSSIBLE);
            return;
        }
        for (GraphRecipe<K> recipe : model.recipes) {
            Map<Integer, BigInteger> inputs = new LinkedHashMap<>(), outputs = new LinkedHashMap<>();
            for (K key : model.keys) {
                budget.check();
                long in = recipe.inputs().getOrDefault(key, 0L) - recipe.configurationInputs().getOrDefault(key, 0L);
                long out = recipe.outputs().getOrDefault(key, 0L);
                if (in > 0) inputs.put(model.ids.get(key), BigInteger.valueOf(in));
                if (out > 0) outputs.put(model.ids.get(key), BigInteger.valueOf(out));
            }
            transitions.add(new Transition(inputs, outputs));
        }
        for (int i = 0; i < model.keys.size(); i++) {
            transitions.add(new Transition(Map.of(i, BigInteger.ONE), Map.of()));
            if (model.external.contains(model.keys.get(i))) transitions.add(new Transition(Map.of(), Map.of(i, BigInteger.ONE)));
        }
        for (int i = 0; i < model.keys.size(); i++) {
            Map<Integer, BigInteger> terms = new LinkedHashMap<>(), reversed = new LinkedHashMap<>();
            for (int t = 0; t < transitions.size(); t++) {
                var transition = transitions.get(t);
                BigInteger change = transition.outputs().getOrDefault(i, BigInteger.ZERO).subtract(transition.inputs().getOrDefault(i, BigInteger.ZERO));
                if (change.signum() != 0) {
                    terms.put(t, change);
                    reversed.put(t, change.negate());
                }
            }
            K key = model.keys.get(i);
            BigInteger difference = model.goal(key).subtract(BigInteger.valueOf(model.stock.getOrDefault(key, 0L)));
            equations.add(new ExactLinearProgram.Constraint(terms, difference));
            equations.add(new ExactLinearProgram.Constraint(reversed, difference.negate()));
        }
        active.set(0, transitions.size());
    }

    boolean step() {
        long before = budget.nodes();
        try {
            return advance();
        } finally {
            work += budget.nodes() - before;
        }
    }

    private boolean advance() {
        budget.check();
        if (result != null) return true;
        if (work >= allowance) return finish(Result.UNKNOWN);
        if (solving == null) {
            if (support.equals(active)) return refine();
            int slack = transitions.size();
            var constraints = new ArrayList<>(equations);
            Map<Integer, BigInteger> unseen = new LinkedHashMap<>();
            unseen.put(slack, BigInteger.ONE);
            for (int i = 0; i < transitions.size(); i++) {
                if (!active.get(i)) constraints.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), BigInteger.ZERO));
                else if (!support.get(i)) unseen.put(i, BigInteger.ONE.negate());
            }
            // Maximizing z <= min(1, sum(unseen counts)) discovers another
            // positive support member, or proves no unseen member can occur.
            constraints.add(new ExactLinearProgram.Constraint(unseen, BigInteger.ZERO));
            constraints.add(new ExactLinearProgram.Constraint(Map.of(slack, BigInteger.ONE), BigInteger.ONE));
            BigInteger[] objective = new BigInteger[slack + 1];
            Arrays.fill(objective, BigInteger.ZERO);
            objective[slack] = BigInteger.ONE;
            solving = new ExactLinearProgram(slack + 1, constraints, objective, budget);
            return false;
        }
        if (!solving.step()) return false;
        ExactLinearProgram.Result status = solving.result();
        ExactRational[] point = solving.point();
        solving.close();
        solving = null;
        if (status == ExactLinearProgram.Result.INFEASIBLE) return finish(Result.BLOCKED);
        if (status != ExactLinearProgram.Result.OPTIMAL) return finish(Result.UNKNOWN);
        for (int i = 0; i < transitions.size(); i++) if (point[i].signum() > 0) support.set(i);
        return point[transitions.size()].signum() == 0 ? refine() : false;
    }

    private boolean refine() {
        if (support.isEmpty()) return finish(Result.BLOCKED);
        BitSet remaining = closure(false);
        remaining.and(closure(true));
        if (remaining.equals(support)) return finish(Result.POSSIBLE);
        if (remaining.isEmpty()) return finish(Result.BLOCKED);
        active.clear();
        active.or(remaining);
        support.clear();
        return false;
    }

    private BitSet closure(boolean reverse) {
        BitSet places = new BitSet(), fired = new BitSet();
        for (int i = 0; i < model.keys.size(); i++) {
            K key = model.keys.get(i);
            if (reverse ? model.goal(key).signum() > 0 : model.stock.getOrDefault(key, 0L) > 0) places.set(i);
        }
        boolean changed;
        do {
            changed = false;
            for (int t = support.nextSetBit(0); t >= 0; t = support.nextSetBit(t + 1)) {
                budget.check();
                if (fired.get(t)) continue;
                var transition = transitions.get(t);
                var inputs = reverse ? transition.outputs() : transition.inputs();
                if (inputs.keySet().stream().anyMatch(i -> !places.get(i))) continue;
                fired.set(t);
                (reverse ? transition.inputs() : transition.outputs()).keySet().forEach(places::set);
                changed = true;
            }
        } while (changed);
        return fired;
    }

    Result result() { return result; }

    private boolean finish(Result status) {
        result = status;
        close();
        return true;
    }

    @Override
    public void close() {
        if (solving != null) solving.close();
        budget.release(memory);
        memory = 0;
    }
}
