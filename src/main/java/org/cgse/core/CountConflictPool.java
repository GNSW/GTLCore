// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Coordinator-owned, inventory-scoped conflict cache; eviction only loses pruning. */
final class CountConflictPool implements AutoCloseable {

    private static final int CAPACITY = 128;
    private static final long MAX_BYTES = 512L << 10, MAX_ENTRY_BYTES = 64L << 10;

    private static final class Entry {

        final Map<Map<Integer, BigInteger>, BigInteger> rows = new HashMap<>();
        final long bytes, signature;
        double activity = 1;
        long used;

        Entry(CountConflict conflict, long bytes) {
            this.bytes = bytes;
            long bits = 0;
            for (var row : conflict.assumptions()) {
                rows.put(row.terms(), row.upper());
                bits |= 1L << row.terms().hashCode();
            }
            signature = bits;
        }

        /** This forbidden conjunction excludes every point excluded by other. */
        boolean subsumes(Entry other, PlanningBudget budget) {
            budget.operation(PlanningBudget.Operation.SCAN, 0);
            if (rows.size() > other.rows.size() || (signature & other.signature) != signature) return false;
            for (var row : rows.entrySet()) {
                budget.operation(PlanningBudget.Operation.INTEGER, row.getValue().bitLength());
                BigInteger bound = other.rows.get(row.getKey());
                // Hashes only reject impossible matches. Every retained
                // implication uses equal normalized coefficients and an exact
                // bound comparison: other assumptions imply these assumptions.
                if (bound == null || bound.compareTo(row.getValue()) > 0) return false;
            }
            return true;
        }
    }

    private final Map<CountConflict, Entry> entries = new LinkedHashMap<>();

    // A branch republishes its same explanations at each yield. Remember a
    // bounded set of already-subsumed publications while their stronger clause
    // remains resident; otherwise containment checks would repeat every slice.
    private record Alias(Entry covering, long bytes) {}

    private final Map<CountConflict, Alias> aliases = new LinkedHashMap<>();
    private final PlanningBudget budget;
    private List<CountConflict> cached = List.of();
    private long memory, learned, reused, evicted, subsumed, clock, revision;

    CountConflictPool(PlanningBudget budget) {
        this.budget = budget;
    }

    void add(Collection<CountConflict> proofs) {
        for (var conflict : proofs) {
            budget.checkpoint();
            if (entries.containsKey(conflict) || aliases.containsKey(conflict)) continue;
            long bytes = retainedBytes(conflict);
            if (bytes < 0) continue;
            // Admission is optional. Reserve before creating the lookup table;
            // a denied addition leaves every existing explanation untouched.
            if (!budget.tryReserve(bytes)) continue;
            boolean retained = false;
            try {
                Entry next = new Entry(conflict, bytes);
                List<CountConflict> redundant = new ArrayList<>();
                Entry covering = null;
                for (var existing : entries.entrySet()) {
                    if (existing.getValue().subsumes(next, budget)) {
                        covering = existing.getValue();
                        subsumed++;
                        break;
                    }
                    if (next.subsumes(existing.getValue(), budget)) redundant.add(existing.getKey());
                }
                if (covering != null) {
                    if (aliases.size() < CAPACITY && memory + bytes <= MAX_BYTES) {
                        aliases.put(conflict, new Alias(covering, bytes));
                        memory += bytes;
                        retained = true;
                    }
                    continue;
                }
                // Finish all charged comparisons before changing the pool, so
                // cancellation cannot leave a half-installed replacement.
                for (var old : redundant) {
                    remove(old);
                    subsumed++;
                }
                while (entries.size() >= CAPACITY || memory + bytes > MAX_BYTES) {
                    if (memory + bytes > MAX_BYTES && !aliases.isEmpty()) {
                        var iterator = aliases.values().iterator();
                        Alias alias = iterator.next();
                        memory -= alias.bytes;
                        budget.release(alias.bytes);
                        iterator.remove();
                        continue;
                    }
                    var victim = entries.entrySet().stream().min(Comparator.<Map.Entry<CountConflict, Entry>>comparingDouble(e -> e.getValue().activity)
                            .thenComparingLong(e -> e.getValue().used)).orElseThrow();
                    remove(victim.getKey());
                    evicted++;
                }
                entries.put(conflict, next);
                memory += bytes;
                retained = true;
                cached = null;
                revision++;
                learned++;
            } finally {
                if (!retained) budget.release(bytes);
            }
        }
    }

    private long retainedBytes(CountConflict conflict) {
        if (conflict.assumptions().size() > 64) return -1;
        long bytes = 384;
        int terms = 0;
        for (var row : conflict.assumptions()) {
            budget.operation(PlanningBudget.Operation.SCAN, 0);
            if (row.terms().size() > 64 - terms) return -1;
            terms += row.terms().size();
            bytes += 192 + integerBytes(row.upper());
            for (BigInteger value : row.terms().values()) {
                budget.operation(PlanningBudget.Operation.SCAN, 0);
                bytes += 128 + integerBytes(value);
            }
            if (bytes > MAX_ENTRY_BYTES) return -1;
        }
        return bytes;
    }

    private static long integerBytes(BigInteger value) {
        return 64L + 4L * ((value.bitLength() + 31L) / 32);
    }

    private void remove(CountConflict conflict) {
        Entry removed = entries.remove(conflict);
        for (var iterator = aliases.values().iterator(); iterator.hasNext();) {
            Alias alias = iterator.next();
            if (alias.covering == removed) {
                memory -= alias.bytes;
                budget.release(alias.bytes);
                iterator.remove();
            }
        }
        memory -= removed.bytes;
        budget.release(removed.bytes);
        cached = null;
    }

    void used(Collection<CountConflict> conflicts) {
        budget.checkpoint();
        if (clock == Long.MAX_VALUE) {
            clock >>>= 1;
            entries.values().forEach(entry -> entry.used >>>= 1);
        }
        clock++;
        if (clock % 32 == 0) {
            entries.values().forEach(entry -> entry.activity *= 0.5);
            cached = null;
        }
        for (var conflict : conflicts) {
            var entry = entries.get(conflict);
            if (entry != null) {
                entry.activity += 4;
                entry.used = clock;
                reused++;
                cached = null;
            }
        }
    }

    List<CountConflict> snapshot() {
        budget.checkpoint();
        if (cached == null) cached = entries.entrySet().stream().sorted(Comparator.<Map.Entry<CountConflict, Entry>>comparingDouble(e -> e.getValue().activity).reversed())
                .map(Map.Entry::getKey).toList();
        return cached;
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    long revision() {
        return revision;
    }

    void report() {
        if (learned > 0) budget.note("count_conflict_pool", "learned=" + learned + "; used=" + reused +
                "; retained=" + entries.size() + "; evicted=" + evicted + "; subsumed=" + subsumed +
                "; aliases=" + aliases.size() + "; bytes=" + memory);
    }

    @Override
    public void close() {
        if (!entries.isEmpty()) revision++;
        entries.clear();
        aliases.clear();
        cached = List.of();
        budget.release(memory);
        memory = 0;
    }
}
