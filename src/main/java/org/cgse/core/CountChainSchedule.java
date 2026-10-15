// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Repeat funded chains without expanding their primitive count vector. Witness only. */
final class CountChainSchedule<K> implements AutoCloseable {
    private record Call<K>(List<Integer> word, SequenceSummary<K> summary, PlanStep program) {}
    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }

    private final RecipeCountModel<K> model;
    private final PlanningBudget budget;
    private final BigInteger[] remaining;
    private final boolean entriesOnly;
    private final Set<K> produced = new HashSet<>(), entries = new HashSet<>();
    private final Map<K, BigInteger> held = new HashMap<>();
    private final Map<K, List<Integer>> consumers = new HashMap<>();
    private final List<SequenceSummary<K>> primitive = new ArrayList<>();
    private final Deque<Call<K>> pending = new ArrayDeque<>();
    private final List<Call<K>> calls = new ArrayList<>();
    private final Set<K> returned = new HashSet<>();
    private final List<PlanStep> program = new ArrayList<>();
    private final long allowance;
    private long memory, work, sliceStarted;
    private int phase, cursor, expanded, rounds;
    private boolean complete, progress;
    private PlanStep witness;

    CountChainSchedule(RecipeCountModel<K> model, BigInteger[] counts, PlanningBudget budget) {
        this(model, counts, budget, false);
    }

    /** A separate candidate may enter a route using its already stocked intermediates. */
    static <K> CountChainSchedule<K> entries(RecipeCountModel<K> model, PlanningBudget budget) {
        if (model.recipes.size() > 256 || model.keys.size() > 512) return null;
        BigInteger total = BigInteger.ZERO;
        for (K key : model.keys) {
            budget.check();
            total = total.add(BigInteger.valueOf(model.stock.getOrDefault(key, 0L)));
        }
        BigInteger[] counts = new BigInteger[model.recipes.size()];
        Arrays.fill(counts, total);
        return new CountChainSchedule<>(model, counts, budget, true);
    }

    private CountChainSchedule(RecipeCountModel<K> model, BigInteger[] counts, PlanningBudget budget, boolean entriesOnly) {
        this.model = model;
        this.budget = budget;
        this.entriesOnly = entriesOnly;
        remaining = counts.length > 256 ? new BigInteger[0] : counts.clone();
        allowance = Math.min(131072, budget.remainingWork() / 16);
        if (counts.length > 256 || model.keys.size() > 512 || allowance < 4096 ||
                model.recipes.stream().anyMatch(GraphRecipe::batchSensitiveInputs) ||
                Arrays.stream(counts).anyMatch(n -> n.signum() < 0) ||
                !entriesOnly && Arrays.stream(counts).noneMatch(n -> n.compareTo(BigInteger.valueOf(512)) > 0)) {
            complete = true;
            return;
        }
        long entries = model.recipes.stream().mapToLong(r -> (long) r.inputs().size() + r.outputs().size()).sum();
        if (!reserve(2048L + 192L * counts.length + 640L * entries)) return;
        long before = sliceStarted = budget.threadWork();
        try {
            for (int i = 0; i < counts.length; i++) {
                tick();
                var r = model.recipes.get(i);
                primitive.add(SequenceSummary.recipe(r));
                if (counts[i].signum() <= 0) continue;
                for (K key : r.inputs().keySet()) {
                    tick();
                    consumers.computeIfAbsent(key, unused -> new ArrayList<>()).add(i);
                    held.put(key, BigInteger.valueOf(model.stock.getOrDefault(key, 0L)));
                }
                // Stock of a coproduct can fund a later chain even if it is
                // absent from the backward model's constrained resource list.
                for (K key : r.outputs().keySet()) {
                    tick();
                    held.put(key, BigInteger.valueOf(model.stock.getOrDefault(key, 0L)));
                    produced.add(key);
                    if (r.outputs().size() == 1 && model.goal(key).signum() == 0 && !model.external.contains(key) &&
                            model.stock.getOrDefault(key, 0L) > 0) this.entries.add(key);
                }
            }
        } catch (Stop exhausted) {
            complete = true;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        } finally { work += budget.threadWork() - before; }
    }

    boolean step() {
        if (complete) return true;
        long before = sliceStarted = budget.threadWork();
        try {
            tick();
            if (work >= allowance) { complete = true; return true; }
            if (phase == 0) discover();
            else schedule();
            return complete;
        } catch (Stop exhausted) {
            complete = true;
            return true;
        } finally { work += budget.threadWork() - before; }
    }

    private void discover() {
        if (pending.isEmpty() && cursor < remaining.length && expanded < 512) {
            int id = cursor++;
            if (remaining[id].signum() > 0 && reserve(256)) pending.add(new Call<>(List.of(id), primitive.get(id),
                    new PlanStep.Batch(model.recipes.get(id).id(), 1)));
            return;
        }
        if (pending.isEmpty() || expanded >= 512) {
            pending.clear();
            for (var call : calls) for (K key : call.summary.required().keySet()) {
                tick();
                if (!model.external.contains(key) && call.summary.required(key).signum() > 0 &&
                        call.summary.delta(key).signum() >= 0) returned.add(key);
            }
            // Primitive suffixes release stocked intermediate seeds too. Keep
            // their real identities and multiplicities alongside complete calls.
            for (int i = 0; i < remaining.length; i++) if (remaining[i].signum() > 0) {
                tick();
                calls.add(new Call<>(List.of(i), primitive.get(i), new PlanStep.Batch(model.recipes.get(i).id(), 1)));
            }
            calls.sort(Comparator.<Call<K>>comparingInt(this::priority).reversed()
                    .thenComparingInt(c -> -c.word.size()));
            phase = 1;
            cursor = 0;
            return;
        }
        var call = pending.removeFirst();
        expanded++;
        var last = model.recipes.get(call.word.get(call.word.size() - 1));
        boolean extended = false;
        if (last.outputs().size() == 1 && call.word.size() < 32) {
            var port = last.outputs().entrySet().iterator().next();
            var next = consumers.getOrDefault(port.getKey(), List.of());
            if (next.size() <= 16) for (int id : next) {
                tick();
                var recipe = model.recipes.get(id);
                // Equal port rates need no speculative throughput rounding.
                // Omitted unequal-rate or cyclic words leave all old searches.
                if (call.word.contains(id) || recipe.inputs().get(port.getKey()).longValue() != port.getValue()) continue;
                charge(call.summary.keys().size() + primitive.get(id).keys().size() + call.word.size());
                if (!reserve(512L + 320L * (call.summary.keys().size() + primitive.get(id).keys().size()) + 32L * call.word.size())) return;
                var word = new ArrayList<>(call.word);
                word.add(id);
                var body = new PlanStep.Sequence(List.of(call.program, new PlanStep.Batch(recipe.id(), 1)));
                pending.addLast(new Call<>(List.copyOf(word), call.summary.then(primitive.get(id)), body));
                extended = true;
            }
        }
        if (!extended && call.word.size() > 1) calls.add(call);
    }

    private int priority(Call<K> call) {
        boolean preserves = false;
        for (K key : returned) {
            tick();
            if (call.summary.delta(key).signum() > 0) return 2;
            if (call.summary.required(key).signum() > 0 && call.summary.delta(key).signum() == 0) preserves = true;
        }
        return preserves ? 1 : 0;
    }

    private void schedule() {
        if (cursor == calls.size()) {
            if (Arrays.stream(remaining).allMatch(n -> n.signum() == 0)) {
                witness = new PlanStep.Sequence(program);
                budget.note("count_chain_schedule", "exact_repetitions; words=" + program.size() + "; calls=" + calls.size());
                complete = true;
            } else if (!progress || ++rounds == 32) complete = true;
            else { cursor = 0; progress = false; }
            if (entriesOnly && complete && !program.isEmpty()) witness = new PlanStep.Sequence(program);
            return;
        }
        var call = calls.get(cursor++);
        if (entriesOnly) {
            // Only release stocked private phases. Starting a new raw-fed call
            // or spending an existing catalyst remains the main solver's job.
            boolean consumesEntry = false;
            for (K key : call.summary.required().keySet()) if (call.summary.required(key).signum() > 0) {
                tick();
                if (entries.contains(key) && call.summary.delta(key).signum() < 0) consumesEntry = true;
                else if (produced.contains(key) && !model.external.contains(key)) return;
            }
            if (!consumesEntry || priority(call) != 2) return;
        }
        BigInteger copies = null;
        // Discovery never repeats a primitive inside one word.
        for (int id : call.word) {
            tick();
            copies = copies == null ? remaining[id] : copies.min(remaining[id]);
        }
        if (copies == null || copies.signum() == 0) return;
        for (K key : call.summary.keys()) if (!model.external.contains(key)) {
            tick();
            BigInteger available = held.getOrDefault(key, BigInteger.ZERO), need = call.summary.required(key);
            if (available.compareTo(need) < 0) return;
            BigInteger delta = call.summary.delta(key);
            if (delta.signum() < 0) copies = copies.min(available.subtract(need).divide(delta.negate()).add(BigInteger.ONE));
        }
        if (!reserve(256L + 16L * copies.bitLength())) return;
        for (int id : call.word) {
            tick();
            remaining[id] = remaining[id].subtract(copies);
        }
        for (var e : call.summary.delta().entrySet()) if (!model.external.contains(e.getKey())) {
            tick();
            held.merge(e.getKey(), e.getValue().multiply(copies), BigInteger::add);
        }
        program.add(PlanStep.repeat(call.program, copies));
        progress = true;
    }

    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) { complete = true; return false; }
        memory += bytes;
        return true;
    }

    PlanStep witness() { return witness; }

    Map<K, Long> entryStock() {
        if (!entriesOnly || witness == null) return null;
        if (!reserve(256L + 96L * (model.stock.size() + held.size()))) return null;
        Map<K, Long> result = new LinkedHashMap<>(model.stock);
        for (var e : held.entrySet()) {
            budget.check();
            if (e.getValue().signum() < 0 || e.getValue().compareTo(ExactAmounts.LONG_MAX) > 0) return null;
            result.put(e.getKey(), e.getValue().longValueExact());
        }
        return Collections.unmodifiableMap(result);
    }

    private void tick() {
        if (work + budget.threadWork() - sliceStarted >= allowance) throw new Stop();
        budget.check();
    }

    private void charge(long amount) {
        tick();
        budget.charge(amount);
    }

    @Override public void close() { budget.release(memory); memory = 0; }
}
