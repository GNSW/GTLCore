package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Derives exact initial inventory and diagnostics from a concrete witness. */
final class PlanAssembly<K> {

    private final K target;
    private final long amount, started;
    private final boolean preserve;
    private final PlanStep steps;
    private final Map<String, GraphRecipe<K>> recipes;
    private final Map<K, Long> seeds, stock;
    private final Set<K> external;
    private final GraphCompiler.Compiled<K> graph;
    private final PlanningBudget budget;
    private final SummaryComputation<K> computation;
    private final PlanCountComputation counting;
    private final Map<K, Long> initial = new LinkedHashMap<>(), missing = new LinkedHashMap<>();
    private SequenceSummary<K> summary;
    private Iterator<K> keys;
    private Iterator<Map.Entry<K, Long>> amounts;
    private Map<String, Long> times;
    private GraphRecipe<K> current;
    private int phase, regionIndex, recipeIndex;
    private boolean missingSeed;
    private GraphPlan<K> result;
    private final CatalystPolicy catalystPolicy;

    PlanAssembly(K target, long amount, boolean preserve, PlanStep steps, Map<String, GraphRecipe<K>> recipes,
                 Map<K, Long> seeds, Map<K, Long> stock, Set<K> external, GraphCompiler.Compiled<K> graph, PlanningBudget budget, long started, CatalystPolicy catalystPolicy) {
        this.target = target;
        this.amount = amount;
        this.preserve = preserve;
        this.steps = steps;
        this.recipes = recipes;
        this.seeds = seeds;
        this.stock = stock;
        this.external = external;
        this.graph = graph;
        this.budget = budget;
        this.started = started;
        this.catalystPolicy = catalystPolicy;
        computation = new SummaryComputation<>(steps, recipes, budget);
        counting = new PlanCountComputation(steps);
    }

    boolean step() {
        budget.check();
        switch (phase) {
            case 0 -> {
                if (!computation.step()) return false;
                summary = computation.result();
                var all = new LinkedHashSet<>(summary.delta().keySet());
                all.add(target);
                all.addAll(seeds.keySet());
                keys = all.iterator();
                phase = 1;
            }
            case 1 -> {
                if (!keys.hasNext()) {
                    phase = 2;
                    return false;
                }
                K key = keys.next();
                BigInteger goal = BigInteger.valueOf(seeds.getOrDefault(key, 0L));
                if (key.equals(target)) goal = goal.add(BigInteger.valueOf(amount));
                long required = CheckedAmounts.amount(summary.required(key).max(goal.subtract(summary.delta(key))));
                if (required != 0) initial.put(key, required);
            }
            case 2 -> {
                if (counting.step(budget)) {
                    times = counting.result();
                    phase = 3;
                }
            }
            case 3 -> {
                if (amounts != null && amounts.hasNext()) {
                    var input = amounts.next();
                    if (current.outputs().getOrDefault(input.getKey(), 0L) >= input.getValue()) {
                        long working = Math.min(stock.getOrDefault(input.getKey(), 0L),
                                CheckedAmounts.multiply(input.getValue(), Math.min(catalystPolicy.parallelism(), times.getOrDefault(current.id(), 0L))));
                        // Growth loops can use multiple existing seeds too. Keep
                        // headroom for the verified peak before adding working stock.
                        working = Math.min(working, CheckedAmounts.amount(BigInteger.valueOf(Long.MAX_VALUE).subtract(summary.peak(input.getKey()))));
                        if (working != 0) initial.merge(input.getKey(), working, Math::max);
                    }
                } else if (regionIndex < graph.regions().size()) {
                    var region = graph.regions().get(regionIndex++);
                    if (region.cyclic() && region.recipes().size() == 1) {
                        current = region.recipes().get(0);
                        amounts = current.inputs().entrySet().iterator();
                    }
                } else {
                    amounts = initial.entrySet().iterator();
                    phase = 4;
                }
            }
            case 4 -> {
                if (amounts.hasNext()) {
                    var entry = amounts.next();
                    long available = stock.getOrDefault(entry.getKey(), 0L);
                    if (!external.contains(entry.getKey()) && entry.getValue() > available) missing.put(entry.getKey(), entry.getValue() - available);
                } else {
                    regionIndex = recipeIndex = 0;
                    keys = null;
                    phase = 5;
                }
            }
            case 5 -> {
                if (missing.isEmpty() || regionIndex == graph.regions().size()) {
                    phase = 6;
                    return false;
                }
                var region = graph.regions().get(regionIndex);
                if (!region.cyclic() || recipeIndex == region.recipes().size()) {
                    regionIndex++;
                    recipeIndex = 0;
                    keys = null;
                    return false;
                }
                current = region.recipes().get(recipeIndex);
                if (keys == null) keys = current.outputs().keySet().iterator();
                if (keys.hasNext()) {
                    K key = keys.next();
                    if (missing.containsKey(key) && (current.inputs().getOrDefault(key, 0L) > stock.getOrDefault(key, 0L) ||
                            region.recipes().size() > 1 && stock.getOrDefault(key, 0L) == 0))
                        missingSeed = true;
                } else {
                    recipeIndex++;
                    keys = null;
                }
            }
            case 6 -> {
                result = new GraphPlan<>(target, amount, preserve, steps, recipes, initial, seeds, missing,
                        missing.isEmpty() ? GraphPlan.Result.FEASIBLE : missingSeed ? GraphPlan.Result.MISSING_SEED : GraphPlan.Result.MISSING_INPUT,
                        budget.nodes(), System.nanoTime() - started);
                phase = 7;
            }
            default -> {
                return true;
            }
        }
        return result != null;
    }

    GraphPlan<K> result() {
        if (result == null) throw new IllegalStateException("Plan assembly incomplete");
        return result;
    }
}
