// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/** Sufficient absence of an entity feedback path, never a state substitution.
 * Ordinary manufacture is followed through every captured input alternative.
 * Encountering any state input declines the certificate. Thus nominal high
 * durability choices alone cannot turn a finite worn tool into a growth seed.
 */
final class CapturedWearBoundary<K> implements AutoCloseable {
    private final List<CapturedCatalog.Entry<K>> entries;
    private final Map<K, List<Integer>> producers;
    private final StateFamily<K> family;
    private final PlanningBudget budget;
    private final CapturedStateReturnClosure<K> closure;
    private Iterator<K> cycles;
    private final Set<Integer> visited = new HashSet<>();
    private final Deque<Integer> pending = new ArrayDeque<>();
    private int entry, output, input, candidate, examining = -1;
    private List<Integer> dependencies = List.of();
    private int dependency;
    private long memory;
    private boolean indexed, done, certified;

    CapturedWearBoundary(List<CapturedCatalog.Entry<K>> entries, Map<K, List<Integer>> producers,
                         StateFamily<K> family, CapturedStateReturnClosure<K> closure, PlanningBudget budget) {
        this.entries = entries; this.producers = producers; this.family = family; this.budget = budget;
        this.closure = closure;
        if (!closure.complete()) done = true;
        cycles = closure.cycles();
    }

    boolean step() {
        if (done) return true;
        for (int work = 0; work < 16; work++) if (advance()) return true;
        return false;
    }

    private boolean advance() {
        if (done) return true;
        budget.compilationCheck();
        if (cycles != null) {
            if (cycles.hasNext()) {
                if (family.accepts(cycles.next())) { done = true; return true; }
            } else cycles = null;
        } else if (!indexed) {
            if (entry == entries.size()) { indexed = true; return false; }
            var recipe = entries.get(entry).recipe();
            if (output < recipe.outputs().size()) {
                if (family.accepts(recipe.outputs().get(output++).what())) add(entry);
            } else if (input < recipe.inputs().size()) {
                var choices = recipe.inputs().get(input).candidates();
                if (candidate < choices.size()) {
                    var value = choices.get(candidate++);
                    // Deterministic wear returns consume an existing entity;
                    // ordinary container returns can manufacture a new one.
                    if (value.family() == null && value.remaining() != null && family.accepts(value.remaining())) add(entry);
                } else { input++; candidate = 0; }
            } else { entry++; output = input = candidate = 0; }
        } else if (dependency < dependencies.size()) add(dependencies.get(dependency++));
        else if (examining >= 0) {
            var recipe = entries.get(examining).recipe();
            if (input == recipe.inputs().size()) { examining = -1; return false; }
            var choices = recipe.inputs().get(input).candidates();
            if (candidate < choices.size()) {
                var value = choices.get(candidate++);
                // This is only a no-feedback certificate. It does not assert
                // that different states or different state rules are equal.
                if (value.family() != null || family.accepts(value.stack().what()) ||
                        closure.returns(value.stack().what())) { done = true; return true; }
                dependencies = producers.getOrDefault(value.stack().what(), List.of()); dependency = 0;
            } else { input++; candidate = 0; }
        } else if (!pending.isEmpty()) {
            examining = pending.removeFirst(); input = candidate = 0;
        } else { certified = done = true; }
        return done;
    }

    private void add(int index) {
        if (visited.contains(index)) return;
        if (!budget.tryReserve(96)) { done = true; return; }
        memory += 96; visited.add(index); pending.addLast(index);
    }
    boolean certified() { return certified; }
    @Override public void close() {
        budget.release(memory); memory = 0; visited.clear(); pending.clear(); dependencies = List.of();
        cycles = null;
    }
}
