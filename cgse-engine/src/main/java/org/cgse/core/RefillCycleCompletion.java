// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Bounded compositions of existing actions for a loop's uncovered entry point. */
final class RefillCycleCompletion<K> implements AutoCloseable {
    private record Partial<K>(BackwardCoverability.Action<K> action, long used, int depth) {}

    private final Map<String, GraphRecipe<K>> recipes;
    private final List<BackwardCoverability.Action<K>> components;
    private final Set<K> transfers;
    private final K root;
    private final PlanningBudget budget;
    private final ArrayDeque<Partial<K>> pending = new ArrayDeque<>();
    private final List<BackwardCoverability.Action<K>> actions = new ArrayList<>();
    private Partial<K> current;
    private BackwardCoverability.Action<K> next;
    private Iterator<Map.Entry<K, BigInteger>> gains;
    private int seed, cursor, visits, composed, retained, nextDepth;
    private long nextUsed, memory;
    private boolean done;
    private SummaryComputation<K> summarizing;
    private PlanStep word;

    RefillCycleCompletion(Map<String, GraphRecipe<K>> recipes, List<BackwardCoverability.Action<K>> components,
                          Set<K> transfers, K root, PlanningBudget budget) {
        this.recipes = recipes; this.components = components; this.transfers = transfers;
        this.root = root; this.budget = budget;
        // The owner supplies a small already compiled neighborhood. Declining
        // wider support is not an exclusion proof and needs no copied catalog.
        done = components.size() > 64;
        if (!done) reserve(512);
    }

    boolean step() {
        budget.checkpoint();
        if (done) return true;
        if (summarizing != null) {
            if (!summarizing.step()) return false;
            var action = new BackwardCoverability.Action<>(word, summarizing.result());
            summarizing.close(); summarizing = null; word = null;
            if (action.summary().delta(root).signum() > 0) actions.add(action);
            else if (nextDepth < 12 && retained < 32) {
                reserve(128); pending.addLast(new Partial<>(action, nextUsed, nextDepth)); retained++;
            }
            return false;
        }
        for (int i = 0; i < 64; i++) {
            budget.check();
            if (++visits > 1024 || composed == 32 || actions.size() == 4) { done = true; return true; }
            if (seed < components.size()) {
                int index = seed++; var action = components.get(index);
                if (action.summary().delta(root).signum() < 0 && retained < 32) {
                    reserve(128); pending.addLast(new Partial<>(action, 1L << index, 1)); retained++;
                }
                continue;
            }
            if (current == null) {
                if (pending.isEmpty()) { done = true; return true; }
                current = pending.removeFirst(); cursor = 0;
            }
            if (gains == null) {
                if (cursor == components.size()) { current = null; continue; }
                int index = cursor++; if ((current.used() & 1L << index) != 0) continue;
                next = components.get(index); nextUsed = current.used() | 1L << index; nextDepth = current.depth() + 1;
                gains = current.action().summary().delta().entrySet().iterator();
                continue;
            }
            if (!gains.hasNext()) { gains = null; continue; }
            var gain = gains.next();
            if (!transfers.contains(gain.getKey())) continue;
            BigInteger produced = gain.getValue(), consumed = next.summary().delta(gain.getKey()).negate();
            if (produced.signum() <= 0 || consumed.signum() <= 0) continue;
            BigInteger gcd = produced.gcd(consumed), a = consumed.divide(gcd), b = produced.divide(gcd);
            reserve(1024L + 512L * (current.action().summary().delta().size() + next.summary().delta().size()));
            // Balance one transfer exactly, then summarize the actual ordered
            // word. Side inputs, prefix requirements, tools and coproducts stay
            // in that summary; endpoint arithmetic alone is not a witness.
            word = new PlanStep.Sequence(List.of(PlanStep.repeat(current.action().program(), a), PlanStep.repeat(next.program(), b)));
            var known = new IdentityHashMap<PlanStep, SequenceSummary<K>>(2);
            known.put(current.action().program(), current.action().summary()); known.put(next.program(), next.summary());
            composed++; summarizing = new SummaryComputation<>(word, recipes, budget, known);
            return false;
        }
        return false;
    }

    List<BackwardCoverability.Action<K>> actions() { return actions; }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    @Override public void close() {
        if (summarizing != null) { summarizing.close(); summarizing = null; }
        budget.release(memory); memory = 0; done = true;
    }
}
