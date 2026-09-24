package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resumable local sequence/ratio search; feasibility is checked by a separate witness verifier. */
public final class RegionSelection<K> {

    public record Choice<K>(PlanStep body, SequenceSummary<K> summary, BigInteger runs, Map<K, Long> seeds) {}

    private final GraphCompiler.Region<K> region;
    private final Map<K, BigInteger> demand;
    private final Map<K, Long> stock;
    private Map<K, Long> catalystStock;
    private final K target;
    private final long amount;
    private final boolean preserve, forceTarget;
    private final PlanningBudget budget;
    private final Set<K> external;
    private final Map<String, GraphRecipe<K>> byId = new LinkedHashMap<>();
    private final Set<K> produced = new LinkedHashSet<>();
    private final List<List<GraphRecipe<K>>> permutations = new ArrayList<>();
    private int phase, index, order, variant;
    private int missing, bestMissing = Integer.MAX_VALUE;
    private int absentSeedTypes, bestAbsentSeedTypes;
    private BigInteger missingAmount, bestMissingAmount;
    private BigInteger runs;
    private List<PlanStep> children;
    private PlanStep body;
    private SummaryComputation<K> computation;
    private SequenceSummary<K> summary;
    private SequenceSummary<K> unitSummary;
    private Iterator<K> keys;
    private BigInteger count;
    private Map<K, Long> reserve;
    private boolean possible;
    private Choice<K> best;
    private CatalystPolicy catalystPolicy = CatalystPolicy.MINIMAL;
    private boolean scaled;
    private int workingCopies = 1;
    private RegionOrder<K> ordering;
    private List<GraphRecipe<K>> preferredOrder;
    private final long searchStarted;

    public RegionSelection(GraphCompiler.Region<K> region, Map<K, BigInteger> demand, Map<K, Long> stock,
                           K target, long amount, boolean preserve, boolean forceTarget, Set<K> external, PlanningBudget budget, CatalystPolicy policy, Map<K, Long> catalystStock) {
        this(region, demand, stock, target, amount, preserve, forceTarget, external, budget);
        catalystPolicy = policy;
        this.catalystStock = catalystStock;
    }

    public RegionSelection(GraphCompiler.Region<K> region, Map<K, BigInteger> demand, Map<K, Long> stock,
                           K target, long amount, boolean preserve, boolean forceTarget, PlanningBudget budget) {
        this(region, demand, stock, target, amount, preserve, forceTarget, Set.of(), budget);
    }

    public RegionSelection(GraphCompiler.Region<K> region, Map<K, BigInteger> demand, Map<K, Long> stock,
                           K target, long amount, boolean preserve, boolean forceTarget, Set<K> external, PlanningBudget budget) {
        this.region = region;
        this.demand = demand;
        this.stock = stock;
        this.catalystStock = stock;
        this.target = target;
        this.amount = amount;
        this.preserve = preserve;
        this.forceTarget = forceTarget;
        this.budget = budget;
        this.external = external;
        searchStarted = budget.nodes();
    }

    public boolean step() {
        budget.check();
        budget.phase(PlanningBudget.Phase.SOLVE);
        List<GraphRecipe<K>> recipes = region.recipes();
        // A speculative local ordering must leave budget for allocation search.
        // Keep a concrete candidate even when this local search is cut short.
        // Its deficits do not prove that stock is missing: the caller must still
        // try other allocations or independently prove that no plan can start.
        if (phase != 8 && region.cyclic() && budget.nodes() - searchStarted > 32_768L + 128L * recipes.size()) {
            if (ordering != null) {
                ordering.close();
                ordering = null;
            }
            phase = 8;
            return true;
        }
        switch (phase) {
            case 0 -> {
                if (index < recipes.size()) {
                    GraphRecipe<K> recipe = recipes.get(index++);
                    byId.put(recipe.id(), recipe);
                    produced.addAll(recipe.outputs().keySet());
                } else {
                    if (recipes.size() > 2 && recipes.size() <= 6) permutations(new ArrayList<>(recipes), 0);
                    if (region.cyclic() && recipes.size() > 2) {
                        ordering = new RegionOrder<>(recipes, produced, stock, external, budget);
                        phase = 9;
                    } else phase = 1;
                }
            }
            case 1 -> {
                children = new ArrayList<>();
                scaled = false;
                workingCopies = 1;
                index = 0;
                phase = 2;
            }
            case 2 -> {
                if (index < recipes.size()) {
                    int trial = order - (preferredOrder == null ? 0 : 1);
                    GraphRecipe<K> recipe = trial < 0 ? preferredOrder.get(index) : trial < recipes.size() ?
                            recipes.get((index - trial + recipes.size()) % recipes.size()) : permutations.get(trial - recipes.size()).get(index);
                    long coefficient = recipes.size() <= 6 ? 1 + ((variant >>> (2 * index)) & 3) : 1;
                    children.add(new PlanStep.Batch(recipe.id(), coefficient));
                    index++;
                } else {
                    body = children.size() == 1 ? children.get(0) : new PlanStep.Sequence(children);
                    computation = new SummaryComputation<>(body, byId, budget);
                    phase = 3;
                }
            }
            case 3 -> {
                if (computation.step()) {
                    summary = computation.result();
                    if (!scaled) unitSummary = summary;
                    if (!scaled && region.cyclic()) {
                        scaled = true;
                        workingCopies = (int) Math.min(catalystPolicy.parallelism(), amount);
                        for (K key : produced) if (summary.required(key).signum() > 0)
                            workingCopies = (int) Math.min(workingCopies, BigInteger.valueOf(catalystStock.getOrDefault(key, 0L))
                                    .divide(summary.required(key)).add(BigInteger.valueOf(catalystPolicy.maxExtraCopies()))
                                    .min(BigInteger.valueOf(Integer.MAX_VALUE)).longValue());
                        workingCopies = Math.max(1, workingCopies);
                        if (recipes.size() > 1 && workingCopies > 1) {
                            body = parallelBody(workingCopies);
                            computation = new SummaryComputation<>(body, byId, budget);
                            return false;
                        }
                    }
                    keys = produced.iterator();
                    count = BigInteger.ZERO;
                    possible = true;
                    phase = 4;
                }
            }
            case 4 -> {
                if (keys.hasNext()) {
                    K key = keys.next();
                    BigInteger gap = demand.getOrDefault(key, BigInteger.ZERO).subtract(BigInteger.valueOf(stock.getOrDefault(key, 0L)));
                    BigInteger gain = unitSummary.delta(key);
                    if (gain.signum() > 0) count = count.max(CheckedAmounts.ceilDiv(gap, gain));
                    else if (gap.signum() > 0 && summary.required(key).signum() == 0) possible = false;
                } else {
                    if (forceTarget && produced.contains(target)) {
                        if (unitSummary.delta(target).signum() <= 0) possible = false;
                        else count = count.max(CheckedAmounts.ceilDiv(BigInteger.valueOf(amount), unitSummary.delta(target)));
                    }
                    if (!possible) {
                        nextTrial();
                        return phase == 8;
                    }
                    if (recipes.size() > 1 && count.signum() > 0 && count.compareTo(BigInteger.valueOf(workingCopies)) < 0) {
                        workingCopies = count.intValueExact();
                        body = parallelBody(workingCopies);
                        computation = new SummaryComputation<>(body, byId, budget);
                        phase = 3;
                        return false;
                    }
                    reserve = new LinkedHashMap<>();
                    keys = produced.iterator();
                    phase = 5;
                }
            }
            case 5 -> {
                if (count.signum() > 0 && region.cyclic() && preserve && keys.hasNext()) {
                    K key = keys.next();
                    if (summary.delta(key).signum() >= 0 && summary.required(key).signum() > 0) {
                        long seed = CheckedAmounts.amount(summary.required(key));
                        if (recipes.size() == 1) seed = CheckedAmounts.multiply(seed, workingCopies);
                        reserve.put(key, seed);
                        if (summary.delta(key).signum() > 0) {
                            BigInteger gap = demand.getOrDefault(key, BigInteger.ZERO).add(BigInteger.valueOf(seed))
                                    .subtract(BigInteger.valueOf(stock.getOrDefault(key, 0L)));
                            count = count.max(CheckedAmounts.ceilDiv(gap, unitSummary.delta(key)));
                        }
                    }
                } else {
                    runs = count;
                    if (recipes.size() > 1 && workingCopies > 1) {
                        BigInteger full = runs.divide(BigInteger.valueOf(workingCopies));
                        long tail = runs.remainder(BigInteger.valueOf(workingCopies)).longValueExact();
                        if (tail != 0) {
                            // Parallelism changes grouping, never the required number
                            // of cycles. A partial last wave must not charge a full
                            // wave's raw materials or force an allocation search.
                            List<PlanStep> waves = new ArrayList<>();
                            if (full.signum() > 0) waves.add(PlanStep.repeat(body, full));
                            waves.add(parallelBody(tail));
                            body = new PlanStep.Sequence(waves);
                            computation = new SummaryComputation<>(body, byId, budget);
                            runs = BigInteger.ONE;
                            phase = 7;
                            return false;
                        }
                        runs = full;
                    }
                    beginValidation();
                }
            }
            case 6 -> {
                if (keys.hasNext()) {
                    K key = keys.next();
                    BigInteger required = runs.signum() == 0 ? BigInteger.ZERO : summary.required(key)
                            .add(summary.delta(key).negate().max(BigInteger.ZERO).multiply(runs.subtract(BigInteger.ONE)));
                    BigInteger deficit = required.subtract(BigInteger.valueOf(stock.getOrDefault(key, 0L)));
                    if (!external.contains(key) && deficit.signum() > 0) {
                        missing++;
                        if (stock.getOrDefault(key, 0L) == 0) absentSeedTypes++;
                        missingAmount = missingAmount.add(deficit);
                    }
                } else {
                    if (best == null || missing < bestMissing || missing == bestMissing &&
                            (absentSeedTypes < bestAbsentSeedTypes || absentSeedTypes == bestAbsentSeedTypes && missingAmount.compareTo(bestMissingAmount) < 0)) {
                        best = new Choice<>(body, summary, runs, Map.copyOf(reserve));
                        bestMissing = missing;
                        bestAbsentSeedTypes = absentSeedTypes;
                        bestMissingAmount = missingAmount;
                    }
                    if (missing == 0) phase = 8;
                    else nextTrial();
                }
            }
            case 7 -> {
                if (computation.step()) {
                    summary = computation.result();
                    beginValidation();
                }
            }
            case 9 -> {
                if (ordering.step()) {
                    preferredOrder = ordering.result();
                    ordering.close();
                    ordering = null;
                    phase = 1;
                }
            }
            default -> {
                return true;
            }
        }
        return phase == 8;
    }

    private PlanStep parallelBody(long copies) {
        List<PlanStep> parallel = new ArrayList<>();
        for (PlanStep child : children) {
            PlanStep.Batch batch = (PlanStep.Batch) child;
            parallel.add(new PlanStep.Batch(batch.recipe(), CheckedAmounts.multiply(batch.runs(), copies)));
        }
        return new PlanStep.Sequence(parallel);
    }

    private void beginValidation() {
        keys = produced.iterator();
        missing = 0;
        absentSeedTypes = 0;
        missingAmount = BigInteger.ZERO;
        phase = 6;
    }

    private void nextTrial() {
        if (++order == region.recipes().size() + permutations.size() + (preferredOrder == null ? 0 : 1)) {
            order = 0;
            variant++;
        }
        int ratioChoices = region.cyclic() && region.recipes().size() <= 6 ? 1 << (2 * region.recipes().size()) : 1;
        phase = variant == ratioChoices ? 8 : 1;
    }

    private void permutations(List<GraphRecipe<K>> values, int at) {
        // At most six entries and 128 alternatives: this helper has a fixed bound.
        budget.check();
        if (permutations.size() + region.recipes().size() >= 128) return;
        if (at == values.size()) {
            permutations.add(List.copyOf(values));
            return;
        }
        for (int i = at; i < values.size(); i++) {
            Collections.swap(values, at, i);
            permutations(values, at + 1);
            Collections.swap(values, at, i);
        }
    }

    public Choice<K> result() {
        if (phase != 8) throw new IllegalStateException("Region search is incomplete");
        return best;
    }
}
