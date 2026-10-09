// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Global source/order search for returned startup loans, with explicit optimality bounds. */
final class SeedOptimization<K> implements AutoCloseable {

    private final GraphCompiler<K> compiler;
    private final GraphPlan<K> original;
    private GraphPlan<K> best, candidate;
    private final Map<K, Long> stock, mandatory;
    private final Set<K> external;
    private final Set<String> excluded;
    private final boolean force;
    private final PlanningBudget budget;
    private final long allowance;
    private long work, stepStarted;
    private final Map<String, GraphRecipe<K>> recipes = new LinkedHashMap<>();
    private final Deque<K> pending = new ArrayDeque<>();
    private final Set<K> discovered = new HashSet<>(), produced = new LinkedHashSet<>();
    private final Map<K, BigInteger> raw = new LinkedHashMap<>();
    private final List<K> choices = new ArrayList<>();
    private List<K> quantityKeys;
    private final List<BackwardCoverability.Action<K>> macros = new ArrayList<>();
    private SummaryComputation<K> summarizing;
    private PlanCountComputation counting;
    private Map<String, GraphRecipe<K>> usedRecipes;
    private SequenceSummary<K> summary;
    private BackwardCoverability<K> searching;
    private PlanVerification<K> verifying;
    private PlanStep program;
    private SeedSupport<K> support;
    private final List<ExactLinearProgram.Constraint> supportRows = new ArrayList<>();
    private final Set<BitSet> supportCuts = new HashSet<>();
    private CountBoolean selecting;
    private CountDomainSearch learnedSelection;
    private CountHittingSet supportPricing;
    private int pricedSupports;
    private long supportPricingWork;
    private List<ExactLinearProgram.Constraint> selectionRows;
    private BitSet selected;
    private boolean enumerateSupports;
    private int supportPrunes;
    private int supportLowerBound;
    private int phase, cardinality, quantityIndex, trials, lowerBound;
    private int unresolvedCardinality = Integer.MAX_VALUE;
    private int[] combination;
    private boolean nextLevel, cardinalityProven, amountsProven = true, complete;
    private BigInteger low, high, middle;
    private Map<K, BigInteger> supplied;
    private long memory;
    private final boolean flexibleMaterials;
    private final Set<K> purchased = new LinkedHashSet<>();
    private final List<GraphPlan<K>> options = new ArrayList<>();

    SeedOptimization(GraphCompiler<K> compiler, GraphPlan<K> plan, Map<K, Long> stock, Map<K, Long> mandatory,
                     Set<K> external, Set<String> excluded, boolean force, PlanningBudget budget) {
        this(compiler, plan, stock, mandatory, external, excluded, force, budget, false);
    }

    SeedOptimization(GraphCompiler<K> compiler, GraphPlan<K> plan, Map<K, Long> stock, Map<K, Long> mandatory,
                     Set<K> external, Set<String> excluded, boolean force, PlanningBudget budget, boolean flexibleMaterials) {
        this.compiler = compiler;
        this.flexibleMaterials = flexibleMaterials;
        original = best = plan;
        this.stock = stock;
        this.mandatory = mandatory;
        this.external = external;
        this.excluded = excluded;
        this.force = force;
        this.budget = budget;
        allowance = Math.min(262_144, budget.remainingWork() / 8);
        pending.add(plan.target());
        pending.addAll(mandatory.keySet());
        lowerBound = mandatory.size();
    }

    boolean step() {
        stepStarted = budget.threadWork();
        try {
            return advance();
        } finally {
            work += budget.threadWork() - stepStarted;
        }
    }

    private long usedWork() {
        return work + budget.threadWork() - stepStarted;
    }

    private boolean advance() {
        if (complete) return true;
        budget.check();
        if (usedWork() >= allowance) return finish("budget");
        switch (phase) {
            case 0 -> {
                if (!pending.isEmpty()) {
                    K key = pending.removeFirst();
                    if (!discovered.add(key)) return false;
                    for (var recipe : compiler.producers(key)) {
                        budget.check();
                        if (excluded.contains(recipe.id()) || recipes.containsKey(recipe.id())) continue;
                        if (recipe.batchSensitiveInputs()) return finish("batch_sensitive_configuration");
                        long bytes = 192L + 96L * (recipe.inputs().size() + recipe.outputs().size());
                        if (!budget.tryReserve(bytes)) return finish("memory");
                        memory += bytes;
                        recipes.put(recipe.id(), recipe);
                        pending.addAll(recipe.inputs().keySet());
                        produced.addAll(recipe.outputs().keySet());
                    }
                } else {
                    summarizing = new SummaryComputation<>(original.steps(), original.recipes(), budget);
                    phase = 1;
                }
            }
            case 1 -> {
                if (!summarizing.step()) return false;
                summary = summarizing.result();
                macros.add(new BackwardCoverability.Action<>(original.steps(), summary));
                summarizing = null;
                if (flexibleMaterials) for (var recipe : recipes.values()) for (K key : recipe.inputs().keySet()) {
                    budget.check();
                    if (!produced.contains(key) && !external.contains(key) && !mandatory.containsKey(key) && !key.equals(original.target())) purchased.add(key);
                }
                // Net consumables retain their inventory/funded-preview limits.
                // Every other produced startup input is a loan: selected or
                // not, it may not be silently relabelled as a spent raw material.
                for (K key : produced) if (!external.contains(key) && summary.delta(key).signum() >= 0 && !mandatory.containsKey(key))
                    if (!original.feasible() || stock.getOrDefault(key, 0L) > 0) choices.add(key);
                choices.sort(Comparator.comparing(key -> summary.required(key).signum() == 0));
                stock.forEach((key, value) -> raw.put(key, BigInteger.valueOf(value)));
                original.missingExact().forEach((key, value) -> raw.merge(key, value, BigInteger::add));
                for (K key : produced) if (summary.delta(key).signum() >= 0) raw.remove(key);
                mandatory.keySet().forEach(raw::remove);
                var base = new LinkedHashMap<>(raw);
                mandatory.forEach((key, value) -> base.put(key, BigInteger.valueOf(Math.max(value, stock.getOrDefault(key, 0L)))));
                Map<K, BigInteger> loans = new LinkedHashMap<>();
                choices.forEach(key -> loans.put(key, original.feasible() ? BigInteger.valueOf(stock.getOrDefault(key, 0L)) : ExactAmounts.LONG_MAX));
                support = new SeedSupport<>(List.copyOf(recipes.values()), choices, base, loans, searchExternal(), mandatory.keySet(),
                        original.target(), original.amount(), force, budget);
                combination = new int[0];
                phase = 2;
            }
            case 2 -> {
                if (priceSupports()) return false;
                if (supportLowerBound > cardinality) {
                    cardinality = supportLowerBound;
                    nextLevel = false;
                    combination = new int[cardinality];
                    for (int i = 0; i < cardinality; i++) combination[i] = i;
                }
                int bestFree = (int) best.seeds().keySet().stream().filter(key -> !mandatory.containsKey(key)).count();
                if (cardinality >= bestFree) {
                    cardinalityProven = unresolvedCardinality >= bestFree || supportLowerBound >= bestFree;
                    if (cardinalityProven) lowerBound = best.seeds().size();
                    quantityKeys = new ArrayList<>(best.seeds().keySet());
                    phase = 5;
                    return false;
                }
                if (nextLevel) {
                    nextLevel = false;
                    cardinality++;
                    if (unresolvedCardinality >= cardinality) lowerBound = mandatory.size() + cardinality;
                    combination = new int[cardinality];
                    for (int i = 0; i < combination.length; i++) combination[i] = i;
                    return false;
                }
                if (cardinality > 0 && !enumerateSupports) {
                    if (selecting == null && learnedSelection == null) {
                        var rows = new ArrayList<>(supportRows);
                        Map<Integer, BigInteger> positive = new LinkedHashMap<>(), negative = new LinkedHashMap<>();
                        for (int i = 0; i < choices.size(); i++) {
                            positive.put(i, BigInteger.ONE);
                            negative.put(i, BigInteger.ONE.negate());
                        }
                        rows.add(new ExactLinearProgram.Constraint(positive, BigInteger.valueOf(cardinality)));
                        rows.add(new ExactLinearProgram.Constraint(negative, BigInteger.valueOf(-cardinality)));
                        BigInteger[] lower = new BigInteger[choices.size()], upper = new BigInteger[choices.size()];
                        Arrays.fill(lower, BigInteger.ZERO);
                        Arrays.fill(upper, BigInteger.ONE);
                        selecting = new CountBoolean(rows, lower, upper, budget);
                        selectionRows = rows;
                    }
                    BigInteger[] witness;
                    boolean closed;
                    if (learnedSelection != null) {
                        if (!learnedSelection.step()) return false;
                        witness = learnedSelection.counts();
                        closed = learnedSelection.infeasible();
                        learnedSelection.close();
                        learnedSelection = null;
                    } else {
                        if (!selecting.step()) return false;
                        witness = selecting.counts();
                        closed = selecting.infeasible();
                        selecting.close();
                        selecting = null;
                        if (witness == null && !closed) {
                            BigInteger[] lower = new BigInteger[choices.size()], upper = new BigInteger[choices.size()];
                            Arrays.fill(lower, BigInteger.ZERO);
                            Arrays.fill(upper, BigInteger.ONE);
                            learnedSelection = new CountDomainSearch(selectionRows, lower, upper, budget, 32768);
                            return false;
                        }
                    }
                    selectionRows = null;
                    if (witness == null) {
                        if (closed) nextLevel = true;
                        else {
                            enumerateSupports = true;
                            combination = new int[cardinality];
                            for (int i = 0; i < cardinality; i++) combination[i] = i;
                        }
                        return false;
                    }
                    combination = java.util.stream.IntStream.range(0, witness.length).filter(i -> witness[i].signum() > 0).toArray();
                }
                selected = new BitSet();
                for (int i : combination) selected.set(i);
                BitSet cut = support.conflict(selected);
                if (cut != null) {
                    addSupportCut(cut);
                    supportPrunes++;
                    if (cardinality == 0 || enumerateSupports) advanceCombination();
                    return false;
                }
                supplied = new LinkedHashMap<>(raw);
                mandatory.forEach((key, value) -> supplied.put(key, BigInteger.valueOf(Math.max(value, stock.getOrDefault(key, 0L)))));
                for (int i : combination) {
                    K key = choices.get(i);
                    supplied.put(key, original.feasible() ? BigInteger.valueOf(stock.getOrDefault(key, 0L)) : ExactAmounts.LONG_MAX);
                }
                if (cardinality == 0 || enumerateSupports) advanceCombination();
                beginSearch();
            }
            case 3 -> {
                if (!searching.step()) return false;
                var result = searching.result();
                if (result == BackwardCoverability.Result.WITNESS) {
                    program = searching.witness();
                    counting = new PlanCountComputation(program);
                    summarizing = new SummaryComputation<>(program, recipes, budget);
                    phase = 4;
                } else if (quantityKeys == null) {
                    if (result != BackwardCoverability.Result.CLOSED) {
                        unresolvedCardinality = Math.min(unresolvedCardinality, cardinality);
                        excludeSupport();
                    } else {
                        // If the largest allowed loan in this support cannot
                        // work, neither can any subset. Every future solution
                        // must add at least one loan outside this support.
                        BitSet outside = new BitSet();
                        outside.set(0, choices.size());
                        outside.andNot(selected);
                        addSupportCut(outside);
                    }
                    phase = 2;
                } else {
                    if (result == BackwardCoverability.Result.CLOSED) low = middle.add(BigInteger.ONE);
                    else {
                        amountsProven = false;
                        quantityIndex++;
                        low = high = null;
                    }
                    phase = 5;
                }
                searching.close();
                searching = null;
            }
            case 4 -> {
                if (verifying != null) {
                    if (!verifying.step()) return false;
                    best = candidate;
                    if (options.stream().noneMatch(old -> SeedPortfolio.dominates(old, candidate))) {
                        options.removeIf(old -> SeedPortfolio.dominates(candidate, old));
                        if (options.size() < 32) options.add(candidate);
                    }
                    verifying = null;
                    if (quantityKeys != null) {
                        if (!best.seeds().containsKey(quantityKeys.get(quantityIndex))) {
                            quantityKeys = new ArrayList<>(best.seeds().keySet());
                            quantityIndex = 0;
                            low = high = null;
                            lowerBound = Math.min(lowerBound, best.seeds().size());
                        } else high = BigInteger.valueOf(best.seeds().get(quantityKeys.get(quantityIndex)));
                    }
                    phase = quantityKeys == null ? 2 : 5;
                    return false;
                }
                if (counting != null) {
                    if (!counting.step(budget)) return false;
                    usedRecipes = new LinkedHashMap<>();
                    counting.result().forEach((id, count) -> {
                        if (count.signum() > 0) usedRecipes.put(id, recipes.get(id));
                    });
                    counting = null;
                    return false;
                }
                if (!summarizing.step()) return false;
                candidate = assemble(summarizing.result());
                summarizing = null;
                if (candidate == null) {
                    if (quantityKeys == null) {
                        unresolvedCardinality = Math.min(unresolvedCardinality, cardinality);
                        excludeSupport();
                    }
                    amountsProven = false;
                    if (quantityKeys != null) {
                        quantityIndex++;
                        low = high = null;
                    }
                    phase = quantityKeys == null ? 2 : 5;
                } else verifying = new PlanVerification<>(new GraphPlan<>(candidate.target(), candidate.amount(), true,
                        candidate.steps(), candidate.recipes(), candidate.initialExact(), candidate.seeds(), Map.of(),
                        GraphPlan.Result.FEASIBLE, budget.nodes(), 0), budget);
            }
            case 5 -> {
                if (quantityIndex == quantityKeys.size()) return finish("searched");
                K key = quantityKeys.get(quantityIndex);
                if (low == null) {
                    // Zero is a real alternative for an optional seed. If a
                    // smaller support was previously undecided, skipping zero
                    // would falsely certify a Pareto-minimal quantity vector.
                    low = BigInteger.valueOf(mandatory.getOrDefault(key, 0L));
                    high = BigInteger.valueOf(best.seeds().get(key));
                }
                if (low.compareTo(high) >= 0) {
                    quantityIndex++;
                    low = high = null;
                    return false;
                }
                middle = low.add(high).shiftRight(1);
                supplied = new LinkedHashMap<>(raw);
                best.seeds().forEach((seed, value) -> supplied.put(seed, BigInteger.valueOf(value)));
                supplied.put(key, middle);
                beginSearch();
            }
            default -> throw new IllegalStateException("Invalid seed optimization phase");
        }
        return false;
    }

    private void beginSearch() {
        if (quantityKeys == null) budget.note("seed_trial", "types=" + cardinality + "; selected=" + supplied.keySet().stream().filter(key -> !raw.containsKey(key)).toList());
        Map<K, BigInteger> goals = new LinkedHashMap<>();
        supplied.forEach((key, value) -> {
            if (!raw.containsKey(key)) goals.put(key, value);
        });
        BigInteger targetReserve = goals.getOrDefault(original.target(), BigInteger.ZERO);
        if (force) targetReserve = targetReserve.max(supplied.getOrDefault(original.target(), BigInteger.ZERO));
        goals.put(original.target(), targetReserve.add(BigInteger.valueOf(original.amount())));
        searching = new BackwardCoverability<>(List.copyOf(recipes.values()), supplied, goals, searchExternal(), macros,
                budget, Math.max(0, Math.min(32_768, allowance - usedWork())));
        trials++;
        phase = 3;
    }

    private void advanceCombination() {
        int i = combination.length - 1;
        while (i >= 0 && combination[i] == choices.size() - combination.length + i) i--;
        if (i < 0) {
            nextLevel = true;
            return;
        }
        combination[i]++;
        for (int j = i + 1; j < combination.length; j++) combination[j] = combination[j - 1] + 1;
    }

    private void addSupportCut(BitSet cut) {
        if (!supportCuts.add((BitSet) cut.clone())) return;
        Map<Integer, BigInteger> terms = new LinkedHashMap<>();
        for (int i = cut.nextSetBit(0); i >= 0; i = cut.nextSetBit(i + 1)) terms.put(i, BigInteger.ONE.negate());
        supportRows.add(new ExactLinearProgram.Constraint(terms, BigInteger.ONE.negate()));
        long bytes = 192L + 96L * terms.size();
        budget.reserve(bytes);
        memory += bytes;
        // Pairwise disjoint necessary supports demand distinct seed types.
        // This is a checked lower bound, not an estimate from a failed search.
        BitSet occupied = new BitSet();
        int packed = 0;
        for (var clause : supportCuts.stream().sorted(Comparator.comparingInt(BitSet::cardinality)).toList()) {
            budget.check();
            if (!clause.isEmpty() && !clause.intersects(occupied)) {
                occupied.or(clause);
                packed++;
            }
        }
        supportLowerBound = Math.max(supportLowerBound, packed);
        lowerBound = Math.max(lowerBound, mandatory.size() + supportLowerBound);
    }

    /**
     * Strengthen the seed-type lower bound using only PROVED necessary
     * supports. Search-only exclusions from UNKNOWN trials never enter this
     * master, and its solution is not an executable seed plan.
     */
    private boolean priceSupports() {
        long allowance = Math.min(16384, this.allowance / 16);
        if (supportPricing == null) {
            if (choices.size() > 64 || supportCuts.size() < 3 || supportCuts.size() > 128 ||
                    pricedSupports == supportCuts.size() || supportPricingWork + 256 >= allowance)
                return false;
            pricedSupports = supportCuts.size();
            var weights = new BigInteger[choices.size()];
            Arrays.fill(weights, BigInteger.ONE);
            long before = budget.threadWork();
            try {
                supportPricing = new CountHittingSet(List.copyOf(supportCuts), weights, budget, Math.min(4096, allowance - supportPricingWork));
            } finally {
                supportPricingWork += budget.threadWork() - before;
            }
        }
        long before = budget.threadWork();
        try {
            if (!supportPricing.step()) return true;
            supportLowerBound = Math.max(supportLowerBound, supportPricing.lowerBound().intValueExact());
            lowerBound = Math.max(lowerBound, mandatory.size() + supportLowerBound);
            supportPricing.close();
            supportPricing = null;
            return false;
        } finally {
            supportPricingWork += budget.threadWork() - before;
        }
    }

    private void excludeSupport() {
        // Search-only blocking of an unresolved trial. The unresolved
        // cardinality remains visible, so master exhaustion cannot certify it.
        Map<Integer, BigInteger> terms = new LinkedHashMap<>();
        for (int i = 0; i < choices.size(); i++) terms.put(i, selected.get(i) ? BigInteger.ONE : BigInteger.ONE.negate());
        supportRows.add(new ExactLinearProgram.Constraint(terms, BigInteger.valueOf(selected.cardinality() - 1L)));
        long bytes = 192L + 96L * terms.size();
        budget.reserve(bytes);
        memory += bytes;
    }

    private GraphPlan<K> assemble(SequenceSummary<K> value) {
        if (force && value.delta(original.target()).compareTo(BigInteger.valueOf(original.amount())) < 0) return null;
        Map<K, Long> seeds = new LinkedHashMap<>(mandatory);
        for (K key : value.keys()) if (!external.contains(key) && value.required(key).signum() > 0) {
            if (!raw.containsKey(key) && !purchased.contains(key) && value.delta(key).signum() < 0) return null;
            if (produced.contains(key) && value.delta(key).signum() >= 0) {
                if (value.required(key).compareTo(ExactAmounts.LONG_MAX) > 0) return null;
                seeds.merge(key, value.required(key).longValueExact(), Math::max);
                // A different program can turn an old net consumable into a
                // returned catalyst. Count it; never call that a zero-seed plan.
                if (raw.containsKey(key) && !mandatory.containsKey(key)) return null;
            }
        }
        if (quantityKeys != null && !best.seeds().keySet().containsAll(seeds.keySet())) return null;
        Map<K, BigInteger> initial = new LinkedHashMap<>(), missing = new LinkedHashMap<>();
        Set<K> keys = value.keys();
        keys.addAll(seeds.keySet());
        keys.add(original.target());
        for (K key : keys) {
            budget.check();
            BigInteger goal = BigInteger.valueOf(seeds.getOrDefault(key, 0L));
            if (key.equals(original.target())) goal = goal.add(BigInteger.valueOf(original.amount()));
            BigInteger need = value.required(key).max(goal.subtract(value.delta(key)));
            if (!external.contains(key) && !purchased.contains(key) && need.compareTo(supplied.getOrDefault(key, BigInteger.ZERO)) > 0) return null;
            if (need.signum() > 0) initial.put(key, need);
            BigInteger gap = need.subtract(BigInteger.valueOf(stock.getOrDefault(key, 0L)));
            if (!external.contains(key) && gap.signum() > 0) missing.put(key, gap);
        }
        if (seeds.size() > best.seeds().size() || original.feasible() && !missing.isEmpty()) return null;
        return new GraphPlan<>(original.target(), original.amount(), true, program, usedRecipes, initial, seeds, missing,
                missing.isEmpty() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : GraphPlan.Result.MISSING_INPUT, budget.nodes(), original.planningNanos());
    }

    private boolean finish(String detail) {
        if (!detail.equals("searched")) {
            cardinalityProven = false;
            amountsProven = false;
        }
        complete = true;
        best = best.withSeedOptimality(new GraphPlan.SeedOptimality(Math.min(lowerBound, best.seeds().size()),
                best.seeds().size(), cardinalityProven, amountsProven, !original.feasible(), flexibleMaterials));
        budget.note("global_seeds", detail + "; types=" + original.seeds().size() + "->" + best.seeds().size() +
                "; lower_bound=" + lowerBound + "; cardinality_proven=" + cardinalityProven + "; amounts_proven=" + amountsProven +
                "; all_source_recipes=" + recipes.size() + "; trials=" + trials + "; work=" + usedWork());
        budget.note("seed_support", "checked_cuts=" + supportCuts.size() + "; startup_prunes=" + supportPrunes + "; pricing_work=" + supportPricingWork + "; enumerating=" + enumerateSupports);
        close();
        return true;
    }

    GraphPlan<K> result() {
        return best;
    }

    List<GraphPlan<K>> options() {
        return List.copyOf(options);
    }

    private Set<K> searchExternal() {
        if (purchased.isEmpty()) return external;
        Set<K> result = new LinkedHashSet<>(external);
        result.addAll(purchased);
        return result;
    }

    boolean cardinalityProven() {
        return cardinalityProven;
    }

    boolean amountsProven() {
        return amountsProven;
    }

    int lowerBound() {
        return lowerBound;
    }

    @Override
    public void close() {
        if (supportPricing != null) supportPricing.close();
        supportPricing = null;
        if (learnedSelection != null) learnedSelection.close();
        learnedSelection = null;
        selectionRows = null;
        if (summarizing != null) summarizing.close();
        summarizing = null;
        if (selecting != null) selecting.close();
        selecting = null;
        if (searching != null) searching.close();
        searching = null;
        budget.release(memory);
        memory = 0;
    }
}
