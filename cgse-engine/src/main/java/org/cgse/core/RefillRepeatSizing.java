// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Exact repeat breakpoint for one witnessed word under an existing supply bill. */
final class RefillRepeatSizing<K> {
    private final SequenceSummary<K> word;
    private final GraphPlan<K> plan;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final boolean ignoreTargetStock;
    private final PlanningBudget budget;
    private final Iterator<K> keys;
    private BigInteger plateau = BigInteger.ZERO, ceiling, result = BigInteger.ZERO;
    private boolean done;

    RefillRepeatSizing(SequenceSummary<K> word, GraphPlan<K> plan, Map<K, Long> stock,
                       Set<K> external, boolean force, boolean ignoreTargetStock, PlanningBudget budget) {
        this.word = word; this.plan = plan; this.stock = stock; this.external = external;
        this.ignoreTargetStock = ignoreTargetStock; this.budget = budget;
        // Exact word summaries retain a delta coordinate even for zero-net
        // returned inputs. No potentially large union needs to be constructed.
        keys = word.delta().keySet().iterator();
        done = force && word.delta(plan.target()).signum() < 0;
    }

    boolean step() {
        budget.checkpoint();
        if (done) return true;
        for (int i = 0; i < 32; i++) {
            budget.check();
            if (!keys.hasNext()) {
                result = ceiling == null ? plateau : plateau.min(ceiling);
                done = true; return true;
            }
            K key = keys.next();
            if (external.contains(key)) continue;
            BigInteger available = ignoreTargetStock && key.equals(plan.target()) ? BigInteger.ZERO : PlanningInventory.exactAmount(stock, key);
            BigInteger limit = available.add(plan.missingExact().getOrDefault(key, BigInteger.ZERO));
            BigInteger required = word.required(key), delta = word.delta(key);
            BigInteger suffix = plan.initialExact().getOrDefault(key, BigInteger.ZERO);
            if (required.compareTo(limit) > 0) { done = true; return true; }
            if (delta.signum() < 0) {
                BigInteger loss = delta.negate();
                BigInteger maximum = limit.subtract(required).divide(loss).add(BigInteger.ONE)
                        .min(limit.subtract(suffix).divide(loss));
                if (maximum.signum() <= 0) { done = true; return true; }
                ceiling = ceiling == null ? maximum : ceiling.min(maximum);
            } else if (delta.signum() > 0 && plan.missingExact().containsKey(key)) {
                // Past this point another copy cannot reduce this shortage:
                // the unchanged first-copy requirement (or stock) dominates.
                BigInteger gap = suffix.subtract(required.max(available)).max(BigInteger.ZERO);
                plateau = plateau.max(CheckedAmounts.ceilDiv(gap, delta));
            }
        }
        return false;
    }

    BigInteger result() { return result; }
}
