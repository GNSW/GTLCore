package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Bounded SAT/PB scheduling of an entire small fixed multiset. Prefix rows
 * encode original input availability, not merely the final state equation.
 * Refusing a horizon/workspace is UNKNOWN; only a complete multiset proof is DEAD.
 */
final class CountBoundedSchedule<K> implements AutoCloseable {

    private final RecipeCountModel<K> model;
    private final BigInteger[] counts;
    private final PlanningBudget budget;
    private final List<Integer> active = new ArrayList<>();
    private final List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
    private CountCdcl search;
    private PlanStep witness;
    private CountSchedule.Result result;
    private int horizon, stage, cursor;
    private long memory, terms;
    private final Set<K> keys = new LinkedHashSet<>();
    private List<K> resources;

    CountBoundedSchedule(RecipeCountModel<K> model, BigInteger[] counts, PlanningBudget budget) {
        this.model = model;
        this.counts = counts.clone();
        this.budget = budget;
        BigInteger total = BigInteger.ZERO;
        for (int i = 0; i < counts.length; i++) if (counts[i].signum() > 0) {
            total = total.add(counts[i]);
            active.add(i);
            if (model.recipes.get(i).batchSensitiveInputs()) {
                result = CountSchedule.Result.UNKNOWN;
                return;
            }
        }
        if (total.compareTo(BigInteger.valueOf(32)) > 0 || active.size() > 16 || total.intValue() * active.size() > 512 ||
                budget.remainingWork() < 32768) {
            result = CountSchedule.Result.UNKNOWN;
            return;
        }
        horizon = total.intValueExact();
        long bytes = 4096 + 256L * horizon * active.size();
        if (!budget.tryReserve(bytes)) {
            result = CountSchedule.Result.UNKNOWN;
            return;
        }
        memory = bytes;
        for (int id : active) {
            keys.addAll(model.recipes.get(id).inputs().keySet());
            keys.addAll(model.recipes.get(id).outputs().keySet());
        }
        keys.removeAll(model.external);
        resources = new ArrayList<>(keys);
    }

    boolean step() {
        if (result != null) return true;
        budget.check();
        if (search != null) {
            if (!search.step()) return false;
            var assignment = search.counts();
            if (assignment == null) return finish(search.infeasible() ? CountSchedule.Result.DEAD : CountSchedule.Result.UNKNOWN);
            Map<K, BigInteger> held = new LinkedHashMap<>();
            model.stock.forEach((key, value) -> held.put(key, BigInteger.valueOf(value)));
            var used = new BigInteger[counts.length];
            Arrays.fill(used, BigInteger.ZERO);
            List<PlanStep> sequence = new ArrayList<>();
            for (int t = 0; t < horizon; t++) {
                int selected = -1;
                for (int r = 0; r < active.size(); r++) if (assignment[t * active.size() + r].signum() > 0) {
                    if (selected >= 0) throw new IllegalStateException("Multiple firings at one SAT schedule step");
                    selected = active.get(r);
                }
                if (selected < 0) throw new IllegalStateException("Empty SAT schedule step");
                var recipe = model.recipes.get(selected);
                for (var input : recipe.inputs().entrySet()) {
                    budget.check();
                    if (!model.external.contains(input.getKey()) && held.getOrDefault(input.getKey(), BigInteger.ZERO).compareTo(BigInteger.valueOf(input.getValue())) < 0)
                        throw new IllegalStateException("Unavailable SAT schedule input");
                }
                recipe.inputs().forEach((key, value) -> held.merge(key, BigInteger.valueOf(value).negate(), BigInteger::add));
                recipe.outputs().forEach((key, value) -> held.merge(key, BigInteger.valueOf(value), BigInteger::add));
                used[selected] = used[selected].add(BigInteger.ONE);
                sequence.add(PlanStep.batch(recipe.id(), BigInteger.ONE));
            }
            if (!Arrays.equals(used, counts)) throw new IllegalStateException("SAT schedule changed fixed counts");
            witness = new PlanStep.Sequence(sequence);
            return finish(CountSchedule.Result.WITNESS);
        }
        if (stage == 0) {
            if (cursor < horizon) {
                Map<Integer, BigInteger> exactlyOne = new LinkedHashMap<>();
                for (int r = 0; r < active.size(); r++) exactlyOne.put(cursor * active.size() + r, BigInteger.ONE);
                cursor++;
                equality(exactlyOne, BigInteger.ONE);
                return result != null;
            }
            stage++;
            cursor = 0;
        }
        if (stage == 1) {
            if (cursor < active.size()) {
                Map<Integer, BigInteger> occurrences = new LinkedHashMap<>();
                for (int t = 0; t < horizon; t++) occurrences.put(t * active.size() + cursor, BigInteger.ONE);
                equality(occurrences, counts[active.get(cursor++)]);
                return result != null;
            }
            stage++;
            cursor = 0;
        }
        if (cursor < resources.size() * horizon) {
            int time = cursor / resources.size();
            K key = resources.get(cursor++ % resources.size());
            Map<Integer, BigInteger> prefix = new LinkedHashMap<>();
            for (int t = 0; t <= time; t++) for (int r = 0; r < active.size(); r++) {
                budget.check();
                var recipe = model.recipes.get(active.get(r));
                BigInteger input = BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L));
                BigInteger coefficient = t == time ? input : input.subtract(BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L)));
                if (coefficient.signum() != 0) prefix.put(t * active.size() + r, coefficient);
            }
            add(prefix, BigInteger.valueOf(model.stock.getOrDefault(key, 0L)));
            return result != null;
        }
        BigInteger[] low = new BigInteger[horizon * active.size()], high = new BigInteger[low.length];
        Arrays.fill(low, BigInteger.ZERO);
        Arrays.fill(high, BigInteger.ONE);
        search = new CountCdcl(rows, low, high, budget, 131072);
        return false;
    }

    private void equality(Map<Integer, BigInteger> terms, BigInteger value) {
        add(terms, value);
        Map<Integer, BigInteger> opposite = new LinkedHashMap<>();
        terms.forEach((id, weight) -> opposite.put(id, weight.negate()));
        add(opposite, value.negate());
    }

    private void add(Map<Integer, BigInteger> coefficients, BigInteger value) {
        terms += coefficients.size();
        long bytes = 128L + 192L * coefficients.size();
        if (terms > 32768 || rows.size() >= 2048 || !budget.tryReserve(bytes)) {
            result = CountSchedule.Result.UNKNOWN;
            return;
        }
        memory += bytes;
        rows.add(new ExactLinearProgram.Constraint(coefficients, value));
    }

    private boolean finish(CountSchedule.Result outcome) {
        result = outcome;
        budget.note("count_bounded_schedule", outcome + "; complete_multiset_horizon=" + horizon + "; recipes=" + active.size() + "; terms=" + terms);
        return true;
    }

    PlanStep witness() {
        return witness;
    }

    CountSchedule.Result result() {
        return result;
    }

    @Override
    public void close() {
        if (search != null) {
            search.close();
            search = null;
        }
        budget.release(memory);
        memory = 0;
    }
}
