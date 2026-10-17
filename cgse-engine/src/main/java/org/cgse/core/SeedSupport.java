// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Necessary startup support clauses, rechecked with every omitted loan granted. */
final class SeedSupport<K> {

    private final List<GraphRecipe<K>> recipes;
    private final List<K> choices;
    private final Map<K, BigInteger> base, loans;
    private final Set<K> external, returned;
    private final K target;
    private final BigInteger amount;
    private final boolean force;
    private final PlanningBudget budget;

    SeedSupport(List<GraphRecipe<K>> recipes, List<K> choices, Map<K, BigInteger> base, Map<K, BigInteger> loans,
                Set<K> external, Set<K> mandatory, K target, long amount, boolean force, PlanningBudget budget) {
        this.recipes = recipes;
        this.choices = choices;
        this.base = base;
        this.loans = loans;
        this.external = external;
        returned = new HashSet<>(choices);
        returned.addAll(mandatory);
        this.target = target;
        this.amount = BigInteger.valueOf(amount);
        this.force = force;
        this.budget = budget;
    }

    BitSet conflict(BitSet selected) {
        Map<K, BigInteger> initial = initial(selected);
        Set<K> unlimited = closure(initial);
        if (!blocked(initial, unlimited)) return null;
        BitSet cut = new BitSet();
        for (int i = 0; i < choices.size(); i++) {
            budget.check();
            K key = choices.get(i);
            if (!selected.get(i) && !unlimited.contains(key) && loans.get(key).compareTo(initial.getOrDefault(key, BigInteger.ZERO)) > 0)
                cut.set(i);
        }
        // Recompute from original arcs, with every source of every omitted
        // seed enabled. This checks the scope of the clause, not just the
        // failed assignment that suggested it.
        if (!verified(cut)) throw new IllegalStateException("Invalid startup support explanation");
        long started = budget.threadWork();
        for (int i = cut.nextSetBit(0); i >= 0 && budget.threadWork() - started < 8192; i = cut.nextSetBit(i + 1)) {
            cut.clear(i);
            if (!verified(cut)) cut.set(i);
        }
        return cut;
    }

    boolean verified(BitSet cut) {
        BitSet allowed = new BitSet();
        allowed.set(0, choices.size());
        allowed.andNot(cut);
        Map<K, BigInteger> initial = initial(allowed);
        return blocked(initial, closure(initial));
    }

    private Map<K, BigInteger> initial(BitSet selected) {
        var result = new LinkedHashMap<>(base);
        for (int i = selected.nextSetBit(0); i >= 0; i = selected.nextSetBit(i + 1)) result.put(choices.get(i), loans.get(choices.get(i)));
        return result;
    }

    private boolean blocked(Map<K, BigInteger> initial, Set<K> unlimited) {
        BigInteger reserve = force || returned.contains(target) ? initial.getOrDefault(target, BigInteger.ZERO) : BigInteger.ZERO;
        return !unlimited.contains(target) && initial.getOrDefault(target, BigInteger.ZERO).compareTo(reserve.add(amount)) < 0;
    }

    private Set<K> closure(Map<K, BigInteger> initial) {
        Set<K> unlimited = new HashSet<>(external);
        boolean changed;
        do {
            changed = false;
            for (var recipe : recipes) {
                boolean enabled = true;
                for (var input : recipe.inputs().entrySet()) {
                    budget.check();
                    if (!unlimited.contains(input.getKey()) && initial.getOrDefault(input.getKey(), BigInteger.ZERO)
                            .compareTo(BigInteger.valueOf(input.getValue())) < 0) {
                        enabled = false;
                        break;
                    }
                }
                // Outputs are optimistic unbounded supplies; inputs are never
                // spent. Failure of this relaxation is a necessary condition.
                if (enabled) for (K output : recipe.outputs().keySet()) {
                    budget.check();
                    changed |= unlimited.add(output);
                }
            }
        } while (changed);
        return unlimited;
    }
}
