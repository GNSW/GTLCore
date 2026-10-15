// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Completed structural nullspace; never a cached stock or feasibility conclusion. */
final class ConservationKernel<K> {
    static final long MAX_BYTES = 256L << 10;
    private final List<K> keys;
    private final BigInteger[][] vectors;
    private final int[] positiveSigns;
    final long bytes, weight;
    private final int rays;

    private ConservationKernel(List<K> keys, BigInteger[][] vectors, int[] signs, long bytes, int rays) {
        this.keys = List.copyOf(keys);
        this.vectors = vectors;
        positiveSigns = signs;
        this.bytes = bytes;
        this.rays = rays;
        weight = keys.size() * (1L + vectors.length);
    }

    static <K> ConservationKernel<K> capture(List<K> keys, List<BigInteger[]> source, PlanningBudget budget) {
        int n = keys.size();
        if (n > 192 || source.size() > 16) return null;
        // The compiler reserves a separate preparation workspace. Neither its
        // memory nor these scans borrow from the current order's solver quota.
        long bytes = 192L + 16L * n + 32L * source.size();
        for (var vector : source) {
            if (vector.length != n) return null;
            if (!budget.tryCacheScan(n)) return null;
            for (var value : vector) bytes += 48L + 4L * ((value.bitLength() + 31L) / 32);
            if (bytes > MAX_BYTES) return null;
        }
        var vectors = new BigInteger[source.size()][];
        var signs = new int[source.size()];
        int rays = 0;
        for (int i = 0; i < source.size(); i++) {
            if (!budget.tryCacheScan(n)) return null;
            vectors[i] = source.get(i).clone();
            boolean positive = false, negative = false;
            for (var value : vectors[i]) {
                positive |= value.signum() > 0;
                negative |= value.signum() < 0;
            }
            if (positive != negative) { signs[i] = positive ? 1 : -1; rays++; }
        }
        if (!budget.tryCacheScan(n)) return null;
        return new ConservationKernel<>(keys, vectors, signs, bytes, rays);
    }

    boolean sameOrder(List<K> current) { return keys.equals(current); }

    /** Different targets may borrow only globally nonnegative rays as hints.
     * Mixed-sign bases depend on the free-column order and must be recomputed. */
    boolean canProbe(Map<K, Integer> current, PlanningBudget budget, long allowance) {
        if (rays == 0 || current.size() != keys.size()) return false;
        long work = 1L + keys.size() * (2L + 3L * rays);
        if (work > allowance / 16 || work > budget.remainingWork() / 32) return false;
        budget.operations(PlanningBudget.Operation.SCAN, 0, keys.size());
        return keys.stream().allMatch(current::containsKey);
    }

    void restore(List<BigInteger[]> destination, Map<K, Integer> current, boolean positiveOnly, PlanningBudget budget) {
        for (int i = 0; i < vectors.length; i++) {
            if (positiveOnly && positiveSigns[i] == 0) continue;
            budget.operations(PlanningBudget.Operation.SCAN, 0, keys.size());
            var copy = new BigInteger[keys.size()];
            if (!positiveOnly) System.arraycopy(vectors[i], 0, copy, 0, copy.length);
            else for (int j = 0; j < keys.size(); j++)
                copy[current.get(keys.get(j))] = positiveSigns[i] > 0 ? vectors[i][j] : vectors[i][j].negate();
            destination.add(copy);
        }
    }
}
