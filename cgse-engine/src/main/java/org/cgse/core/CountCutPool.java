// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Coordinator-owned certified linear cuts for one captured model and inventory. */
final class CountCutPool implements AutoCloseable {

    private static final class Entry {

        final ExactLinearProgram.Constraint row;
        final long bytes;
        double activity = 1;
        long used;

        Entry(ExactLinearProgram.Constraint row, long bytes, long clock) {
            this.row = row;
            this.bytes = bytes;
            used = clock;
        }

        double score(long clock) {
            int bits = row.terms().values().stream().mapToInt(BigInteger::bitLength).max().orElse(1);
            return activity / (1 + Math.max(0, clock - used) / 64.0) / (1 + row.terms().size() / 32.0 + bits / 128.0);
        }
    }

    private final Map<Map<Integer, BigInteger>, Entry> entries = new LinkedHashMap<>();
    private final PlanningBudget budget;
    private long clock, memory, offered, reused, evicted;

    CountCutPool(PlanningBudget budget) {
        this.budget = budget;
    }

    /** The caller must have proved validity over the entire original order domain. */
    boolean offer(ExactLinearProgram.Constraint candidate) {
        if (candidate.terms().size() > 256) return false;
        var row = CountReduction.normalize(candidate);
        var old = entries.get(row.terms());
        if (old != null && old.row.upper().compareTo(row.upper()) <= 0) return false;
        long bytes = 256L + row.terms().size() * 192L;
        if (!budget.tryReserve(bytes)) return false;
        if (old != null) {
            entries.remove(row.terms());
            memory -= old.bytes;
            budget.release(old.bytes);
        }
        if (entries.size() == 128) {
            var victim = entries.values().stream().min(Comparator.comparingDouble(e -> e.score(clock))).orElseThrow();
            entries.remove(victim.row.terms());
            budget.release(victim.bytes);
            memory -= victim.bytes;
            evicted++;
        }
        entries.put(row.terms(), new Entry(row, bytes, clock));
        memory += bytes;
        offered++;
        return true;
    }

    void used(Collection<ExactLinearProgram.Constraint> rows) {
        clock++;
        if (clock % 64 == 0) entries.values().forEach(entry -> entry.activity *= 0.5);
        for (var row : rows) {
            Entry entry = entries.get(CountReduction.normalize(row).terms());
            if (entry != null) {
                entry.used = clock;
                entry.activity += 4;
                reused++;
            }
        }
    }

    List<ExactLinearProgram.Constraint> snapshot() {
        // Limit per-node LP density, while retaining sparse or repeatedly useful
        // cuts for later branches. Eviction removes acceleration, never domains.
        return entries.values().stream().sorted(Comparator.<Entry>comparingDouble(e -> e.score(clock)).reversed())
                .limit(64).map(entry -> entry.row).toList();
    }

    void report() {
        if (offered != 0) budget.note("count_cut_pool", "offered=" + offered + "; reused=" + reused + "; retained=" + entries.size() + "; evicted=" + evicted);
    }

    @Override
    public void close() {
        entries.clear();
        budget.release(memory);
        memory = 0;
    }
}
