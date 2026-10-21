// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Exact round-trip shortcuts; they propose witnesses, never exclusion proofs. */
final class RefillCycleActions<K> implements AutoCloseable {
    private final PlanningBudget budget;
    private final Map<String, GraphRecipe<K>> recipes;
    private final K root;
    private final Iterator<GraphRecipe<K>> primitives;
    private final List<BackwardCoverability.Action<K>> components, actions;
    private Iterator<Map.Entry<K, BigInteger>> gains;
    private BackwardCoverability.Action<K> first, second;
    private int left, right, visits, added;
    private boolean initialized, done;
    private SummaryComputation<K> summarizing;
    private PlanStep word;
    private long memory;

    RefillCycleActions(Map<String, GraphRecipe<K>> recipes, List<BackwardCoverability.Action<K>> macros,
                       K root, PlanningBudget budget) {
        this.recipes = recipes; this.root = root; this.budget = budget;
        primitives = recipes.values().iterator();
        reserve(256L + 32L * (recipes.size() + macros.size()));
        components = new ArrayList<>(macros); actions = new ArrayList<>(macros);
    }

    boolean step() {
        budget.checkpoint();
        if (done) return true;
        if (summarizing != null) {
            if (!summarizing.step()) return false;
            // Every intermediate requirement is retained, even if the
            // transfer key's net balance is zero after this round trip.
            actions.add(0, new BackwardCoverability.Action<>(word, summarizing.result()));
            summarizing.close(); summarizing = null; word = null; added++;
            return false;
        }
        if (!initialized) {
            if (primitives.hasNext()) {
                var recipe = primitives.next();
                int ports = recipe.inputs().size() + recipe.outputs().size();
                budget.charge(1L + ports); reserve(256L + 384L * ports);
                components.add(new BackwardCoverability.Action<>(new PlanStep.Batch(recipe.id(), 1), SequenceSummary.recipe(recipe)));
                return false;
            }
            initialized = true;
        }
        for (int i = 0; i < 64; i++) {
            budget.check();
            if (++visits > 512 || added == 8 || left == components.size()) { done = true; return true; }
            if (gains != null) {
                if (!gains.hasNext()) { gains = null; continue; }
                var gain = gains.next();
                BigInteger produced = gain.getValue(), consumed = second.summary().delta(gain.getKey()).negate();
                if (produced.signum() <= 0 || consumed.signum() <= 0) continue;
                BigInteger gcd = produced.gcd(consumed), a = consumed.divide(gcd), b = produced.divide(gcd);
                if (first.summary().delta(root).multiply(a).add(second.summary().delta(root).multiply(b)).signum() <= 0) continue;
                // Match the intermediate exactly. Multiplicities are inverse
                // throughput ratios, not rounded firing estimates. The actual
                // words supply all side-input, tool and coproduct semantics.
                reserve(1024L + 512L * (first.summary().delta().size() + second.summary().delta().size()));
                word = new PlanStep.Sequence(List.of(PlanStep.repeat(first.program(), a), PlanStep.repeat(second.program(), b)));
                summarizing = new SummaryComputation<>(word, recipes, budget);
                return false;
            }
            if (right == components.size()) { left++; right = 0; continue; }
            first = components.get(left); second = components.get(right++);
            if (first.summary().delta(root).signum() < 0 && second.summary().delta(root).signum() > 0)
                gains = first.summary().delta().entrySet().iterator();
        }
        return false;
    }

    List<BackwardCoverability.Action<K>> actions() { return actions; }
    List<BackwardCoverability.Action<K>> components() { return components; }
    boolean hasRoundTrip() { return added != 0; }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    @Override public void close() {
        if (summarizing != null) { summarizing.close(); summarizing = null; }
        budget.release(memory); memory = 0; done = true;
    }
}
