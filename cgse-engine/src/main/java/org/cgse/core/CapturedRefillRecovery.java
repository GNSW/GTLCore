// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Restore a verified refill through the original state model, without extra tool states. */
final class CapturedRefillRecovery<K> implements AutoCloseable {
    private final CapturedCatalog<K> catalog;
    private final List<CapturedCatalog.Entry<K>> entries;
    private final GraphPlan<K> preview;
    private final Map<K, Long> stock, seeds;
    private final Set<K> external;
    private final boolean force;
    private final CatalystPolicy catalysts;
    private final PlanningBudget budget;
    private final long started, allowance;
    private final List<StateFamily<K>> families = new ArrayList<>();
    private final Set<String> identities = new LinkedHashSet<>();
    private final Map<K, Long> supplied = new LinkedHashMap<>();
    private Iterator<Map.Entry<K, BigInteger>> missing;
    private Iterator<Map.Entry<K, Long>> inventory;
    private Map.Entry<K, BigInteger> current;
    private int entry, input, candidate, family;
    private boolean removedToolRefill, done;
    private long memory;
    private CapturedCatalog<K>.Work work;
    private GraphPlan<K> result;

    CapturedRefillRecovery(CapturedCatalog<K> catalog, List<CapturedCatalog.Entry<K>> entries,
            GraphPlan<K> preview, Map<K, Long> stock, Set<K> external, Map<K, Long> seeds,
            boolean force, CatalystPolicy catalysts, PlanningBudget budget) {
        this.catalog = catalog; this.entries = entries; this.preview = preview; this.stock = stock;
        this.external = external; this.seeds = seeds; this.force = force; this.catalysts = catalysts; this.budget = budget;
        started = budget.nodes(); allowance = Math.min(1_048_576, budget.remainingWork() / 4);
        inventory = stock.entrySet().iterator();
    }

    boolean step() {
        if (done) return true;
        budget.checkpoint();
        if (budget.nodes() - started >= allowance) { done = true; return true; }
        if (entry < entries.size()) {
            budget.compilationCheck();
            var recipe = entries.get(entry).recipe();
            if (input < recipe.inputs().size()) {
                var choices = recipe.inputs().get(input).candidates();
                if (candidate < choices.size()) {
                    var rule = choices.get(candidate++).family();
                    if (rule != null && !identities.contains(rule.id())) {
                        reserve(128); identities.add(rule.id()); families.add(rule);
                    }
                } else { input++; candidate = 0; }
            } else { entry++; input = candidate = 0; }
        } else if (inventory.hasNext()) {
            budget.compilationCheck(); var value = inventory.next(); reserve(96); supplied.put(value.getKey(), value.getValue());
        } else if (work == null) {
            budget.compilationCheck();
            if (missing == null) missing = preview.missingExact().entrySet().iterator();
            if (current == null && missing.hasNext()) { current = missing.next(); family = 0; }
            if (current != null) {
                if (family < families.size()) {
                    if (families.get(family++).accepts(current.getKey())) { removedToolRefill = true; current = null; }
                } else {
                    BigInteger available = BigInteger.valueOf(supplied.getOrDefault(current.getKey(), 0L)).add(current.getValue());
                    // A large exact preview is still useful, but this optional
                    // retry must not narrow an unrepresentable physical stock.
                    if (available.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) { done = true; return true; }
                    if (!supplied.containsKey(current.getKey())) reserve(96);
                    supplied.put(current.getKey(), available.longValueExact()); current = null;
                }
            } else if (!missing.hasNext()) {
                if (families.isEmpty()) { done = true; return true; }
                budget.note("candidate_refill", "retry_funded_materials; withheld_tool_refill=" + removedToolRefill +
                        "; allowance=" + allowance + "; negative_proofs=false");
                // Keep the full captured source domain. The nested work cannot
                // make another conditional refill or recursively retry itself.
                work = catalog.begin(preview.target(), preview.amount(), supplied, external, seeds,
                        preview.preserveSeeds(), force, budget).catalysts(catalysts);
            }
        } else if (work.step()) {
            accept(work.result().plan()); done = true;
        }
        return done;
    }

    private void accept(GraphPlan<K> plan) {
        if (!plan.feasible()) return;
        Map<K, BigInteger> deficits = new LinkedHashMap<>();
        for (var value : plan.initialExact().entrySet()) {
            budget.compilationCheck();
            if (external.contains(value.getKey())) continue;
            if (value.getValue().compareTo(BigInteger.valueOf(supplied.getOrDefault(value.getKey(), 0L))) > 0)
                throw new IllegalStateException("Refill retry borrowed unavailable stock");
            BigInteger deficit = value.getValue().subtract(BigInteger.valueOf(stock.getOrDefault(value.getKey(), 0L)));
            if (deficit.signum() > 0) deficits.put(value.getKey(), deficit);
        }
        result = new GraphPlan<>(plan.target(), plan.amount(), plan.preserveSeeds(), plan.steps(), plan.recipes(),
                plan.initialExact(), plan.seeds(), deficits, deficits.isEmpty() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL :
                GraphPlan.Result.INCOMPLETE_MODEL, budget.nodes(), plan.planningNanos());
        budget.note("candidate_refill", "state_refill_verified; missing_keys=" + deficits.size() + "; verified_physical_execution=true");
    }

    GraphPlan<K> result() { return result; }
    CompletableFuture<?> waitingFor() { return work == null ? null : work.waitingFor(); }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    public void close() { if (work != null) work.close(); work = null; budget.release(memory); memory = 0; }
}
