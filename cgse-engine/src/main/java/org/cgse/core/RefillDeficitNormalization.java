// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Imports a verified refill frontier into its caller's finite-stock convention. */
final class RefillDeficitNormalization<K> implements AutoCloseable {
    private final GraphPlan<K> original;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final boolean ignoreTargetStock;
    private final PlanningBudget budget;
    private final List<GraphPlan<K>> alternatives = new ArrayList<>();
    private GraphPlan<K> current, main, result;
    private Iterator<Map.Entry<K, BigInteger>> entries;
    private Map<K, BigInteger> missing;
    private Map<K, GraphPlan.MissingSource> sources;
    private int option = -1;
    private long memory;

    RefillDeficitNormalization(GraphPlan<K> plan, Map<K, Long> stock, Set<K> external,
            boolean ignoreTargetStock, PlanningBudget budget) {
        original = current = plan; this.stock = stock; this.external = external;
        this.ignoreTargetStock = ignoreTargetStock; this.budget = budget;
    }

    boolean step() {
        if (result != null) return true;
        budget.check();
        if (entries == null) {
            reserve(640);
            missing = new LinkedHashMap<>();
            sources = new LinkedHashMap<>();
            entries = current.initialExact().entrySet().iterator();
        }
        if (entries.hasNext()) {
            var entry = entries.next(); K key = entry.getKey();
            if (!external.contains(key)) {
                BigInteger available = ignoreTargetStock && current.target().equals(key) ? BigInteger.ZERO :
                        PlanningInventory.exactAmount(stock, key);
                budget.operation(PlanningBudget.Operation.INTEGER, Math.max(entry.getValue().bitLength(), available.bitLength()));
                BigInteger gap = entry.getValue().subtract(available);
                if (gap.signum() > 0) {
                    reserve(192L + (gap.bitLength() + 7L) / 8); missing.put(key, gap);
                    var boundary = current.missingBoundary(key);
                    if (boundary != null) sources.put(key, boundary.source());
                }
            }
            return false;
        }
        var status = missing.isEmpty() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL :
                current.feasible() ? GraphPlan.Result.MISSING_INPUT : current.result();
        var normalized = GraphPlan.ownedRefill(current, current.steps(), current.recipes(),
                current.initialExact(), missing, status, budget.nodes())
                .withMissingSources(GraphPlan.MissingSource.UNASSESSED, sources).withRefillCycles(current.refillCycles());
        if (option < 0) main = normalized;
        else { reserve(32); alternatives.add(normalized); }
        if (++option < original.alternatives().size()) {
            current = original.alternatives().get(option); entries = null; missing = null; sources = null;
            return false;
        }
        reserve(64L + 16L * alternatives.size());
        result = main.withAlternatives(alternatives);
        return true;
    }

    GraphPlan<K> result() { return result; }
    long takeResultCharge() { long bytes = memory; memory = 0; return bytes; }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    public void close() { budget.release(memory); memory = 0; }
}
