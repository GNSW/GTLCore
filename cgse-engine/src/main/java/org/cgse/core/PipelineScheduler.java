// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/** Bounded lookahead over the verified witness, with a feasible-prefix reservation. */
final class PipelineScheduler<K> {

    static final int WINDOW = 32;
    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private final PlanCursor cursor;
    private final Map<String, GraphRecipe<K>> recipes;
    private final List<Entry> window = new ArrayList<>();
    private final Map<String, Entry> first = new LinkedHashMap<>();
    private int next, active = -1;
    private final CompilationBudget compilation;
    private final GrowthWindow<K> growth;
    private boolean regrouped;
    private long regroupedAt;

    PipelineScheduler(PlanCursor cursor, Map<String, GraphRecipe<K>> recipes, List<PlanStep.Batch> saved) {
        this(cursor, recipes, saved, new CompilationBudget());
    }

    PipelineScheduler(PlanCursor cursor, Map<String, GraphRecipe<K>> recipes, List<PlanStep.Batch> saved,
                      CompilationBudget compilation) {
        this(cursor, recipes, saved, compilation, null, null);
    }

    PipelineScheduler(PlanCursor cursor, Map<String, GraphRecipe<K>> recipes, List<PlanStep.Batch> saved,
                      CompilationBudget compilation, Map<String, BigInteger> planned, AdaptiveScheduling<K> feedback) {
        this.cursor = cursor;
        this.compilation = compilation;
        this.recipes = recipes;
        this.growth = feedback == null ? null : new GrowthWindow<>(recipes, planned, feedback);
        if (saved.size() > WINDOW) throw new IllegalArgumentException("Oversized pipeline window");
        for (var batch : saved) {
            if (batch.runs() == 0 || !recipes.containsKey(batch.recipe())) throw new IllegalArgumentException("Invalid pipeline batch");
            append(batch);
        }
    }

    PlanStep.Batch poll(long tick) {
        return poll(tick, null);
    }

    PlanStep.Batch poll(long tick, ToLongFunction<K> forecast) {
        // Optional compilation is cached, globally bounded, and sliced once per
        // tick, regardless of the number of provider probes made in that tick.
        if (forecast != null) compilation.advance(cursor, tick);
        boolean intelligent = growth != null && growth.enabled();
        boolean refreshGrowth = intelligent && forecast != null && (!regrouped || regroupedAt != tick);
        if (refreshGrowth) {
            regrouped = true;
            regroupedAt = tick;
            replaceWindow(growth.regroup(snapshot(), forecast));
        }
        boolean extended = false;
        while (window.size() < WINDOW) {
            if (forecast != null) {
                if (refreshGrowth && !extended && growth.extend()) {
                    extended = true;
                    var approved = new ArrayList<List<PlanStep.Batch>>();
                    var candidate = cursor.takeLoopBatch(recipes, WINDOW - window.size(),
                            () -> key -> BigInteger.valueOf(forecast.applyAsLong(key)), batches -> {
                                var combined = new ArrayList<>(snapshot());
                                combined.addAll(batches);
                                var reordered = growth.regroup(combined, forecast);
                                if (reordered == null) return false;
                                approved.add(reordered);
                                return true;
                            });
                    if (candidate != null && !candidate.isEmpty()) {
                        replaceWindow(approved.get(0));
                        continue;
                    }
                }
                var group = cursor.takeLoopBatch(recipes, WINDOW - window.size(), () -> suffixStock(forecast));
                if (group != null) {
                    if (group.isEmpty()) break;
                    group.forEach(this::append);
                    continue;
                }
            }
            var batch = cursor.current();
            if (batch == null) break;
            append(batch);
            cursor.dispatched(batch.runs());
        }
        if (refreshGrowth) replaceWindow(growth.regroup(snapshot(), forecast));
        for (int pass = 0; pass < (intelligent ? 2 : 1); pass++) for (int i = 0; i < window.size(); i++) {
            int index = (next + i) % window.size();
            Entry entry = window.get(index);
            if (intelligent && growth.preferred(entry.recipe) != (pass == 0)) continue;
            // Equal operations are interchangeable. Dispatch the earliest
            // occurrence instead of repeatedly testing later loop iterations.
            if (first.get(entry.recipe) != entry || tick < entry.readyAt) continue;
            active = index;
            next = (index + 1) % window.size();
            // Acceptance requeues remaining work. A blocked candidate sleeps
            // until a relevant return, another acceptance, or a timed retry.
            entry.readyAt = Long.MAX_VALUE;
            return new PlanStep.Batch(entry.recipe, entry.remaining);
        }
        active = -1;
        return null;
    }

    private void replaceWindow(List<PlanStep.Batch> replacement) {
        if (replacement == null) return;
        window.clear(); first.clear(); next = 0;
        replacement.forEach(this::append);
    }

    long growthLimit(long requested, ToLongFunction<K> forecast) {
        return active < 0 || growth == null || !growth.enabled() ? requested : growth.limit(window.get(active).recipe, requested, snapshot(), forecast);
    }

    private Function<K, BigInteger> suffixStock(ToLongFunction<K> forecast) {
        Map<K, BigInteger> changes = new LinkedHashMap<>();
        for (Entry entry : window) {
            GraphRecipe<K> recipe = recipes.get(entry.recipe);
            if (!LoopBatching.fixedPerRun(recipe)) return null;
            BigInteger count = BigInteger.valueOf(entry.remaining);
            recipe.inputs().forEach((key, amount) -> changes.merge(key, count.multiply(BigInteger.valueOf(amount)).negate(), BigInteger::add));
            recipe.outputs().forEach((key, amount) -> changes.merge(key, count.multiply(BigInteger.valueOf(amount)), BigInteger::add));
        }
        return key -> BigInteger.valueOf(forecast.applyAsLong(key)).add(changes.getOrDefault(key, BigInteger.ZERO));
    }

    private void append(PlanStep.Batch batch) {
        Entry entry = new Entry(batch.recipe(), batch.runs());
        window.add(entry);
        first.putIfAbsent(entry.recipe, entry);
    }

    /**
     * Move only a funded batch ahead of the unfinished prefix. After all already
     * accepted outputs return, that prefix must still be executable. The suffix
     * then sees the same total recipe delta as in the original witness. Expected
     * output is proof of eventual prefix funding, never spendable input.
     */
    long limit(long requested, ToLongFunction<K> forecast) {
        if (active < 0 || requested <= 0) return 0;
        if (active == 0) return requested;
        GraphRecipe<K> recipe = recipes.get(window.get(active).recipe);
        var keys = new LinkedHashSet<>(recipe.inputs().keySet());
        keys.addAll(recipe.outputs().keySet());
        for (K key : keys) {
            BigInteger need = BigInteger.ZERO, delta = BigInteger.ZERO, peak = BigInteger.ZERO;
            for (int i = 0; i < active; i++) {
                Entry entry = window.get(i);
                GraphRecipe<K> earlier = recipes.get(entry.recipe);
                BigInteger input = BigInteger.valueOf(earlier.inputs().getOrDefault(key, 0L));
                BigInteger change = BigInteger.valueOf(earlier.outputs().getOrDefault(key, 0L)).subtract(input);
                BigInteger count = BigInteger.valueOf(entry.remaining);
                BigInteger required = input.add(change.negate().max(BigInteger.ZERO).multiply(count.subtract(BigInteger.ONE)));
                need = need.max(required.subtract(delta));
                peak = peak.max(delta.add(change.max(BigInteger.ZERO).multiply(count)));
                delta = delta.add(change.multiply(count));
            }
            long fixed = recipe.configurationInputs().getOrDefault(key, 0L) - recipe.reusableInputs().getOrDefault(key, 0L);
            BigInteger base = BigInteger.valueOf(forecast.applyAsLong(key)).subtract(BigInteger.valueOf(fixed));
            BigInteger net = BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L))
                    .subtract(BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L) - fixed));
            // Any provider is allowed to accept a smaller batch. Require the
            // reservation to hold even at one run, then cap the upper endpoint.
            if (base.add(net).compareTo(need) < 0) return 0;
            if (net.signum() <= 0 && base.add(net).add(peak).compareTo(MAX) > 0) return 0;
            if (net.signum() < 0) requested = Math.min(requested, capped(base.subtract(need).divide(net.negate())));
            else if (net.signum() > 0) requested = Math.min(requested, capped(MAX.subtract(peak).subtract(base).divide(net)));
            else if (base.add(peak).compareTo(MAX) > 0) return 0;
            if (requested == 0) return 0;
        }
        return requested;
    }

    private static long capped(BigInteger amount) {
        return amount.max(BigInteger.ZERO).min(MAX).longValueExact();
    }

    void accepted(long runs) {
        if (active < 0 || runs <= 0 || runs > window.get(active).remaining) throw new IllegalArgumentException("Invalid pipeline acceptance");
        Entry entry = window.get(active);
        entry.remaining -= runs;
        if (entry.remaining == 0) {
            window.remove(active);
            first.remove(entry.recipe);
            for (Entry later : window) if (later.recipe.equals(entry.recipe)) {
                first.put(later.recipe, later);
                break;
            }
            if (next > active) next--;
            if (!window.isEmpty()) next %= window.size();
            else next = 0;
        }
        // Removing work changes the reservation for later steps, including
        // steps blocked on a seed whose physical quantity did not change.
        for (Entry pending : window) pending.readyAt = 0;
        active = -1;
    }

    void retry(long tick) {
        if (active >= 0) window.get(active).readyAt = tick;
    }

    void resourceChanged(K key) {
        for (Entry entry : window) {
            GraphRecipe<K> recipe = recipes.get(entry.recipe);
            if (recipe.inputs().containsKey(key) || recipe.outputs().containsKey(key)) entry.readyAt = 0;
        }
    }

    boolean finished() {
        return window.isEmpty() && cursor.current() == null;
    }

    List<PlanStep.Batch> snapshot() {
        return window.stream().map(entry -> new PlanStep.Batch(entry.recipe, entry.remaining)).toList();
    }

    PlanStep remainingSteps() {
        List<PlanStep> remaining = new ArrayList<>(snapshot());
        PlanStep suffix = cursor.remainingSteps();
        if (!(suffix instanceof PlanStep.Sequence sequence) || !sequence.children().isEmpty()) remaining.add(suffix);
        return remaining.size() == 1 ? remaining.get(0) : new PlanStep.Sequence(remaining);
    }

    Map<String, BigInteger> remainingCounts() {
        Map<String, BigInteger> result = new LinkedHashMap<>(cursor.remainingCountsExact());
        for (Entry entry : window) result.merge(entry.recipe, BigInteger.valueOf(entry.remaining), BigInteger::add);
        return result;
    }

    private static final class Entry {

        final String recipe;
        long remaining, readyAt;

        Entry(String recipe, long remaining) {
            this.recipe = recipe;
            this.remaining = remaining;
        }
    }

    /** Shared by all dependency lanes; a large ready queue cannot multiply main-thread index work. */
    static final class CompilationBudget {
        private final SequenceLoopIndex.Storage storage = new SequenceLoopIndex.Storage();
        private int remaining = SequenceLoopIndex.MAX_WORK, slice;
        private long indexedAt;
        private boolean started;

        void advance(PlanCursor cursor, long tick) {
            if (remaining == 0) return;
            if (!started || indexedAt != tick) {
                indexedAt = tick;
                started = true;
                slice = 1024;
            }
            if (slice == 0) return;
            int used = cursor.prepareBatching(Math.min(slice, remaining), storage);
            remaining -= used;
            slice -= used;
        }

        int work() { return SequenceLoopIndex.MAX_WORK - remaining; }
    }
}
