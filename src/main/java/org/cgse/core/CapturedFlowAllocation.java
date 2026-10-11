// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Positive-only cross-pattern flow proposal. Recipe counts and integral slot
 * choices share physical resource rows, including candidate-specific returns.
 * Net balance does NOT establish executable startup or seed preservation.
 * Only restored variants leave this view; the ordinary planner validates them.
 */
final class CapturedFlowAllocation<K> implements AutoCloseable {

    record Variant(int entry, BigInteger ordinal, BigInteger firings) {}

    private final List<CapturedCatalog.Entry<K>> entries;
    private final Map<K, List<Integer>> producers;
    private final Map<K, Long> stock, seeds;
    private final Set<K> external;
    private final K target;
    private final long amount;
    private final boolean force;
    private final PlanningBudget budget;
    private final Set<K> requested = new HashSet<>();
    private final Set<Integer> seen = new HashSet<>();
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private final List<Pattern> patterns = new ArrayList<>();
    private final List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
    private final Map<K, Map<Integer, BigInteger>> resources = new LinkedHashMap<>();
    private final List<Variant> results = new ArrayList<>();
    private final long buildStarted, buildAllowance;
    private int entry = -1, slot, candidate, output, variables, restoring;
    private Pattern building;
    private boolean validated, built, done;
    private Iterator<Map.Entry<K, Map<Integer, BigInteger>>> resourceRows;
    private CountLcg search;
    private BigInteger[] counts;
    private CapturedRecipeDomain<K> domain;
    private CapturedAllocationRecovery<K> recovery;
    private long memory;

    CapturedFlowAllocation(List<CapturedCatalog.Entry<K>> entries, Map<K, List<Integer>> producers,
                           K target, long amount, Map<K, Long> stock, Set<K> external,
                           Map<K, Long> seeds, boolean force, PlanningBudget budget) {
        this.entries = entries; this.producers = producers; this.target = target; this.amount = amount;
        this.stock = stock; this.external = external; this.seeds = seeds; this.force = force; this.budget = budget;
        buildStarted = budget.compilationWork();
        buildAllowance = Math.min(32768, budget.remainingWork() / 4);
        try {
            if (budget.remainingWork() < 8192 || !reserve(1024)) { done = true; return; }
            require(target);
            for (K key : seeds.keySet()) { budget.compilationCheck(); require(key); }
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    boolean step() {
        try (var costs = budget.trace(PlanningCostTrace.Origin.ALLOCATION, counts != null ? PlanningCostTrace.Stage.RESTORE :
                search != null || built ? PlanningCostTrace.Stage.SEARCH : PlanningCostTrace.Stage.PREPARE)) {
            return stepMeasured();
        }
    }

    private boolean stepMeasured() {
        if (done) return true;
        budget.checkpoint();
        if (counts != null) restore();
        else if (search != null) {
            budget.phase(PlanningBudget.Phase.SOLVE);
            if (!search.step()) return false;
            counts = search.counts();
            search.close(); search = null;
            if (counts == null || !reserve(96L * counts.length)) { counts = null; done = true; }
        } else if (!built) {
            budget.compilationCheck();
            if (budget.compilationWork() - buildStarted >= buildAllowance) { done = true; return true; }
            build();
        } else {
            if (!reserve(32L * variables)) { done = true; return true; }
            BigInteger[] low = new BigInteger[variables], high = new BigInteger[variables];
            Arrays.fill(low, BigInteger.ZERO);
            budget.note("candidate_flow", "patterns=" + patterns.size() + "; variables=" + variables + "; rows=" + rows.size());
            search = new CountLcg(rows, low, high, budget, Math.min(32768, budget.remainingWork() / 8));
        }
        return done;
    }

    private void build() {
        if (resourceRows != null) {
            if (!resourceRows.hasNext()) { built = true; return; }
            var row = resourceRows.next(); K key = row.getKey();
            if (external.contains(key) && !key.equals(target)) return;
            BigInteger available = BigInteger.valueOf(stock.getOrDefault(key, 0L));
            BigInteger goal = BigInteger.valueOf(seeds.getOrDefault(key, 0L));
            if (key.equals(target)) goal = goal.add(BigInteger.valueOf(amount));
            BigInteger upper = available.subtract(goal);
            // A conservative forced-production proposal requires net growth.
            // Existing target seeds still fund explicit delivery reserves.
            if (force && key.equals(target)) upper = upper.min(BigInteger.valueOf(amount).negate());
            addRow(row.getValue(), upper);
        } else if (entry < 0) {
            if (pending.isEmpty()) {
                resource(target);
                for (K key : seeds.keySet()) { budget.compilationCheck(); resource(key); }
                resourceRows = resources.entrySet().iterator();
            } else {
                entry = pending.removeFirst(); slot = candidate = output = 0; validated = false;
                var recipe = entries.get(entry).recipe();
                if (recipe.size() == 0 || recipe.inputs().size() > 256) entry = -1;
            }
        } else if (!validated) {
            var recipe = entries.get(entry).recipe();
            if (slot < recipe.inputs().size()) {
                var input = recipe.inputs().get(slot);
                if (input.multiplier() <= 0) { entry = -1; return; }
                if (candidate < input.candidates().size()) {
                    var value = input.candidates().get(candidate++);
                    // Dispatch consumption and read arcs require a different
                    // formulation. Do not silently linearize either of them.
                    if (value.configuration() || value.reusable()) entry = -1;
                } else { slot++; candidate = 0; }
            } else {
                int x = variable();
                if (x < 0 || !reserve(256)) return;
                building = new Pattern(entry, x); patterns.add(building);
                slot = candidate = 0; validated = true;
            }
        } else {
            var recipe = entries.get(entry).recipe();
            if (slot < recipe.inputs().size()) buildSlot(recipe);
            else if (output < recipe.outputs().size()) {
                var value = recipe.outputs().get(output++);
                term(value.what(), building.variable, BigInteger.valueOf(value.amount()).negate());
            } else { building = null; entry = -1; }
        }
    }

    private void buildSlot(CapturedRecipe<K> recipe) {
        var input = recipe.inputs().get(slot);
        boolean mixed = !recipe.external() && input.multiplier() <= 9;
        if (candidate == 0 && building.slots.size() == slot) {
            if (!reserve(256)) return;
            building.slots.add(new LinkedHashMap<>());
        }
        if (candidate < input.candidates().size()) {
            int at = candidate++;
            var value = input.candidates().get(at);
            long copies = mixed ? 1 : input.multiplier();
            BigInteger consumed = BigInteger.valueOf(value.stack().amount()).multiply(BigInteger.valueOf(copies));
            if (consumed.signum() <= 0 || consumed.bitLength() > 63) return;
            K key = value.stack().what();
            // This is only proposal admission. Omitted producers/semantics and
            // zero-stock candidates cannot yield negative canonical knowledge.
            if (!external.contains(key) && stock.getOrDefault(key, 0L) <= 0 && !producers.containsKey(key)) return;
            int u = variable();
            if (u < 0 || !reserve(96)) return;
            building.slots.get(slot).put(at, u);
            term(key, u, consumed);
            if (value.remaining() != null) term(value.remaining(), u, BigInteger.valueOf(copies).negate());
            require(key);
        } else {
            var terms = new LinkedHashMap<Integer, BigInteger>();
            for (int u : building.slots.get(slot).values()) { budget.compilationCheck(); terms.put(u, BigInteger.ONE); }
            terms.put(building.variable, BigInteger.valueOf(mixed ? input.multiplier() : 1).negate());
            addRow(terms, BigInteger.ZERO);
            terms.replaceAll((id, value) -> { budget.compilationCheck(); return value.negate(); });
            addRow(terms, BigInteger.ZERO);
            slot++; candidate = 0;
        }
    }

    private int variable() {
        if (variables >= 4096 || !reserve(128)) { done = true; return -1; }
        return variables++;
    }

    private void require(K key) {
        budget.compilationCheck();
        if (requested.contains(key) || done || !reserve(112)) return;
        requested.add(key);
        for (int provider : producers.getOrDefault(key, List.of())) {
            budget.compilationCheck();
            if (seen.contains(provider)) continue;
            if (seen.size() >= 512 || !reserve(96)) { done = true; return; }
            seen.add(provider); pending.addLast(provider);
        }
    }

    private Map<Integer, BigInteger> resource(K key) {
        var terms = resources.get(key);
        if (terms == null) {
            if (!reserve(160)) return null;
            terms = new LinkedHashMap<>(); resources.put(key, terms);
        }
        return terms;
    }

    private void term(K key, int id, BigInteger amount) {
        budget.compilationCheck();
        var terms = resource(key);
        if (terms == null || !reserve(112)) return;
        terms.merge(id, amount, BigInteger::add);
    }

    private void addRow(Map<Integer, BigInteger> terms, BigInteger upper) {
        if (reserve(128L + 112L * terms.size())) rows.add(new ExactLinearProgram.Constraint(terms, upper));
    }

    private void restore() {
        budget.compilationCheck();
        if (recovery != null) {
            if (!recovery.step()) return;
            var ordinals = recovery.results();
            if (!recovery.complete() || !reserve(128L * ordinals.size())) { done = true; return; }
            var batches = recovery.batches();
            for (var ordinal : ordinals) {
                budget.compilationCheck(); results.add(new Variant(patterns.get(restoring).entry, ordinal, batches.get(ordinal)));
            }
            recovery.close(); recovery = null; domain.close(); domain = null; restoring++;
        } else if (restoring < patterns.size()) {
            var pattern = patterns.get(restoring);
            BigInteger firings = counts[pattern.variable];
            if (firings.signum() == 0) { restoring++; return; }
            long scratch = 256L * pattern.slots.size();
            for (var slot : pattern.slots) { budget.compilationCheck(); scratch += 128L * slot.size(); }
            if (!reserve(scratch)) return;
            try {
                var allocation = new ArrayList<Map<Integer, BigInteger>>();
                for (var slot : pattern.slots) {
                    budget.compilationCheck(); var values = new LinkedHashMap<Integer, BigInteger>();
                    for (var choice : slot.entrySet()) {
                        budget.compilationCheck();
                        if (counts[choice.getValue()].signum() > 0) values.put(choice.getKey(), counts[choice.getValue()]);
                    }
                    allocation.add(values);
                }
                var recipe = entries.get(pattern.entry).recipe();
                domain = recipe.domain(budget);
                recovery = new CapturedAllocationRecovery<>(recipe, domain, firings, allocation, budget);
            } finally { budget.release(scratch); memory -= scratch; }
        } else done = true;
    }

    List<Variant> results() { return List.copyOf(results); }

    private boolean reserve(long bytes) {
        if (done || !budget.tryReserve(bytes)) { done = true; return false; }
        memory += bytes; return true;
    }

    @Override public void close() {
        if (search != null) search.close(); search = null;
        if (recovery != null) recovery.close(); recovery = null;
        if (domain != null) domain.close(); domain = null;
        budget.release(memory); memory = 0; done = true;
        requested.clear(); seen.clear(); pending.clear(); patterns.clear(); rows.clear(); resources.clear();
        counts = null; building = null; resourceRows = null; results.clear();
    }

    private final class Pattern {
        final int entry, variable;
        final List<Map<Integer, Integer>> slots = new ArrayList<>();
        Pattern(int entry, int variable) { this.entry = entry; this.variable = variable; }
    }
}
