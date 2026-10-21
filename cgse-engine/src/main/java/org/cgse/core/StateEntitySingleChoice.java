// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/** Exact current-inventory allocation for one indivisible slot. Its argmin
 * matches the general allocator's first complete assignment: preference, wear
 * cost, best-fit entity count, descending capacity, then original pool order.
 * No sorted supplies, DFS frames or mutable inventory copy are needed. */
final class StateEntitySingleChoice {
    record Result<K>(StateEntityAllocation.Pick<K> pick, StateEntityAllocation.Failure failure) {}

    private StateEntitySingleChoice() {}

    /** Null leaves unsupported/invalid inputs to the original allocator.
     * Callers own result storage. A failure only describes this one slot in
     * this exact inventory; it is never a count constraint or global proof. */
    static <K> Result<K> select(StateEntityAllocation.Demand demand, Map<K, BigInteger> owned,
            StateFamily<K> family, boolean shared, ToIntFunction<K> preference, ToLongFunction<K> cost, PlanningBudget budget) {
        if (demand.mixed() || owned.size() > 128) return null;
        budget.checkpoint();
        BigInteger required = BigInteger.valueOf(demand.amount()), available = BigInteger.ZERO, bestCount = null;
        K best = null;
        int bestRank = 0;
        long bestCost = 0, bestCapacity = 0;
        boolean bestRanked = false, bestCosted = false;
        for (var entity : owned.entrySet()) {
            budget.check();
            if (entity.getKey() == null || entity.getValue() == null || entity.getValue().signum() <= 0) return null;
            K state = entity.getKey();
            long capacity = family.accepts(state) ? family.capacity(state) : 0;
            if (capacity <= 0) {
                if (shared) continue;
                return null;
            }
            BigInteger count = entity.getValue();
            available = available.add(count);
            if (count.compareTo(required) < 0) continue;
            // A sole feasible cohort needs no preference evaluation. Once
            // there is a choice, compare lexicographically: exact wear costs
            // are irrelevant when eligibility ranks already distinguish it.
            int rank = 0, order = -1;
            long loss = 0;
            boolean ranked = best != null, costed = false;
            if (ranked) {
                if (!bestRanked) {
                    bestRank = preference == null ? 0 : preference.applyAsInt(best);
                    bestRanked = true;
                }
                rank = preference == null ? 0 : preference.applyAsInt(state);
                order = Integer.compare(rank, bestRank);
                if (order == 0) {
                    if (!bestCosted) {
                        bestCost = cost == null ? 0 : cost.applyAsLong(best);
                        bestCosted = true;
                    }
                    loss = cost == null ? 0 : cost.applyAsLong(state);
                    costed = true;
                    order = Long.compare(loss, bestCost);
                }
            }
            if (order == 0) order = count.compareTo(bestCount);
            if (order == 0) order = Long.compare(bestCapacity, capacity);
            if (order < 0) {
                best = state;
                bestRank = rank;
                bestCost = loss;
                bestRanked = ranked;
                bestCosted = costed;
                bestCount = count;
                bestCapacity = capacity;
            }
        }
        if (best != null) return new Result<>(new StateEntityAllocation.Pick<>(demand.slot(), best, demand.amount()), StateEntityAllocation.Failure.NONE);
        return new Result<>(null, available.compareTo(required) < 0 ? StateEntityAllocation.Failure.ENTITY_SHORTAGE :
                StateEntityAllocation.Failure.WHOLE_KEY_FRAGMENTATION);
    }
}
