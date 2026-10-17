// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.List;

/** Necessary whole-key packing bounds on one firing's original inventory.
 * For each whole-slot size q, the number of slots of size at least q cannot
 * exceed sum floor(key count / q). Eligibility is deliberately relaxed here.
 * Passing this check is not an allocation; failure is not a Hall deficit or
 * a bound on entities that a different execution could manufacture. */
final class StateEntityPacking<K> implements AutoCloseable {
    private static final long BYTES = 192;
    private final List<StateEntityAllocation.Demand> demands;
    private final List<StateEntityAllocation.Supply<K>> supplies;
    private final PlanningBudget budget;
    private final int fixed;
    private int nextDemand, supply, available;
    private long threshold, memory = BYTES;
    private BigInteger divisor;
    private boolean done, possible;

    /** The owned demand prefix is whole-key and sorted by descending amount.
     * Called lazily after an exact branch fails, so first-success allocations
     * retain their order, work and memory cost. No extra matrix is allocated. */
    static <K> StateEntityPacking<K> create(List<StateEntityAllocation.Demand> demands, int fixed,
            List<StateEntityAllocation.Supply<K>> supplies, PlanningBudget budget) {
        if (fixed < 2 || !budget.tryReserve(BYTES)) return null;
        try { return new StateEntityPacking<>(demands, fixed, supplies, budget); }
        catch (RuntimeException | Error failure) { budget.release(BYTES); throw failure; }
    }

    private StateEntityPacking(List<StateEntityAllocation.Demand> demands, int fixed,
            List<StateEntityAllocation.Supply<K>> supplies, PlanningBudget budget) {
        this.demands = demands; this.fixed = fixed; this.supplies = supplies; this.budget = budget;
    }

    /** One demand or supply term, retained across scheduling boundaries. */
    boolean step() {
        budget.checkpoint();
        if (done) return true;
        budget.check();
        if (threshold == 0) {
            // Unit slots add only the total-count bound already checked by
            // the owner; mixed slots lie outside this prefix altogether.
            if (nextDemand == fixed || demands.get(nextDemand).amount() == 1) {
                possible = done = true; return true;
            }
            threshold = demands.get(nextDemand).amount(); divisor = BigInteger.valueOf(threshold);
        }
        if (nextDemand < fixed && demands.get(nextDemand).amount() == threshold) {
            nextDemand++; return false;
        }
        if (available < nextDemand && supply < supplies.size()) {
            // Saturating at the required slot count avoids both long overflow
            // and quantity expansion, even for inventories wider than long.
            BigInteger count = supplies.get(supply++).count().divide(divisor);
            available += count.min(BigInteger.valueOf(nextDemand - available)).intValueExact();
            return false;
        }
        if (available < nextDemand) { done = true; return true; }
        threshold = 0; divisor = null; supply = available = 0;
        return false;
    }

    boolean possible() { return done && possible; }

    @Override public void close() {
        budget.release(memory); memory = 0; divisor = null; done = true;
    }
}
