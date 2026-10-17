// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Request-local source selection for the positive state arm. Reuses the
 * immutable captured producer index; neither membership nor absence is a
 * mathematical proof. The full capture and its ordinary state attempt survive
 * a failed selected attempt. No stock is manufactured by this analysis. */
final class CapturedStateSupport<K> implements AutoCloseable {
    private static final int MAX_SOURCES = 128, MAX_PORTS = 4096, MAX_RULES = 32, MAX_KEYS = 8192;
    private final List<CapturedCatalog.Entry<K>> entries;
    private final Map<K, List<Integer>> producers;
    private final CapturedStateProducers<K> retained;
    private final PlanningBudget budget;
    private final long started, allowance;
    private final boolean recover;
    private final List<K> recoveryRequests;
    private final List<Integer> sourceHints;
    private final Set<Integer> selected = new TreeSet<>();
    private final Set<K> requested = new HashSet<>();
    private final ArrayDeque<K> keys = new ArrayDeque<>();
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private final Map<String, StateFamily<K>> rules = new LinkedHashMap<>();
    private final ArrayDeque<StateFamily<K>> pendingRules = new ArrayDeque<>();
    private final Iterator<K> seeds;
    private StateFamily<K> scanningRule;
    private Iterator<K> producerKeys;
    private CapturedStateProducers<K>.Capture capturing;
    private boolean projected;
    private List<Integer> providers = List.of();
    private List<CapturedCatalog.Entry<K>> result;
    private int provider, scanning = -1, output, input, candidate, ports;
    private int recoveryEntry, recoveryInput, recoveryCandidate, recoveryStart, recoveryEnd = -1, recoveryRounds, relatedKey;
    private StateFamily<K> relatedRule;
    private long memory = 2048;
    private boolean done, closed, hintMatched;

    static <K> CapturedStateSupport<K> create(List<CapturedCatalog.Entry<K>> entries, Map<K, List<Integer>> producers,
            K target, Set<K> seeds, PlanningBudget budget) {
        return create(entries, producers, target, seeds, budget, null);
    }

    static <K> CapturedStateSupport<K> create(List<CapturedCatalog.Entry<K>> entries, Map<K, List<Integer>> producers,
            K target, Set<K> seeds, PlanningBudget budget, CapturedStateProducers<K> retained) {
        if (budget.remainingWork() < 16_384 || !budget.tryReserve(2048)) return null;
        return new CapturedStateSupport<>(entries, producers, target, seeds, budget, retained, false, Long.MAX_VALUE, null);
    }

    /** Optional second support after a selected state attempt missed a source.
     * It never changes the canonical producer index or exports a proof. */
    static <K> CapturedStateSupport<K> recover(List<CapturedCatalog.Entry<K>> entries, Map<K, List<Integer>> producers,
            K target, Set<K> seeds, PlanningBudget budget, CapturedStateProducers<K> retained, long maximumWork) {
        if (budget.remainingWork() < 16_384 || maximumWork < 4096 || !budget.tryReserve(2048)) return null;
        return new CapturedStateSupport<>(entries, producers, target, seeds, budget, retained, true, maximumWork, null);
    }

    /** Hints are immutable global source positions from this same capture. */
    static <K> CapturedStateSupport<K> recoverHints(List<CapturedCatalog.Entry<K>> entries, Map<K, List<Integer>> producers,
            K target, Set<K> seeds, PlanningBudget budget, CapturedStateProducers<K> retained,
            long maximumWork, List<Integer> hints) {
        if (hints == null || hints.isEmpty() || budget.remainingWork() < 16_384 || maximumWork < 4096 || !budget.tryReserve(2048)) return null;
        return new CapturedStateSupport<>(entries, producers, target, seeds, budget, retained, true, maximumWork, hints);
    }

    private CapturedStateSupport(List<CapturedCatalog.Entry<K>> entries, Map<K, List<Integer>> producers,
            K target, Set<K> seeds, PlanningBudget budget, CapturedStateProducers<K> retained, boolean recover, long maximumWork,
            List<Integer> sourceHints) {
        this.entries = entries; this.producers = producers; this.seeds = seeds.iterator(); this.budget = budget;
        this.retained = retained; this.recover = recover; this.sourceHints = sourceHints;
        recoveryRequests = recover ? new ArrayList<>() : List.of();
        started = budget.nodes(); allowance = sourceHints != null ? Math.min(8192, Math.min(maximumWork / 8, budget.remainingWork() / 16)) :
                recover ? Math.min(262_144, Math.min(maximumWork / 4, budget.remainingWork() / 8)) :
                Math.min(65_536, budget.remainingWork() / 16);
        try { require(target); }
        catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    boolean step() {
        if (done) return true;
        try (var costs = budget.trace(PlanningCostTrace.Origin.ALLOCATION, PlanningCostTrace.Stage.PREPARE)) {
            budget.compilationCheck(); budget.phase(PlanningBudget.Phase.BUILD);
            if (recover && budget.nodes() - started >= allowance - selected.size() - 8) {
                // A bounded positive support is already useful even if later
                // unrelated rules remain unscanned. Reserve its copy cost and
                // let exact planning validate it; no closure/absence is proved.
                finishBounded(); return done;
            }
            if (budget.nodes() - started >= allowance) return decline("work_limit");
            if (seeds.hasNext()) require(seeds.next());
            else if (scanning >= 0) scanSource();
            else if (provider < providers.size()) select(providers.get(provider++));
            else if (!pending.isEmpty()) { scanning = pending.removeFirst(); output = input = candidate = 0; }
            else if (!keys.isEmpty()) { providers = producers.getOrDefault(keys.removeFirst(), List.of()); provider = 0; }
            else if (producerKeys != null && producerKeys.hasNext()) {
                K key = producerKeys.next();
                if (projected || scanningRule.accepts(key) && scanningRule.capacity(key) > 0) {
                    require(key);
                    if (!done && capturing != null) capturing.add(key);
                }
            } else {
                if (capturing != null) { capturing.complete(); capturing = null; }
                if (!pendingRules.isEmpty()) {
                    scanningRule = pendingRules.removeFirst();
                    var keys = retained == null ? null : retained.get(scanningRule, producers, budget);
                    projected = keys != null;
                    producerKeys = (projected ? keys : producers.keySet()).iterator();
                    if (projected) budget.note("candidate_state_producers", "reused; keys=" + keys.size() + "; catalog_keys=" + producers.size());
                    else if (retained != null) capturing = retained.begin(scanningRule, producers, budget);
                } else if (recover) recoverSource();
                else finish();
            }
            return done;
        } catch (PlanningBudget.Exhausted limit) {
            if (limit.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw limit;
            return decline("memory_limit");
        } catch (ArithmeticException overflow) {
            if (!recover) throw overflow;
            return decline("state_arithmetic_limit");
        }
    }

    private void recoverSource() {
        if (recoveryEnd < 0) { recoveryEnd = recoveryRequests.size(); recoveryRounds++; }
        if (recoveryEntry == (sourceHints == null ? entries.size() : sourceHints.size())) {
            // New ordinary dependencies may refer to a source visited earlier.
            // Reach a fixed point under the same allowance, not source order.
            if (recoveryEnd != recoveryRequests.size()) {
                recoveryEntry = 0; recoveryStart = recoveryEnd; recoveryEnd = -1; return;
            }
            budget.note(recoveryStage(), "closed; rounds=" + recoveryRounds + "; selected=" + selected.size());
            finish(); return;
        }
        int source = sourceHints == null ? recoveryEntry : sourceHints.get(recoveryEntry);
        if (selected.contains(source)) { nextRecoverySource(); return; }
        if (relatedRule != null) {
            if (relatedKey < recoveryEnd) {
                // Compatibility only requests an extra positive candidate. It
                // does not assert that this rule can reach the requested key.
                if (relatedRule.accepts(recoveryRequests.get(relatedKey++))) {
                    relatedRule = null;
                    hintMatched = true; select(source);
                }
            } else relatedRule = null;
            return;
        }
        var recipe = entries.get(source).recipe();
        if (recipe.size() == 0) { nextRecoverySource(); return; }
        // Bound each cooperative step even for a very wide unselected entry.
        for (int scan = 0; scan < 32; scan++) {
            budget.compilationScan();
            if (budget.nodes() - started >= allowance - selected.size() - 8) { finishBounded(); return; }
            if (recoveryInput == recipe.inputs().size()) { nextRecoverySource(); return; }
            var choices = recipe.inputs().get(recoveryInput).candidates();
            if (recoveryCandidate == choices.size()) { recoveryInput++; recoveryCandidate = 0; continue; }
            var choice = choices.get(recoveryCandidate++);
            var family = choice.family();
            if (family == null || choice.stack().amount() != 1 || choice.configuration() || choice.reusable()) continue;
            // Also see a terminal return outside the rule's accepted domain:
            // a declared tool:1 may return an ordinary empty shell. Inspect one
            // exact transition, never a recursive durability / charge chain.
            K before = choice.remaining();
            budget.compilationCheck();
            if (before != null && family.accepts(before) && family.capacity(before) > 0) {
                K after = family.nominalAfterOne(before);
                if (after != null && requested.contains(after)) { hintMatched = true; select(source); return; }
            }
            // Later rounds inspect only newly discovered needs. Retaining the
            // finite key frontier avoids repeating every old family/key query.
            relatedRule = family; relatedKey = recoveryStart;
            return;
        }
    }

    private void nextRecoverySource() {
        recoveryEntry++; recoveryInput = recoveryCandidate = 0;
        relatedRule = null;
    }

    private void finishBounded() {
        budget.note(recoveryStage(), "bounded; rounds=" + recoveryRounds +
                "; selected=" + selected.size() + "; negative_proofs=false");
        finish();
    }

    private String recoveryStage() { return sourceHints == null ? "candidate_state_support_recovery" : "candidate_state_source_hints"; }

    private void require(K key) {
        if (requested.contains(key)) return;
        if (requested.size() == MAX_KEYS) { decline("key_limit"); return; }
        if (!reserve(recover ? 176 : 160)) return;
        requested.add(key); keys.addLast(key);
        if (recover) recoveryRequests.add(key);
    }

    private void select(int index) {
        if (selected.contains(index)) return;
        if (selected.size() == MAX_SOURCES) { decline("source_limit"); return; }
        if (!reserve(160)) return;
        selected.add(index); pending.addLast(index);
    }

    private void scanSource() {
        var recipe = entries.get(scanning).recipe();
        if (output < recipe.outputs().size()) {
            output++; port();
        } else if (input < recipe.inputs().size()) {
            var slot = recipe.inputs().get(input);
            if (candidate == 0 && !port()) return;
            if (candidate < slot.candidates().size()) {
                if (!port()) return;
                var choice = slot.candidates().get(candidate++);
                require(choice.stack().what());
                if (done) return;
                var family = choice.family();
                if (family != null && choice.stack().amount() == 1 && !choice.configuration() && !choice.reusable() && !rules.containsKey(family.id())) {
                    if (rules.size() == MAX_RULES) { decline("rule_limit"); return; }
                    if (!reserve(192)) return;
                    rules.put(family.id(), family); pendingRules.addLast(family);
                }
            } else { input++; candidate = 0; }
        } else scanning = -1;
    }

    private boolean port() {
        if (++ports > MAX_PORTS) { decline("port_limit"); return false; }
        return true;
    }

    private void finish() {
        if (sourceHints != null && !hintMatched) { decline("no_retained_state_source"); return; }
        if (selected.isEmpty() || selected.size() == entries.size()) { decline("unchanged_support"); return; }
        var sources = new ArrayList<CapturedCatalog.Entry<K>>(selected.size());
        // Original priority and original entry objects survive the selection.
        // Dynamic candidates get their own identities only in the later view.
        for (int index : selected) {
            budget.compilationCheck();
            if (budget.nodes() - started >= allowance) { decline("work_limit"); return; }
            sources.add(entries.get(index));
        }
        result = List.copyOf(sources); done = true;
        budget.note("candidate_state_support", "selected=" + result.size() + "; catalog=" + entries.size() +
                "; rules=" + rules.size() + "; ports=" + ports + "; work=" + (budget.nodes() - started) + "; negative_proofs=false");
    }

    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) { decline("memory_limit"); return false; }
        memory += bytes; return true;
    }

    private boolean decline(String reason) {
        done = true;
        budget.note("candidate_state_support", "declined; reason=" + reason + "; selected=" + selected.size() + "; catalog=" + entries.size());
        return true;
    }

    List<CapturedCatalog.Entry<K>> result() { return result; }

    /** Optional copy for later retention, not input to the current solve. */
    List<Integer> retainedIndices() {
        return result != null && selected.size() <= CapturedStateSources.MAX_SOURCES &&
                budget.tryCacheCharge(selected.size()) ? List.copyOf(selected) : null;
    }

    @Override public void close() {
        if (closed) return; closed = true;
        selected.clear(); requested.clear(); keys.clear(); pending.clear(); rules.clear(); pendingRules.clear();
        if (capturing != null) capturing.close();
        capturing = null;
        producerKeys = null; scanningRule = null; providers = List.of();
        relatedRule = null;
        if (recover) recoveryRequests.clear();
        budget.release(memory); memory = 0; done = true;
    }
}
