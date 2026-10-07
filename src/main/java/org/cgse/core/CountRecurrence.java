package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Construct repeated subprograms before expanding their trace. Failure is only a heuristic miss. */
final class CountRecurrence<K> implements AutoCloseable {

    private final RecipeCountModel<K> model;
    private final BigInteger[] counts;
    private final List<SequenceSummary<K>> recipes;
    private final PlanningBudget budget;
    private final BitSet included = new BitSet();
    private final IdentityHashMap<PlanStep, Integer> depths = new IdentityHashMap<>();
    private SequenceSummary<K> summary = SequenceSummary.empty();
    private PlanStep body, witness;
    private BigInteger period;
    private long memory;
    private boolean complete;

    CountRecurrence(RecipeCountModel<K> model, BigInteger[] counts, List<SequenceSummary<K>> recipes, PlanningBudget budget) {
        this.model = model;
        this.counts = counts;
        this.recipes = recipes;
        this.budget = budget;
        long bytes = 1024L + 512L * counts.length * model.keys.size();
        if (counts.length > 120 || Arrays.stream(counts).filter(v -> v.signum() > 0).distinct().count() < 3 ||
                model.recipes.stream().anyMatch(GraphRecipe::batchSensitiveInputs) || !budget.tryReserve(bytes))
            complete = true;
        else memory = bytes;
    }

    boolean step() {
        if (complete) return true;
        BigInteger next = BigInteger.ZERO;
        for (int i = 0; i < counts.length; i++) if (!included.get(i)) next = next.max(counts[i]);
        if (next.signum() == 0) {
            if (body != null && funded(scale(summary, period))) {
                PlanStep candidate = repeat(body, period);
                if (depth(candidate) <= 120) witness = candidate;
            }
            complete = true;
            if (witness != null) budget.note("count_recurrence", "verified_shared_program; recipes=" + included.cardinality());
            return true;
        }
        if (period != null && period.remainder(next).signum() != 0) return stop();
        BigInteger ratio = period == null ? BigInteger.ZERO : period.divide(next);
        for (int i = 0; i < counts.length; i++) if (!included.get(i) && counts[i].equals(next)) {
            Set<BigInteger> cuts = new LinkedHashSet<>(List.of(ratio.divide(BigInteger.TWO), ratio, BigInteger.ZERO));
            BigInteger interior = insertion(recipes.get(i), ratio);
            if (interior != null) cuts.add(interior);
            for (BigInteger before : cuts) {
                budget.check();
                BigInteger after = ratio.subtract(before);
                SequenceSummary<K> candidate = scale(summary, before).then(recipes.get(i)).then(scale(summary, after));
                if (!funded(candidate)) continue;
                List<PlanStep> children = new ArrayList<>();
                if (before.signum() > 0) children.add(repeat(body, before));
                children.add(new PlanStep.Batch(model.recipes.get(i).id(), 1));
                if (after.signum() > 0) children.add(repeat(body, after));
                body = children.size() == 1 ? children.get(0) : new PlanStep.Sequence(children);
                // Leave headroom for assembly and the persisted program envelope.
                if (depth(body) > 120) return stop();
                summary = candidate;
                included.set(i);
                period = next;
                return false;
            }
        }
        return stop();
    }

    private boolean funded(SequenceSummary<K> candidate) {
        for (var entry : candidate.required().entrySet()) {
            budget.check();
            if (!model.external.contains(entry.getKey()) && entry.getValue().compareTo(BigInteger.valueOf(model.stock.getOrDefault(entry.getKey(), 0L))) > 0) return false;
        }
        return true;
    }

    /** Exact feasible interval for S^a ; R ; S^(n-a), including shared inputs. */
    private BigInteger insertion(SequenceSummary<K> middle, BigInteger times) {
        if (times.compareTo(BigInteger.TWO) < 0) return null;
        BigInteger[] interval = { BigInteger.ONE, times.subtract(BigInteger.ONE) };
        Set<K> keys = summary.keys();
        keys.addAll(middle.keys());
        for (K key : keys) if (!model.external.contains(key)) {
            budget.check();
            BigInteger stock = BigInteger.valueOf(model.stock.getOrDefault(key, 0L));
            BigInteger delta = summary.delta(key), loss = delta.negate().max(BigInteger.ZERO);
            // Prefix, insertion, suffix requirements are affine in a whenever
            // both copies of S are nonempty. Endpoints are tried separately.
            if (!constrain(interval, loss, stock.subtract(summary.required(key)).add(loss)) ||
                    !constrain(interval, delta.negate(), stock.subtract(middle.required(key))) ||
                    !constrain(interval, loss.add(delta).negate(), stock.subtract(summary.required(key))
                            .subtract(loss.multiply(times.subtract(BigInteger.ONE))).add(middle.delta(key))))
                return null;
        }
        return interval[0];
    }

    private static boolean constrain(BigInteger[] interval, BigInteger coefficient, BigInteger upper) {
        if (coefficient.signum() == 0) return upper.signum() >= 0;
        if (coefficient.signum() > 0) {
            BigInteger[] qr = upper.divideAndRemainder(coefficient);
            BigInteger floor = qr[0].subtract(qr[1].signum() < 0 ? BigInteger.ONE : BigInteger.ZERO);
            interval[1] = interval[1].min(floor);
        } else {
            BigInteger positive = coefficient.negate(), value = upper.negate();
            BigInteger[] qr = value.divideAndRemainder(positive);
            BigInteger ceil = qr[0].add(qr[1].signum() > 0 ? BigInteger.ONE : BigInteger.ZERO);
            interval[0] = interval[0].max(ceil);
        }
        return interval[0].compareTo(interval[1]) <= 0;
    }

    private SequenceSummary<K> scale(SequenceSummary<K> source, BigInteger times) {
        if (times.signum() == 0) return SequenceSummary.empty();
        if (times.equals(BigInteger.ONE)) return source;
        Map<K, BigInteger> required = new LinkedHashMap<>(), delta = new LinkedHashMap<>(), peak = new LinkedHashMap<>();
        for (K key : source.keys()) {
            budget.check();
            BigInteger change = source.delta(key);
            required.put(key, source.required(key).add(change.negate().max(BigInteger.ZERO).multiply(times.subtract(BigInteger.ONE))));
            delta.put(key, change.multiply(times));
            peak.put(key, source.peak(key).add(change.max(BigInteger.ZERO).multiply(times.subtract(BigInteger.ONE))));
        }
        return new SequenceSummary<>(required, delta, peak);
    }

    private int depth(PlanStep step) {
        Integer known = depths.get(step);
        if (known != null) return known;
        budget.check();
        int result = 0;
        if (step instanceof PlanStep.Repeat repeat) result = 1 + depth(repeat.body());
        else if (step instanceof PlanStep.Sequence sequence)
            for (PlanStep child : sequence.children()) result = Math.max(result, 1 + depth(child));
        depths.put(step, result);
        return result;
    }

    private static PlanStep repeat(PlanStep body, BigInteger times) {
        return times.equals(BigInteger.ONE) ? body : PlanStep.repeat(body, times);
    }

    private boolean stop() {
        complete = true;
        return true;
    }

    PlanStep witness() {
        return witness;
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
