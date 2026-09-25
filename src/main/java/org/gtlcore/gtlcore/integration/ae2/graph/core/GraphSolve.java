package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Retains demand propagation and the current region's search across work slices. */
final class GraphSolve<K> {

    private final GraphCompiler.Compiled<K> graph;
    private final K target;
    private final long amount, started;
    private final Map<K, Long> stock;
    private final Map<K, Long> catalystStock;
    private final Map<K, Long> requiredSeeds;
    private final Set<K> external;
    private final boolean preserve, forceCraft;
    private final PlanningBudget budget;
    private final Map<K, BigInteger> demand = new LinkedHashMap<>();
    private final Map<K, Long> seeds = new LinkedHashMap<>();
    private final List<PlanStep> reversed = new ArrayList<>();
    private boolean targetProduced;
    private int regionIndex;
    private RegionSelection<K> selection;
    private RegionSelection.Choice<K> selected;
    private GraphRecipe<K> ordinary;
    private Iterator<Map.Entry<K, Long>> ordinaryEntries;
    private BigInteger ordinaryCount;
    private int ordinaryPhase;
    private Iterator<K> applying;
    private PlanAssembly<K> assembly;
    private GraphPlan<K> result;
    private final CatalystPolicy catalystPolicy;

    GraphSolve(GraphCompiler.Compiled<K> graph, K target, long amount, Map<K, Long> stock, Set<K> external, Map<K, Long> requiredSeeds,
               boolean preserve, boolean forceCraft, PlanningBudget budget, long started, CatalystPolicy catalystPolicy, Map<K, Long> catalystStock) {
        this.graph = graph;
        this.target = target;
        this.amount = amount;
        this.stock = stock;
        this.catalystStock = catalystStock;
        this.requiredSeeds = requiredSeeds;
        this.external = external;
        this.preserve = preserve;
        this.forceCraft = forceCraft;
        this.budget = budget;
        this.started = started;
        this.catalystPolicy = catalystPolicy;
        targetProduced = !forceCraft;
        demand.put(target, BigInteger.valueOf(amount));
        requiredSeeds.forEach((key, count) -> demand.merge(key, BigInteger.valueOf(count), BigInteger::add));
        seeds.putAll(requiredSeeds);
    }

    boolean step() {
        budget.check();
        budget.phase(PlanningBudget.Phase.SOLVE);
        if (result != null) return true;
        if (assembly != null) {
            if (assembly.step()) result = assembly.result();
            return result != null;
        }
        if (ordinary != null) {
            // Like MAX_FAST's compiled ordinary-node path, advance a small batch
            // of deterministic arithmetic without re-entering the whole planner
            // for every input/output. Keep the frontier and per-operation limits.
            for (int operation = 0; operation < 32 && ordinary != null; operation++) {
                budget.check();
                ordinaryStep();
            }
            return false;
        }
        if (applying != null) {
            if (applying.hasNext()) {
                K key = applying.next();
                BigInteger runs = selected.runs();
                BigInteger change = selected.summary().delta(key);
                BigInteger required = selected.summary().required(key).add(change.negate().max(BigInteger.ZERO).multiply(runs.subtract(BigInteger.ONE)));
                long additionalSeed = Math.max(0, selected.seeds().getOrDefault(key, 0L) - requiredSeeds.getOrDefault(key, 0L));
                BigInteger goal = demand.getOrDefault(key, BigInteger.ZERO).add(BigInteger.valueOf(additionalSeed));
                demand.put(key, required.max(goal.subtract(change.multiply(runs))).max(BigInteger.ZERO));
                return false;
            }
            if (selected.body() instanceof PlanStep.Batch batch) reversed.add(PlanStep.batch(batch.recipe(), selected.runs().multiply(BigInteger.valueOf(batch.runs()))));
            else reversed.add(PlanStep.repeat(selected.body(), selected.runs()));
            applying = null;
            selected = null;
            return false;
        }
        if (selection != null) {
            if (!selection.step()) return false;
            selected = selection.result();
            selection = null;
            if (selected == null) {
                var region = graph.regions().get(regionIndex - 1);
                budget.note("region", "no_witness; index=" + (regionIndex - 1) + "; recipes=" + region.recipes().size() +
                        "; cyclic=" + region.cyclic() + "; ids=" + region.recipes().stream().limit(6).map(GraphRecipe::id).toList());
                result = failure(GraphPlan.Result.UNKNOWN);
                return true;
            }
            if (selected.runs().signum() == 0) {
                selected = null;
                return false;
            }
            if (selected.summary().delta(target).signum() > 0) targetProduced = true;
            selected.seeds().forEach((key, count) -> seeds.merge(key, count, Math::max));
            applying = selected.summary().delta().keySet().iterator();
            return false;
        }
        if (regionIndex < graph.regions().size()) {
            var region = graph.regions().get(regionIndex++);
            if (!region.cyclic() && region.recipes().size() == 1 &&
                    Collections.disjoint(region.recipes().get(0).inputs().keySet(), region.recipes().get(0).outputs().keySet())) {
                ordinary = region.recipes().get(0);
                ordinaryEntries = ordinary.outputs().entrySet().iterator();
                ordinaryCount = BigInteger.ZERO;
                ordinaryPhase = 0;
            } else selection = new RegionSelection<>(region, demand, stock, target, amount,
                    preserve, forceCraft && !targetProduced, external, budget, catalystPolicy, catalystStock);
        } else if (!targetProduced) {
            budget.note("selected_graph", "target_not_produced; target=" + target + "; recipes=" + graph.recipes().size() + "; force_craft=" + forceCraft);
            result = failure(GraphPlan.Result.UNKNOWN);
        } else {
            Collections.reverse(reversed);
            assembly = new PlanAssembly<>(target, amount, preserve, new PlanStep.Sequence(reversed), graph.recipes(),
                    seeds, stock, external, graph, budget, started, catalystPolicy);
        }
        return result != null;
    }

    /** Exact one-recipe DAG propagation; the final serial witness is still independently verified. */
    private void ordinaryStep() {
        if (ordinaryEntries.hasNext()) {
            var entry = ordinaryEntries.next();
            K key = entry.getKey();
            BigInteger quantity = BigInteger.valueOf(entry.getValue());
            if (ordinaryPhase == 0) {
                BigInteger gap = demand.getOrDefault(key, BigInteger.ZERO).subtract(BigInteger.valueOf(stock.getOrDefault(key, 0L)));
                ordinaryCount = ordinaryCount.max(CheckedAmounts.ceilDiv(gap, quantity));
                if (forceCraft && !targetProduced && key.equals(target))
                    ordinaryCount = ordinaryCount.max(CheckedAmounts.ceilDiv(BigInteger.valueOf(amount), quantity));
            } else if (ordinaryPhase == 1) demand.merge(key, quantity.multiply(ordinaryCount), BigInteger::add);
            else demand.put(key, demand.getOrDefault(key, BigInteger.ZERO).subtract(quantity.multiply(ordinaryCount)).max(BigInteger.ZERO));
            return;
        }
        if (ordinaryPhase == 0) {
            if (ordinaryCount.signum() == 0) {
                ordinary = null;
                return;
            }
            ordinaryEntries = ordinary.inputs().entrySet().iterator();
            ordinaryPhase = 1;
        } else if (ordinaryPhase == 1) {
            ordinaryEntries = ordinary.outputs().entrySet().iterator();
            ordinaryPhase = 2;
        } else {
            if (ordinary.outputs().containsKey(target)) targetProduced = true;
            reversed.add(PlanStep.batch(ordinary.id(), ordinaryCount));
            ordinary = null;
        }
    }

    private GraphPlan<K> failure(GraphPlan.Result reason) {
        return new GraphPlan<>(target, amount, preserve, new PlanStep.Sequence(List.of()), Map.of(), Map.of(), Map.of(), Map.of(), reason,
                budget.nodes(), System.nanoTime() - started);
    }

    GraphPlan<K> result() {
        if (result == null) throw new IllegalStateException("Solve incomplete");
        return result;
    }
}
