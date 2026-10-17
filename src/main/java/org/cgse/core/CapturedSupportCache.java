// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Positive source/key hints for one immutable capture. No quantities or negative solver facts. */
final class CapturedSupportCache<K> {
    private static final int MAX_ENTRIES = 32, MAX_SEEDS = 64;
    private static final int MAX_STOCK_KEYS = 256, MAX_PORTS = 4096;
    static final long MAX_BYTES = 64L << 10;
    record Hint<K>(K target, Set<K> seeds, List<CapturedCatalog.Entry<K>> sources, List<K> stockKeys, long bytes) {}
    private record Snapshot<K>(List<Hint<K>> hints, long bytes) {}
    record Metrics(long hits, long misses, int entries, long bytes, long maximumBytes) {}

    private final AtomicReference<Snapshot<K>> saved = new AtomicReference<>(new Snapshot<>(List.of(), 0));
    private final AtomicLong hits = new AtomicLong(), misses = new AtomicLong();

    List<CapturedCatalog.Entry<K>> get(K target, Set<K> seeds, PlanningBudget budget) {
        var hint = select(target, seeds, budget);
        return hint == null ? null : hint.sources;
    }

    Hint<K> select(K target, Set<K> seeds, PlanningBudget budget) {
        if (seeds.size() <= MAX_SEEDS) for (var hint : saved.get().hints) {
            budget.compilationScan();
            if (!hint.target.equals(target) || hint.seeds.size() != seeds.size()) continue;
            boolean same = true;
            for (K seed : seeds) {
                budget.compilationScan();
                if (!hint.seeds.contains(seed)) { same = false; break; }
            }
            if (same) {
                hits.updateAndGet(x -> x == Long.MAX_VALUE ? x : x + 1);
                return hint;
            }
        }
        misses.updateAndGet(x -> x == Long.MAX_VALUE ? x : x + 1);
        return null;
    }

    void retain(K target, Set<K> seeds, List<CapturedCatalog.Entry<K>> sources, PlanningBudget budget) {
        retain(target, seeds, sources, budget, false);
    }

    /** A verified recovery may add original sources in their original order.
     * Drop the old stock projection: the added sources may use other keys. */
    void retainExpanded(K target, Set<K> seeds, List<CapturedCatalog.Entry<K>> sources, PlanningBudget budget) {
        retain(target, seeds, sources, budget, true);
    }

    private void retain(K target, Set<K> seeds, List<CapturedCatalog.Entry<K>> sources, PlanningBudget budget, boolean expand) {
        if (sources == null || sources.isEmpty() || sources.size() > 128 || seeds.size() > MAX_SEEDS) return;
        var before = saved.get();
        // Copying the bounded scope and publishing references is optional work.
        // Lookup and all current-request deductions remain compilation/search.
        if (!budget.tryCacheCharge(1L + seeds.size() + sources.size() + before.hints.size())) return;
        Hint<K> replaced = null;
        for (var hint : before.hints) {
            if (!budget.tryCacheScan(1)) return;
            if (!hint.target.equals(target) || hint.seeds.size() != seeds.size()) continue;
            boolean same = true;
            for (K seed : seeds) {
                if (!budget.tryCacheScan(1)) return;
                if (!hint.seeds.contains(seed)) { same = false; break; }
            }
            if (!same) continue;
            if (!expand || sources.size() <= hint.sources.size()) return;
            int matched = 0;
            for (var source : sources) {
                if (!budget.tryCacheScan(1)) return;
                if (matched < hint.sources.size() && source == hint.sources.get(matched)) matched++;
            }
            if (matched != hint.sources.size()) return;
            replaced = hint; break;
        }
        long bytes = 192L + 48L * seeds.size() + 16L * sources.size();
        var hints = new ArrayList<Hint<K>>();
        hints.add(new Hint<>(target, Set.copyOf(seeds), List.copyOf(sources), null, bytes));
        for (var hint : before.hints) {
            if (hint == replaced) continue;
            if (hints.size() == MAX_ENTRIES || hint.bytes > MAX_BYTES - bytes) break;
            hints.add(hint); bytes += hint.bytes;
        }
        // One bounded attempt. Concurrent publication can decline retention;
        // it cannot delay a request or invoke host key equality under a lock.
        saved.compareAndSet(before, new Snapshot<>(List.copyOf(hints), bytes));
    }

    /**
     * Remember only keys after a physically verified plan. The next request
     * reads their current amounts and may try this incomplete stock view first.
     * Declared ports include ordinary intermediate stock, returns and producers;
     * the witness adds worn states absent from the source prototypes. Neither
     * absence from this set nor an old successful plan is a reusable fact.
     */
    void retainStockKeys(K target, Set<K> seeds, List<CapturedCatalog.Entry<K>> sources,
                         Set<K> used, int stockSize, PlanningBudget budget) {
        if (sources.size() > 128 || seeds.size() > MAX_SEEDS || used.size() > MAX_STOCK_KEYS ||
                stockSize <= 2L * (1L + seeds.size() + sources.size() + used.size())) return;
        var before = saved.get();
        Hint<K> matched = null;
        for (var hint : before.hints) {
            if (!budget.tryCacheScan(1)) return;
            if (!hint.target.equals(target) || hint.seeds.size() != seeds.size()) continue;
            boolean same = true;
            for (K seed : seeds) {
                if (!budget.tryCacheScan(1)) return;
                if (!hint.seeds.contains(seed)) { same = false; break; }
            }
            if (!same) continue;
            if (!budget.tryCacheCharge(sources.size())) return;
            if (!hint.sources.equals(sources)) return;
            matched = hint; break;
        }
        if (matched == null) return;
        var keys = new LinkedHashSet<K>();
        if (!budget.tryCacheCharge(1L + seeds.size() + used.size())) return;
        keys.add(target); keys.addAll(seeds); keys.addAll(used);
        if (matched.stockKeys != null) {
            if (!budget.tryCacheCharge(matched.stockKeys.size())) return;
            keys.addAll(matched.stockKeys);
        }
        int ports = 0;
        for (var source : sources) {
            if (!budget.tryCacheScan(1) || ++ports > MAX_PORTS) return;
            for (var output : source.recipe().outputs()) {
                if (!budget.tryCacheScan(1) || ++ports > MAX_PORTS) return;
                keys.add(output.what());
                if (keys.size() > MAX_STOCK_KEYS) return;
            }
            for (var input : source.recipe().inputs()) {
                if (!budget.tryCacheScan(1) || ++ports > MAX_PORTS) return;
                for (var candidate : input.candidates()) {
                    if (!budget.tryCacheScan(1) || ++ports > MAX_PORTS) return;
                    keys.add(candidate.stack().what());
                    if (candidate.remaining() != null) keys.add(candidate.remaining());
                    if (keys.size() > MAX_STOCK_KEYS) return;
                }
            }
        }
        if (keys.size() > MAX_STOCK_KEYS || 2L * keys.size() >= stockSize) return;
        long bytes = 224L + 48L * seeds.size() + 16L * sources.size() + 64L * keys.size();
        if (bytes > MAX_BYTES || !budget.tryCacheCharge(keys.size() + before.hints.size())) return;
        var hints = new ArrayList<Hint<K>>();
        hints.add(new Hint<>(matched.target, matched.seeds, matched.sources, List.copyOf(keys), bytes));
        for (var hint : before.hints) {
            if (hint == matched) continue;
            if (hints.size() == MAX_ENTRIES || hint.bytes > MAX_BYTES - bytes) break;
            hints.add(hint); bytes += hint.bytes;
        }
        saved.compareAndSet(before, new Snapshot<>(List.copyOf(hints), bytes));
    }

    Metrics metrics() {
        var snapshot = saved.get();
        return new Metrics(hits.get(), misses.get(), snapshot.hints.size(), snapshot.bytes, MAX_BYTES);
    }
}
