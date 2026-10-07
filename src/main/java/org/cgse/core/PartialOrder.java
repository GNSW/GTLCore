package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Strong diamonds for sequential programs, including enabledness and inventory peaks. */
final class PartialOrder<K> {

    private final List<SequenceSummary<K>> actions;
    private final PlanningBudget budget;
    private final BitSet[] checked, independent;

    PartialOrder(List<SequenceSummary<K>> actions, PlanningBudget budget) {
        this.actions = actions;
        this.budget = budget;
        checked = new BitSet[actions.size()];
        independent = new BitSet[actions.size()];
        for (int i = 0; i < actions.size(); i++) {
            checked[i] = new BitSet();
            independent[i] = new BitSet();
        }
    }

    boolean independent(int a, int b) {
        if (a == b) return false;
        if (!checked[a].get(b)) {
            boolean safe = commute(actions.get(a), actions.get(b), budget);
            checked[a].set(b);
            checked[b].set(a);
            if (safe) {
                independent[a].set(b);
                independent[b].set(a);
            }
        }
        return independent[a].get(b);
    }

    BitSet after(BitSet sleeping, int action) {
        BitSet result = new BitSet();
        for (int i = sleeping.nextSetBit(0); i >= 0; i = sleeping.nextSetBit(i + 1))
            if (independent(i, action)) result.set(i);
        return result;
    }

    /** Stubborn closure for a fixed, decreasing multiset (hence no cycle proviso). */
    BitSet persistent(BitSet remaining, BitSet enabled, Map<K, BigInteger> stock, Set<K> external) {
        BitSet selected = new BitSet();
        Deque<Integer> pending = new ArrayDeque<>();
        int first = enabled.nextSetBit(0);
        if (first < 0) return selected;
        selected.set(first);
        pending.add(first);
        while (!pending.isEmpty()) {
            int action = pending.removeFirst();
            K missing = null;
            if (!enabled.get(action)) {
                for (K key : actions.get(action).required().keySet()) {
                    budget.check();
                    if (!external.contains(key) && stock.getOrDefault(key, BigInteger.ZERO).compareTo(actions.get(action).required(key)) < 0) {
                        missing = key;
                        break;
                    }
                }
            }
            for (int other = remaining.nextSetBit(0); other >= 0; other = remaining.nextSetBit(other + 1)) {
                budget.check();
                if (selected.get(other)) continue;
                // An enabled member must commute with every omitted action.
                // A disabled member must first receive a missing material;
                // include ALL possible suppliers, not one preferred source.
                boolean needed = enabled.get(action) ? !independent(action, other) :
                        missing == null || actions.get(other).delta(missing).signum() > 0;
                if (needed) {
                    selected.set(other);
                    pending.addLast(other);
                }
            }
        }
        selected.and(enabled);
        return selected;
    }

    static boolean subset(BitSet left, BitSet right) {
        for (int i = left.nextSetBit(0); i >= 0; i = left.nextSetBit(i + 1)) if (!right.get(i)) return false;
        return true;
    }

    static <K> boolean commute(SequenceSummary<K> a, SequenceSummary<K> b, PlanningBudget budget) {
        Set<K> keys = a.keys();
        keys.addAll(b.keys());
        for (K key : keys) {
            budget.check();
            BigInteger need = a.required(key).max(b.required(key));
            // Equal net effects alone are insufficient: either action must
            // remain enabled when the other one fires first.
            if (!a.required(key).max(b.required(key).subtract(a.delta(key))).equals(need) ||
                    !b.required(key).max(a.required(key).subtract(b.delta(key))).equals(need))
                return false;
            BigInteger peak = a.peak(key).max(b.peak(key));
            // Also preserve arbitrary per-resource capacity constraints. Two
            // producers sharing a finite buffer are deliberately dependent.
            if (!a.peak(key).max(a.delta(key).add(b.peak(key))).equals(peak) ||
                    !b.peak(key).max(b.delta(key).add(a.peak(key))).equals(peak))
                return false;
        }
        return true;
    }
}
