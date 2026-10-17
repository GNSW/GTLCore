// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Add executions to an old auxiliary assignment under the current rows.
 * Restricted failure is inconclusive; only a newly verified program escapes. */
final class CapturedProposalRepair<K> implements AutoCloseable {
    private final List<CapturedCatalog.Entry<K>> entries;
    private final Map<String, BigInteger> previous;
    private final K target;
    private final long amount, started, allowance;
    private final Map<K, Long> stock, seeds;
    private final Set<K> external;
    private final boolean preserve, force;
    private final PlanningBudget budget;
    private final List<GraphRecipe<K>> recipes = new ArrayList<>();
    private final Map<String, BigInteger> counts = new LinkedHashMap<>();
    private CapturedRecipeDomain<K> domain;
    private RecipeCountModel<K> model;
    private CountLcg search;
    private CapturedFlowWitness<K> witness;
    private GraphPlan<K> result;
    private long memory, ports;
    private int entry, matched;
    private boolean done;

    static <K> CapturedProposalRepair<K> create(List<CapturedCatalog.Entry<K>> entries, Map<String, BigInteger> previous,
            K target, long amount, Map<K, Long> stock, Set<K> external, Map<K, Long> seeds,
            boolean preserve, boolean force, PlanningBudget budget) {
        if (previous == null || previous.isEmpty() || previous.size() > 128 || entries.size() > 128 || budget.remainingWork() < 16_384)
            return null;
        var repair = new CapturedProposalRepair<>(entries, previous, target, amount, stock, external, seeds, preserve, force, budget);
        boolean retained = false;
        try {
            long bytes = 1024L + 192L * previous.size();
            for (var count : previous.values()) {
                budget.check();
                if (count.signum() < 0) return null;
                bytes += (count.bitLength() + 7L) / 8;
            }
            if (!budget.tryReserve(bytes)) return null;
            repair.memory = bytes;
            retained = true;
            return repair;
        } finally { if (!retained) repair.close(); }
    }

    private CapturedProposalRepair(List<CapturedCatalog.Entry<K>> entries, Map<String, BigInteger> previous,
            K target, long amount, Map<K, Long> stock, Set<K> external, Map<K, Long> seeds,
            boolean preserve, boolean force, PlanningBudget budget) {
        this.entries = entries; this.previous = previous; this.target = target; this.amount = amount;
        this.stock = stock; this.external = external; this.seeds = seeds; this.preserve = preserve; this.force = force; this.budget = budget;
        started = budget.nodes(); allowance = Math.min(65_536, budget.remainingWork() / 16);
    }

    boolean step() {
        if (done) return true;
        budget.checkpoint();
        if (budget.nodes() - started >= allowance) return finish("local_limit");
        try {
            if (witness != null) {
                if (!witness.step()) return false;
                result = witness.result();
                return finish(result == null ? "unexecutable" : "verified");
            }
            if (search != null) {
                if (!search.step()) return false;
                var values = search.counts();
                if (values == null) return finish("restricted_search");
                for (int i = 0; i < values.length; i++) { budget.check(); counts.put(recipes.get(i).id(), values[i]); }
                search.close(); search = null;
                model.close(); model = null;
                witness = new CapturedFlowWitness<>(recipes, counts, target, amount, stock, external, seeds, preserve, force, budget);
                return false;
            }
            if (entry < entries.size()) {
                var source = entries.get(entry);
                if (domain == null) {
                    domain = source.recipe().domain(budget);
                    if (domain.size().compareTo(BigInteger.valueOf(128 - recipes.size())) > 0) return finish("domain_limit");
                }
                if (!domain.hasNext()) { domain.close(); domain = null; entry++; return false; }
                BigInteger ordinal = domain.coveredPrefix();
                var value = domain.next();
                ports += value.slots().size() + value.outputs().size();
                if (ports > 2048) return finish("port_limit");
                long bytes = 384L + 256L * (value.slots().size() + value.outputs().size());
                if (!budget.tryReserve(bytes)) return finish("workspace");
                memory += bytes;
                String id = source.id() + "#" + ordinal;
                // Re-resolve slots and outputs after the feedback revision.
                // The old ID supplies only a lower-bound hint, never semantics.
                recipes.add(new GraphRecipe<>(id, source.binding(), value.slots(), value.outputs()));
                if (previous.containsKey(id)) matched++;
                return false;
            }
            if (matched != previous.size()) return finish("changed_domain");
            var goals = new LinkedHashMap<K, BigInteger>();
            seeds.forEach((key, value) -> { budget.check(); goals.put(key, BigInteger.valueOf(value)); });
            goals.merge(target, BigInteger.valueOf(amount), BigInteger::add);
            model = RecipeCountModel.forShell(recipes, goals, stock, external, budget);
            if (model == null) return finish("workspace");
            BigInteger[] lower = new BigInteger[recipes.size()], upper = new BigInteger[recipes.size()];
            for (int i = 0; i < lower.length; i++) { budget.check(); lower[i] = previous.getOrDefault(recipes.get(i).id(), BigInteger.ZERO); }
            // Null upper bounds stay unbounded. These speculative lower bounds
            // cannot exclude any canonical solution or enter a later request.
            search = new CountLcg(model.constraints, lower, upper, budget, Math.min(32_768, allowance / 2));
            return false;
        } catch (PlanningBudget.Exhausted limit) {
            if (limit.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw limit;
            return finish("workspace");
        } catch (ArithmeticException unrepresentable) {
            // This scout also visits unused variants. Their long-valued port
            // limits must not prevent a fresh solve on a different support.
            return finish("unrepresentable_variant");
        }
    }

    private boolean finish(String reason) {
        budget.note("candidate_state_repair", "result=" + reason + "; recipes=" + recipes.size() +
                "; work=" + (budget.nodes() - started) + "; negative_proofs=false");
        close(); return true;
    }

    GraphPlan<K> result() { return result; }

    @Override public void close() {
        if (domain != null) domain.close(); domain = null;
        if (search != null) search.close(); search = null;
        if (model != null) model.close(); model = null;
        if (witness != null) witness.close(); witness = null;
        recipes.clear(); counts.clear(); budget.release(memory); memory = 0; done = true;
    }
}
