// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Joins already executed scan passes; every extra copy has exact prefix and count bounds. */
final class CountScheduleWord<K> implements AutoCloseable {
    record Jump<K>(PlanStep body, SequenceSummary<K> summary, BigInteger[] counts, BigInteger copies, int passes) {}

    private final PlanningBudget budget;
    private final long allowance;
    private final int maxPasses;
    private final List<PlanStep> bodies = new ArrayList<>();
    private BigInteger[] counts;
    private SequenceSummary<K> summary = SequenceSummary.empty();
    private long work, memory;

    CountScheduleWord(List<SequenceSummary<K>> actions, BigInteger[] original, PlanningBudget budget) {
        this.budget = budget;
        allowance = Math.min(16_384, budget.remainingWork() / 16);
        maxPasses = Math.min(64, original.length);
        // Optional working set, not a domain restriction. The ordinary passes,
        // ordering repairs and exact continuations remain available on refusal.
        if (original.length > 128 || maxPasses < 2 || allowance < 512) return;
        // Count every actual port, including coproducts outside the count rows.
        long ports = actions.stream().mapToLong(a -> a.required().size() + a.delta().size()).sum();
        if (ports > 2048) return;
        long bits = 80;
        for (BigInteger count : original) bits = Math.max(bits, count.bitLength() + 80L);
        long bytes = 2048 + 256L * original.length + 64L * maxPasses +
                (1024L + 12L * ((bits + 7L) / 8)) * ports;
        if (!budget.tryReserve(bytes)) return;
        memory = bytes;
        try {
            counts = new BigInteger[original.length];
            Arrays.fill(counts, BigInteger.ZERO);
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    Jump<K> observe(PlanStep body, SequenceSummary<K> next, BigInteger[] used, BigInteger[] remaining,
                    Map<K, BigInteger> held, Set<K> external) {
        if (memory == 0) return null;
        if (bodies.size() >= maxPasses) reset();
        // A startup action or exhausted branch cannot occur in another copy.
        // Keep only the current pass if an older part has become unrepeatable.
        for (int i = 0; i < counts.length; i++) {
            if (!tick()) return null;
            if (counts[i].add(used[i]).compareTo(remaining[i]) > 0) { reset(); break; }
        }
        BigInteger copies = null;
        for (int i = 0; i < counts.length; i++) {
            if (!tick()) return null;
            counts[i] = counts[i].add(used[i]);
            if (counts[i].signum() > 0) {
                BigInteger bound = remaining[i].divide(counts[i]);
                copies = copies == null ? bound : copies.min(bound);
            }
        }
        if (copies == null || copies.signum() == 0) { reset(); return null; }
        Map<K, BigInteger> need = new LinkedHashMap<>(), delta = new LinkedHashMap<>(), peak = new LinkedHashMap<>();
        Set<K> keys = summary.keys(); keys.addAll(next.keys());
        for (K key : keys) {
            if (!tick()) return null;
            need.put(key, summary.required(key).max(next.required(key).subtract(summary.delta(key))));
            delta.put(key, summary.delta(key).add(next.delta(key)));
            peak.put(key, summary.peak(key).max(summary.delta(key).add(next.peak(key))));
        }
        summary = SequenceSummary.owned(need, delta, peak);
        bodies.add(body);
        if (bodies.size() < 2) return null;
        for (K key : keys) {
            if (!tick()) return null;
            if (external.contains(key)) continue;
            BigInteger available = held.getOrDefault(key, BigInteger.ZERO);
            if (available.compareTo(summary.required(key)) < 0) return null;
            BigInteger change = summary.delta(key);
            if (change.signum() < 0)
                copies = copies.min(available.subtract(summary.required(key)).divide(change.negate()).add(BigInteger.ONE));
        }
        return new Jump<>(new PlanStep.Sequence(bodies), summary, counts.clone(), copies, bodies.size());
    }

    private boolean tick() {
        if (work >= allowance) { close(); return false; }
        budget.check(); work++; return true;
    }

    void reset() {
        bodies.clear(); summary = SequenceSummary.empty();
        if (counts != null) Arrays.fill(counts, BigInteger.ZERO);
    }

    @Override public void close() {
        reset(); counts = null;
        budget.release(memory); memory = 0;
    }
}
