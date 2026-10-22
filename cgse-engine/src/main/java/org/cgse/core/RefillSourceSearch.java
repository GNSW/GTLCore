// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Bounded source-choice trials normalized to complete supply boundaries.
 * The completed incumbent, not a local intermediate bill, decides publication.
 */
final class RefillSourceSearch<K> implements AutoCloseable {
    private enum Phase { BINDINGS, SCAN, PORTS, SOURCES, INPUTS, ORIGINAL, GOALS, MISSING, RANK, TRIAL, EXPAND, COMPARE, DONE }
    private record Choice<K>(K key, GraphRecipe<K> source) {}
    private static final int MAX_CHOICES = 8;
    private final GraphPlan<K> original;
    private final GraphCompiler<K> compiler;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final Set<String> excluded;
    private final boolean force, ignoreTargetStock, reallocate, joint, borrowedOrder;
    private final PlanningBudget budget;
    private final long started, allowance, scanAllowance;
    private final String retainedFailure;
    private GraphPlan<K> result, boundary, candidate;
    private Phase phase = Phase.BINDINGS;
    private final Set<K> seen = new HashSet<>();
    private final List<Choice<K>> choices = new ArrayList<>(), local = new ArrayList<>();
    private final Map<K, String> preferred = new LinkedHashMap<>();
    private final int[] groupStart = new int[MAX_CHOICES], groupEnd = new int[MAX_CHOICES], groupAt = new int[MAX_CHOICES];
    private int groups;
    private boolean combinationsStarted;
    private Iterator<GraphRecipe<K>> recipes;
    private Iterator<K> ports, inputs;
    private Iterator<GraphRecipe<K>> sources;
    private Iterator<? extends Map.Entry<K, ? extends Number>> entries;
    private GraphRecipe<K> recipe;
    private K key;
    private boolean outputs, multihop;
    private boolean scanComplete, boundChoicesSeen, declined;
    private int boundSources;
    private int jointSources, pairFirst, pairSecond = 1;
    private boolean jointChoicesSeen, reversePair;
    private int next, accepted;
    private String previousPreference;
    private Choice<K> choice;
    private BigInteger credit = BigInteger.ZERO;
    private Map<K, BigInteger> initial, missing;
    private PlanVerification<K> verification;
    private RefillProducerOrder<K> order;
    private MissingMaterialExpansion<K> expansion;
    private RefillFrontier.Comparison<K> comparison;
    private boolean ranked, allocated;
    private boolean baselineTried;
    private boolean ordinaryWord = true, normalizing;
    private long memory;

    RefillSourceSearch(GraphCompiler<K> compiler, GraphPlan<K> original, Map<K, Long> stock,
                       Set<K> external, Set<String> excluded, boolean force, boolean ignoreTargetStock,
                       PlanningBudget budget) {
        this(compiler, original, stock, external, excluded, force, ignoreTargetStock, budget, false, false, null);
    }

    /** Runs after unused-source trials, so bound-source hints cannot displace
     * their service. The completed support has the same compiler/roots/scope
     * and remains owned by that earlier search until this continuation closes. */
    static <K> RefillSourceSearch<K> reallocate(GraphCompiler<K> compiler, GraphPlan<K> original, Map<K, Long> stock,
            Set<K> external, Set<String> excluded, boolean force, boolean ignoreTargetStock,
            PlanningBudget budget, RefillProducerOrder<K> order) {
        return new RefillSourceSearch<>(compiler, original, stock, external, excluded, force, ignoreTargetStock,
                budget, true, false, order);
    }

    /** Interior coproduct mixtures run only after the original source trials. */
    static <K> RefillSourceSearch<K> joint(GraphCompiler<K> compiler, GraphPlan<K> original, Map<K, Long> stock,
            Set<K> external, Set<String> excluded, boolean force, boolean ignoreTargetStock,
            PlanningBudget budget, RefillProducerOrder<K> order) {
        return new RefillSourceSearch<>(compiler, original, stock, external, excluded, force, ignoreTargetStock,
                budget, false, true, order);
    }

    private RefillSourceSearch(GraphCompiler<K> compiler, GraphPlan<K> original, Map<K, Long> stock,
            Set<K> external, Set<String> excluded, boolean force, boolean ignoreTargetStock,
            PlanningBudget budget, boolean reallocate, boolean joint, RefillProducerOrder<K> completedOrder) {
        this.compiler = compiler; this.original = result = original; this.stock = stock;
        this.external = external; this.excluded = excluded; this.force = force; this.ignoreTargetStock = ignoreTargetStock;
        this.budget = budget; started = budget.nodes(); retainedFailure = budget.failureDetail();
        this.reallocate = reallocate; this.joint = joint; order = completedOrder; borrowedOrder = completedOrder != null;
        ranked = borrowedOrder;
        // Long unused routes owe the same original/candidate verification as
        // bound mixtures. Size their complete analysis too; a fixed 1024 scan
        // previously expired in bindings before even reaching source choices.
        // These optional grants never enlarge the request's remaining budget.
        long cap = Math.min(65536, Math.max(8192, 128L * original.recipes().size()));
        allowance = Math.min(cap, budget.remainingWork() / 4);
        scanAllowance = Math.min(reallocate || cap > 8192 ? 8192 : 1024, Math.max(128, allowance / 4));
        recipes = original.recipes().values().iterator();
    }

    boolean step() {
        try {
            budget.checkpoint();
            if (phase == Phase.DONE) return true;
            if (!normalizing && budget.nodes() - started >= allowance) return finish();
            if (!allocated) { reserve(1024); allocated = true; }
            // Each child owns its slice. Never append a child to a full scan.
            if (phase == Phase.ORIGINAL) {
                if (!verification.step()) return false;
                credit = force ? verification.summary().delta(original.target()) : BigInteger.ZERO;
                verification.close(); verification = null;
                // Net production cannot certify a low-net forced-craft proof.
                // Keep that existing witness rather than weakening its contract.
                if (force && credit.compareTo(BigInteger.valueOf(original.amount())) < 0) { declined = true; return finish(); }
                initial = new LinkedHashMap<>(); missing = new LinkedHashMap<>();
                entries = original.seeds().entrySet().iterator(); phase = Phase.GOALS; return false;
            }
            if (phase == Phase.RANK) {
                if (!order.step()) return false;
                ranked = true; phase = Phase.TRIAL; return false;
            }
            if (phase == Phase.EXPAND) {
                if (!expansion.step()) return false;
                candidate = expansion.result();
                if (force && expansion.prefixProduction().compareTo(credit) < 0) { endTrial(false); return false; }
                comparison = new RefillFrontier.Comparison<>(candidate, result, budget);
                phase = Phase.COMPARE; return false;
            }
            if (phase == Phase.COMPARE) {
                if (!comparison.step()) return false;
                // Equal/incomparable bills keep the old source preference.
                // Held stock may increase only for strictly smaller new supply.
                endTrial(comparison.improvesMissing());
                return false;
            }
            for (int i = 0; i < 64; i++) {
                budget.check();
                if (phase.ordinal() <= Phase.INPUTS.ordinal() && budget.nodes() - started >= scanAllowance) {
                    return beginTrials();
                }
                if (advance()) return true;
                if (phase == Phase.ORIGINAL || phase == Phase.RANK || phase == Phase.EXPAND) return false;
            }
            return false;
        } catch (PlanningBudget.Exhausted limit) {
            budget.failureDetail(retainedFailure);
            return finish();
        }
    }

    private boolean advance() {
        switch (phase) {
            case BINDINGS -> {
                // Check every old binding before a truncated hint scan is
                // allowed to start trials. Late per-push bindings matter too.
                if (recipes.hasNext()) {
                    var bound = recipes.next();
                    if (finiteBatchConfiguration(bound)) { declined = true; return finish(); }
                    ordinaryWord &= bound.inputs().size() == 1 && bound.outputs().size() == 1 &&
                            bound.configurationInputs().isEmpty() && !bound.inputs().keySet().equals(bound.outputs().keySet());
                }
                else { recipes = original.recipes().values().iterator(); phase = Phase.SCAN; }
            }
            case SCAN -> {
                if (!recipes.hasNext()) { scanComplete = true; return beginTrials(); }
                if (choices.size() == MAX_CHOICES) return beginTrials();
                recipe = recipes.next();
                ports = recipe.inputs().keySet().iterator(); outputs = false; phase = Phase.PORTS;
            }
            case PORTS -> {
                if (!ports.hasNext() && !outputs) { outputs = true; ports = recipe.outputs().keySet().iterator(); }
                if (!ports.hasNext()) { phase = Phase.SCAN; break; }
                key = ports.next();
                if (external.contains(key) || compiler.producers(key).size() < 2 || seen.contains(key)) break;
                reserve(96); seen.add(key);
                multihop = false; boundSources = jointSources = 0; local.clear(); sources = compiler.producers(key).iterator(); phase = Phase.SOURCES;
            }
            case SOURCES -> {
                if (!sources.hasNext()) {
                    boundChoicesSeen |= multihop && boundSources >= 2;
                    jointChoicesSeen |= jointSources >= 2;
                    if ((joint ? local.size() >= 2 : !reallocate || multihop && local.size() >= 2)) for (var option : local) {
                        if (choices.size() == MAX_CHOICES) break;
                        // The local list is constant-sized; charge retained hints.
                        reserve(96); choices.add(option);
                    }
                    local.clear(); sources = null; phase = Phase.PORTS; break;
                }
                var source = sources.next();
                if (excluded.contains(source.id()) || finiteBatchConfiguration(source) ||
                        source.outputs().getOrDefault(key, 0L) <= source.inputs().getOrDefault(key, 0L)) break;
                var binding = original.recipes().get(source.id());
                if (binding != null && !binding.equals(source)) break;
                if (binding != null) boundSources = Math.min(2, boundSources + 1);
                if (source.outputs().size() > 1) jointSources = Math.min(2, jointSources + 1);
                // Discovery of unused routes retains its old queue. A separate
                // continuation revisits materials with multiple bound producers;
                // a binding is a physical contract, not a fixed firing count.
                if ((joint ? source.outputs().size() > 1 : (binding != null) == reallocate) && local.size() < MAX_CHOICES)
                    local.add(new Choice<>(key, source));
                inputs = source.inputs().keySet().iterator(); phase = Phase.INPUTS;
            }
            case INPUTS -> {
                if (!inputs.hasNext() || multihop) { inputs = null; phase = Phase.SOURCES; break; }
                K input = inputs.next();
                multihop = !external.contains(input) && !compiler.producers(input).isEmpty();
            }
            case GOALS -> {
                if (entries.hasNext()) {
                    var e = entries.next(); reserve(256);
                    initial.put(e.getKey(), BigInteger.valueOf(e.getValue().longValue()));
                } else {
                    reserve(256); initial.merge(original.target(), BigInteger.valueOf(original.amount()), BigInteger::add);
                    entries = initial.entrySet().iterator(); phase = Phase.MISSING;
                }
            }
            case MISSING -> {
                if (entries.hasNext()) {
                    var e = entries.next();
                    BigInteger have = ignoreTargetStock && original.target().equals(e.getKey()) ? BigInteger.ZERO : PlanningInventory.exactAmount(stock, e.getKey());
                    BigInteger gap = ((BigInteger) e.getValue()).subtract(have);
                    if (gap.signum() > 0 && !external.contains(e.getKey())) missing.put(e.getKey(), gap);
                } else {
                    entries = null;
                    // Existing bindings are borrowed immutably: an alternative
                    // cannot silently change the physical meaning of an old ID.
                    boundary = GraphPlan.ownedRefill(original, new PlanStep.Sequence(List.of()), original.recipes(),
                            initial, missing, original.result(), budget.nodes());
                    if (order == null) {
                        order = new RefillProducerOrder<>(compiler, initial.keySet(), external, excluded, budget);
                        phase = Phase.RANK;
                    } else phase = Phase.TRIAL;
                }
            }
            case TRIAL -> {
                if (result.feasible()) return finish();
                if (!reallocate && !joint && ordinaryWord && !baselineTried) {
                    // A terminal bill can still encode an expensive or
                    // stock-starved single-source word. Normalize the whole
                    // demand once with ordinary funded mixtures before any
                    // preferred-source trial. Ordinary-word normalization uses
                    // the original request budget; the small optional hint quota
                    // must not stop a wide stock split halfway through.
                    baselineTried = true;
                    choice = null;
                    expansion = MissingMaterialExpansion.sourceAlternative(compiler, boundary, stock, external, excluded,
                            force, ignoreTargetStock, budget, order, credit, Map.of());
                    phase = Phase.EXPAND;
                    break;
                }
                if (joint) {
                    var pair = nextPair();
                    if (pair == null) return finish();
                    next++;
                    expansion = MissingMaterialExpansion.jointAlternative(compiler, boundary, stock, external, excluded,
                            force, ignoreTargetStock, budget, order, credit, pair);
                    phase = Phase.EXPAND;
                    break;
                } else if (reallocate) {
                    // Rebuilding one coordinate can reset another incumbent
                    // mixture to a worse default. Compare small joint choices,
                    // not a greedy sequence that rejects every such tradeoff.
                    if (!nextCombination()) return finish();
                    next++;
                } else {
                    if (next == choices.size()) return finish();
                    choice = choices.get(next++);
                    previousPreference = preferred.put(choice.key(), choice.source().id());
                }
                expansion = MissingMaterialExpansion.sourceAlternative(compiler, boundary, stock, external, excluded,
                        force, ignoreTargetStock, budget, order, credit, preferred);
                phase = Phase.EXPAND;
            }
            default -> throw new IllegalStateException("Child continuation must use its own slice");
        }
        return false;
    }

    private boolean beginTrials() {
        ports = inputs = null; sources = null; local.clear();
        if (choices.isEmpty()) return finish();
        normalizing = ordinaryWord && !reallocate && !joint;
        reserve(1024);
        var funded = GraphPlan.ownedRefill(original, original.steps(), original.recipes(), original.initialExact(), Map.of(),
                GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, budget.nodes());
        verification = PlanVerification.refill(funded, budget);
        phase = Phase.ORIGINAL;
        return false;
    }

    private void endTrial(boolean accept) {
        if (choice == null && baselineTried) normalizing = false;
        if (accept) {
            result = candidate; accepted++;
            // The retained word still owns its builders after the child closes.
            memory += expansion.takeResultCharge();
        } else if (!reallocate && !joint && choice != null) {
            if (previousPreference == null) preferred.remove(choice.key());
            else preferred.put(choice.key(), previousPreference);
        }
        expansion.close(); expansion = null; candidate = null; comparison = null;
        phase = Phase.TRIAL;
    }

    /** Discovery appends each material's options contiguously. Eight retained
     * hints have at most 18 Cartesian combinations, without an unbounded tree
     * or another allowance. Every word still needs full execution verification. */
    private boolean nextCombination() {
        if (!combinationsStarted) {
            for (int at = 0; at < choices.size();) {
                int start = at++;
                while (at < choices.size() && choices.get(at).key().equals(choices.get(start).key())) at++;
                groupStart[groups] = groupAt[groups] = start; groupEnd[groups++] = at;
            }
            combinationsStarted = true;
        } else {
            int group = groups - 1;
            while (group >= 0 && ++groupAt[group] == groupEnd[group]) {
                groupAt[group] = groupStart[group]; group--;
            }
            if (group < 0) return false;
        }
        preferred.clear();
        for (int group = 0; group < groups; group++) {
            var selected = choices.get(groupAt[group]); preferred.put(selected.key(), selected.source().id());
        }
        return true;
    }

    private RefillPairCounts.Choice<K> nextPair() {
        while (pairFirst < choices.size() - 1) {
            if (pairSecond == choices.size()) { pairSecond = ++pairFirst + 1; continue; }
            var a = choices.get(pairFirst); var b = choices.get(pairSecond);
            if (!a.key().equals(b.key())) { pairSecond++; continue; }
            var pair = reversePair ? new RefillPairCounts.Choice<>(a.key(), b.source(), a.source()) :
                    new RefillPairCounts.Choice<>(a.key(), a.source(), b.source());
            if (reversePair) pairSecond++;
            reversePair = !reversePair;
            return pair;
        }
        return null;
    }

    private boolean finish() {
        phase = Phase.DONE;
        if (!choices.isEmpty()) budget.note(joint ? "refill_joint_sources" : reallocate ? "refill_reallocate" : "refill_sources", "trials=" + next + "; accepted=" + accepted +
                "; work=" + (budget.nodes() - started));
        return true;
    }
    GraphPlan<K> result() { return result; }
    /** Only complete discovery can decline the old-bound-source follow-up.
     * Recipes added by successful unused-source trials need not immediately
     * repeat those same choices. A truncated scan must remain inconclusive. */
    boolean mayReallocate() { return !declined && (!scanComplete || boundChoicesSeen); }
    boolean mayJoint() { return !declined && jointChoicesSeen; }
    RefillProducerOrder<K> completedOrder() { return ranked ? order : null; }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }

    /** Finite consumed configurations depend on dispatch grouping. A truly
     * external configuration has no finite budget to invalidate when a source
     * changes; its original physical binding and exact prefix are still kept. */
    private boolean finiteBatchConfiguration(GraphRecipe<K> recipe) {
        if (!recipe.batchSensitiveInputs()) return false;
        for (var entry : recipe.configurationInputs().entrySet()) {
            budget.check();
            if (entry.getValue() > recipe.reusableInputs().getOrDefault(entry.getKey(), 0L) && !external.contains(entry.getKey()))
                return true;
        }
        return false;
    }
    @Override public void close() {
        if (verification != null) { verification.close(); verification = null; }
        if (expansion != null) { expansion.close(); expansion = null; }
        if (order != null) { if (!borrowedOrder) order.close(); order = null; }
        budget.release(memory); memory = 0;
    }
}
