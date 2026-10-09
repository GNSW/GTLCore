// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Builds an explicit acyclic startup prefix instead of borrowing every steady
 * state intermediate. Reachability only proposes a prefix: its exact summary
 * must still prove all inputs, joint outputs and retained seeds.
 */
final class RegionBootstrap<K> implements AutoCloseable {

    private record Introduction<K>(GraphRecipe<K> recipe, Set<K> outputs) {}

    private final List<GraphRecipe<K>> recipes;
    private final Map<String, GraphRecipe<K>> byId = new LinkedHashMap<>();
    private final Map<K, Long> stock;
    private final Set<K> external, produced = new LinkedHashSet<>(), boundary = new LinkedHashSet<>();
    private final PlanningBudget budget;
    private final long allowance;
    private final RegionSelection.Choice<K> original;
    private RegionSelection.Choice<K> best;
    private final List<K> candidates;
    private final List<BitSet> trials = new ArrayList<>();
    private final List<Introduction<K>> order = new ArrayList<>();
    private final Set<K> available = new HashSet<>();
    private final BitSet fired = new BitSet();
    private final Map<K, BigInteger> wanted = new LinkedHashMap<>(), needs = new LinkedHashMap<>();
    private final List<PlanStep> prefix = new ArrayList<>();
    private final PlanStep originalProgram;
    private PlanStep candidateProgram;
    private SummaryComputation<K> computation;
    private PlanCountComputation counting;
    private BigInteger[] counts;
    private Map<String, BigInteger> countsById;
    private CountProgram<K> reordered;
    private RecipeCountModel<K> model;
    private CountSchedule<K> scheduling;
    private long scheduleStarted;
    private SequenceSummary<K> originalSummary;
    private BitSet chosen;
    private long memory, work;
    private int phase, trial, cursor, tried;
    private boolean changed, complete, orderedAttempted;

    RegionBootstrap(List<GraphRecipe<K>> recipes, RegionSelection.Choice<K> original,
                    Map<K, Long> stock, Set<K> external, PlanningBudget budget) {
        this.recipes = recipes;
        this.original = best = original;
        this.stock = stock;
        this.external = external;
        this.budget = budget;
        candidates = List.copyOf(original.seeds().keySet());
        originalProgram = PlanStep.repeat(original.body(), original.runs());
        allowance = Math.min(131_072, budget.remainingWork() / 8);
        long entries = 0;
        for (var recipe : recipes) entries += recipe.inputs().size() + recipe.outputs().size();
        long bytes = 4096 + 512L * entries + 1024L * recipes.size();
        if (allowance < 1024 || !budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
    }

    boolean step() {
        if (complete) return true;
        long before = budget.threadWork();
        try {
            charge();
            if (work >= allowance) return finish("work_limit");
            switch (phase) {
                case 0 -> {
                    if (cursor < recipes.size()) {
                        var recipe = recipes.get(cursor++);
                        byId.put(recipe.id(), recipe);
                        produced.addAll(recipe.outputs().keySet());
                        boundary.addAll(recipe.inputs().keySet());
                        for (int i = 0; i < recipe.inputs().size() + recipe.outputs().size(); i++) charge();
                    } else phase = 1;
                }
                case 1 -> {
                    Map<K, BigInteger> required = new LinkedHashMap<>(), delta = new LinkedHashMap<>(), peak = new LinkedHashMap<>();
                    BigInteger repeats = original.runs(), previous = repeats.subtract(BigInteger.ONE);
                    for (K key : original.summary().keys()) {
                        charge();
                        BigInteger change = original.summary().delta(key);
                        required.put(key, original.summary().required(key).add(change.negate().max(BigInteger.ZERO).multiply(previous)));
                        delta.put(key, change.multiply(repeats));
                        peak.put(key, original.summary().peak(key).add(change.max(BigInteger.ZERO).multiply(previous)));
                    }
                    originalSummary = new SequenceSummary<>(required, delta, peak);
                    boundary.removeAll(produced);
                    boundary.addAll(external);
                    for (K key : produced) {
                        charge();
                        // Existing net consumables remain consumables. Never
                        // invent a new externally supplied cyclic intermediate.
                        if (originalSummary.required(key).signum() > 0 && originalSummary.delta(key).signum() < 0)
                            boundary.add(key);
                    }
                    for (K key : candidates) wanted.put(key, originalSummary.required(key));
                    prepareTrials();
                    phase = 2;
                }
                case 2 -> {
                    if (trial == trials.size()) return finish("searched");
                    chosen = trials.get(trial++);
                    tried++;
                    available.clear();
                    available.addAll(boundary);
                    for (int i = chosen.nextSetBit(0); i >= 0; i = chosen.nextSetBit(i + 1)) available.add(candidates.get(i));
                    fired.clear();
                    order.clear();
                    orderedAttempted = false;
                    cursor = 0;
                    changed = false;
                    phase = 3;
                }
                case 3 -> {
                    if (cursor == recipes.size()) {
                        if (changed) {
                            changed = false;
                            cursor = 0;
                            return false;
                        }
                        if (!available.containsAll(wanted.keySet())) {
                            phase = 2;
                            return false;
                        }
                        needs.clear();
                        needs.putAll(wanted);
                        boundary.forEach(needs::remove);
                        for (int i = chosen.nextSetBit(0); i >= 0; i = chosen.nextSetBit(i + 1)) needs.remove(candidates.get(i));
                        prefix.clear();
                        cursor = order.size() - 1;
                        phase = 4;
                        return false;
                    }
                    int id = cursor++;
                    if (fired.get(id)) return false;
                    var recipe = recipes.get(id);
                    for (K key : recipe.inputs().keySet()) {
                        charge();
                        if (!available.contains(key)) return false;
                    }
                    fired.set(id);
                    Set<K> fresh = new LinkedHashSet<>();
                    for (K key : recipe.outputs().keySet()) {
                        charge();
                        if (available.add(key)) fresh.add(key);
                    }
                    if (!fresh.isEmpty()) {
                        order.add(new Introduction<>(recipe, fresh));
                        changed = true;
                    }
                }
                case 4 -> {
                    if (cursor < 0) {
                        Collections.reverse(prefix);
                        prefix.add(originalProgram);
                        candidateProgram = new PlanStep.Sequence(prefix);
                        computation = new SummaryComputation<>(candidateProgram, byId, budget, Map.of(originalProgram, originalSummary));
                        phase = 5;
                        return false;
                    }
                    var introduction = order.get(cursor--);
                    var recipe = introduction.recipe();
                    BigInteger count = BigInteger.ZERO;
                    for (K key : introduction.outputs()) {
                        charge();
                        count = count.max(CheckedAmounts.ceilDiv(needs.getOrDefault(key, BigInteger.ZERO),
                                BigInteger.valueOf(recipe.outputs().get(key))));
                    }
                    if (count.signum() == 0) return false;
                    prefix.add(PlanStep.batch(recipe.id(), count));
                    for (var entry : recipe.outputs().entrySet()) {
                        charge();
                        K key = entry.getKey();
                        needs.put(key, needs.getOrDefault(key, BigInteger.ZERO).subtract(count.multiply(BigInteger.valueOf(entry.getValue()))).max(BigInteger.ZERO));
                    }
                    for (var entry : recipe.inputs().entrySet()) {
                        charge();
                        needs.merge(entry.getKey(), count.multiply(BigInteger.valueOf(entry.getValue())), BigInteger::add);
                    }
                }
                case 5 -> {
                    if (!computation.step()) return false;
                    consider(computation.result());
                    computation = null;
                    if (best.seeds().isEmpty()) return finish("zero_seed_witness");
                    phase = !chosen.isEmpty() && original.seeds().size() > 1 ? 6 : 2;
                }
                case 6 -> {
                    if (counts == null) {
                        if (counting == null) counting = new PlanCountComputation(originalProgram);
                        if (!counting.step(budget)) return false;
                        countsById = counting.result();
                        counts = recipes.stream().map(recipe -> countsById.getOrDefault(recipe.id(), BigInteger.ZERO)).toArray(BigInteger[]::new);
                        counting = null;
                    }
                    if (!orderedAttempted) {
                        orderedAttempted = true;
                        var ordered = new LinkedHashMap<String, GraphRecipe<K>>();
                        for (var entry : order) {
                            charge();
                            ordered.put(entry.recipe().id(), entry.recipe());
                        }
                        for (var recipe : recipes) {
                            charge();
                            ordered.putIfAbsent(recipe.id(), recipe);
                        }
                        var orderedRecipes = List.copyOf(ordered.values());
                        var orderedCounts = orderedRecipes.stream().map(recipe -> countsById.getOrDefault(recipe.id(), BigInteger.ZERO)).toArray(BigInteger[]::new);
                        reordered = new CountProgram<>(orderedRecipes, orderedCounts, budget);
                        phase = 9;
                        return false;
                    }
                    Map<K, Long> supplied = new LinkedHashMap<>();
                    for (int i = chosen.nextSetBit(0); i >= 0; i = chosen.nextSetBit(i + 1)) {
                        K key = candidates.get(i);
                        long count = original.seeds().get(key);
                        for (var recipe : recipes) {
                            charge();
                            count = Math.max(count, recipe.inputs().getOrDefault(key, 0L));
                        }
                        supplied.put(key, count);
                    }
                    model = RecipeCountModel.region(recipes, Map.of(), supplied, boundary, budget);
                    if (model == null) {
                        phase = 2;
                        return false;
                    }
                    scheduling = new CountSchedule<>(model, counts, budget, true);
                    scheduleStarted = work;
                    phase = 7;
                }
                case 7 -> {
                    if (work - scheduleStarted >= 16_384) {
                        closeSchedule();
                        phase = 2;
                        return false;
                    }
                    if (!scheduling.step()) return false;
                    if (scheduling.result() == CountSchedule.Result.WITNESS) {
                        candidateProgram = scheduling.witness();
                        computation = new SummaryComputation<>(candidateProgram, byId, budget);
                        phase = 8;
                    } else phase = 2;
                    closeSchedule();
                }
                case 8 -> {
                    if (!computation.step()) return false;
                    consider(computation.result());
                    computation = null;
                    if (best.seeds().isEmpty()) return finish("zero_seed_witness");
                    phase = 2;
                }
                case 9 -> {
                    if (!reordered.step()) return false;
                    if (reordered.available()) {
                        candidateProgram = reordered.program();
                        consider(reordered.summary());
                    }
                    reordered.close();
                    reordered = null;
                    if (best != original && best.seeds().size() <= 1) return finish("ordered_witness");
                    phase = 6;
                }
                default -> throw new IllegalStateException("Invalid bootstrap phase");
            }
            return false;
        } finally {
            work += budget.threadWork() - before;
        }
    }

    private void prepareTrials() {
        if (candidates.size() <= 10) {
            List<Integer> masks = new ArrayList<>();
            for (int mask = 0; mask < 1 << candidates.size(); mask++) masks.add(mask);
            masks.sort(Comparator.comparingInt(Integer::bitCount));
            for (int mask : masks) {
                charge();
                trials.add(BitSet.valueOf(new long[] { mask }));
            }
        } else {
            trials.add(new BitSet());
            for (int i = 0; i < candidates.size(); i++) {
                charge();
                BitSet single = new BitSet();
                single.set(i);
                trials.add(single);
            }
            for (int i = 0; i < candidates.size(); i++) {
                BitSet without = new BitSet();
                without.set(0, candidates.size());
                without.clear(i);
                trials.add(without);
            }
        }
    }

    private void consider(SequenceSummary<K> summary) {
        // A startup optimization must not consume the promised net production
        // or reclassify one of its chosen borrowed seeds as a spent raw input.
        for (var entry : originalSummary.delta().entrySet()) {
            charge();
            if (entry.getValue().signum() > 0 && summary.delta(entry.getKey()).compareTo(entry.getValue()) < 0) return;
        }
        for (int i = chosen.nextSetBit(0); i >= 0; i = chosen.nextSetBit(i + 1))
            if (summary.delta(candidates.get(i)).signum() < 0) return;
        Map<K, Long> reserve = new LinkedHashMap<>();
        for (K key : produced) {
            charge();
            if (!external.contains(key) && summary.required(key).signum() > 0 && summary.delta(key).signum() >= 0) {
                if (summary.required(key).compareTo(ExactAmounts.LONG_MAX) > 0) return;
                reserve.put(key, summary.required(key).longValueExact());
            }
        }
        int missing = missing(reserve), previous = missing(best.seeds());
        if (missing < previous || missing == previous && reserve.size() < best.seeds().size())
            best = new RegionSelection.Choice<>(candidateProgram, summary, BigInteger.ONE, Map.copyOf(reserve));
    }

    private int missing(Map<K, Long> seeds) {
        int count = 0;
        for (var entry : seeds.entrySet()) if (!external.contains(entry.getKey()) && entry.getValue() > stock.getOrDefault(entry.getKey(), 0L)) count++;
        return count;
    }

    private void charge() {
        budget.check();
    }

    private boolean finish(String detail) {
        complete = true;
        budget.note("seed_bootstrap", detail + "; recipes=" + recipes.size() + "; trials=" + tried +
                "; seed_types=" + original.seeds().size() + "->" + best.seeds().size() + "; work=" + work);
        return true;
    }

    RegionSelection.Choice<K> result() {
        return best;
    }

    private void closeSchedule() {
        if (scheduling != null) scheduling.close();
        if (model != null) model.close();
        scheduling = null;
        model = null;
    }

    @Override
    public void close() {
        if (computation != null) computation.close();
        computation = null;
        closeSchedule();
        if (reordered != null) reordered.close();
        budget.release(memory);
        memory = 0;
    }
}
