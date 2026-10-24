// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Publication guard for a word produced by a restricted captured-domain arm.
 * The word's bindings do not describe all producers. Expand a bounded, complete
 * finite ancestor domain before applying the common startup necessary condition.
 * An unfinished check declines this proposal; it is never a negative proof.
 */
final class CapturedStartupVerification<K> implements AutoCloseable {
    private static final int MAX_VARIANTS = 2048, MAX_SOURCES = 256, MAX_PORTS = 32768;
    private final List<CapturedCatalog.Entry<K>> entries;
    private final Map<K, List<Integer>> producers;
    private final GraphPlan<K> plan;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final PlanningBudget budget;
    private final long started, allowance;
    private final SummaryComputation<K> computation;
    private record StateUse<K>(int entry, int slot, StateFamily<K> family) {}
    private final boolean stateFamilies;
    private final Set<K> known = new LinkedHashSet<>();
    private final List<StateUse<K>> uses = new ArrayList<>();
    private final IdentityHashMap<StateFamily<K>, List<CapturedRecipe.Candidate<K>>> states = new IdentityHashMap<>();
    private final Map<K, List<Integer>> stateProducers = new LinkedHashMap<>();
    private final Map<Integer, Map<Integer, Set<CapturedRecipe.Candidate<K>>>> stateChoices = new LinkedHashMap<>();
    private Iterator<K> stockKeys;
    private Iterator<GraphRecipe<K>> wordRecipes;
    private Iterator<K> wordInputs, wordOutputs;
    private int scanningEntry, scanningInput, scanningCandidate, scanningOutput, use;
    private Iterator<K> stateKeys;
    private StateFamily<K> activeFamily;
    private final Set<StateFamily<K>> slotFamilies = Collections.newSetFromMap(new IdentityHashMap<>());
    private Iterator<CapturedRecipe.Candidate<K>> applyingStates;
    private StateUse<K> applyingUse;
    private Iterator<StateFamily<K>> rootFamilies;
    private K checkedRoot;
    private boolean unknownReturn, checkedState;
    private CapturedWearBoundary<K> wearBoundary;
    private CapturedStateReturnClosure<K> stateClosure;
    private StateFamily<K> boundaryFamily;
    private final Set<StateFamily<K>> acyclicWear = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<GraphRecipe<K>> available = new ArrayList<>();
    private final Set<K> seen = new LinkedHashSet<>();
    private final Deque<K> pending = new ArrayDeque<>();
    private final List<K> consumed = new ArrayList<>();
    private int thresholdRoot, thresholdEntry, thresholdInput, thresholdCandidate;
    private BigInteger thresholdRecipe = BigInteger.ZERO, thresholdSlot = BigInteger.ZERO, thresholdFinal;
    private final Set<Integer> selected = new HashSet<>();
    private final List<CapturedRecipeDomain<K>> retained = new ArrayList<>();
    private SequenceSummary<K> summary;
    private Iterator<K> roots;
    private List<Integer> sources = List.of();
    private int source, variants, ports, phase;
    private CapturedRecipeDomain<K> domain;
    private CapturedCatalog.Entry<K> entry;
    private Iterator<K> dependencies;
    private SeedStartupGuard<K> guard;
    private long memory;
    private boolean done, accepted;
    private boolean targetChecked;
    private String reason = "unfinished";

    CapturedStartupVerification(List<CapturedCatalog.Entry<K>> entries, Map<K, List<Integer>> producers,
            GraphPlan<K> plan, Map<K, Long> stock, Set<K> external, PlanningBudget budget, boolean stateFamilies) {
        this.entries = entries; this.producers = producers; this.plan = plan;
        this.stock = stock; this.external = external; this.budget = budget;
        this.stateFamilies = stateFamilies;
        started = budget.nodes(); allowance = Math.min(131072, budget.remainingWork() / 2);
        computation = new SummaryComputation<>(plan.steps(), plan.recipes(), budget);
        if (stateFamilies) { stockKeys = stock.keySet().iterator(); wordRecipes = plan.recipes().values().iterator(); phase = -2; }
        if (!plan.preserveSeeds()) { accepted = done = true; reason = "ordinary_order"; }
    }

    boolean step() {
        if (done) return true;
        budget.checkpoint();
        if (budget.nodes() - started >= allowance) return decline("local_work_limit");
        try {
            budget.check();
            if (phase == -2) prepareStates();
            else if (phase == -1) expandStates();
            else if (phase == 0) {
                if (!computation.step()) return false;
                summary = computation.result(); roots = summary.required().keySet().iterator(); phase = 1;
            } else if (phase == 1) {
                if (roots.hasNext()) {
                    K key = roots.next();
                    if (SeedStartupGuard.spendsAfterDelivery(summary, key, plan.target(), plan.amount()) && !external.contains(key)) root(key);
                } else if (!targetChecked) {
                    targetChecked = true;
                    if (!external.contains(plan.target()) && SeedStartupGuard.spendsAfterDelivery(summary, plan.target(), plan.target(), plan.amount()))
                        root(plan.target());
                } else if (pending.isEmpty()) {
                    accepted = done = true; reason = "no_consumed_produced_inputs";
                } else phase = 4;
            } else if (phase == 2) expand();
            else if (phase == 4) checkRetained();
            else if (phase == 5) {
                if (!stateClosure.step()) return false;
                if (!stateClosure.complete()) return decline("state_return_limit");
                thresholdRoot = thresholdEntry = thresholdInput = thresholdCandidate = 0;
                thresholdFinal = null; thresholdRecipe = thresholdSlot = BigInteger.ZERO;
                phase = 4;
            }
            else if (guard.step()) {
                accepted = guard.lost().isEmpty(); done = true;
                reason = accepted ? "checked_finite_producers" : "lost_startup";
            }
        } catch (PlanningBudget.Exhausted exhausted) {
            if (exhausted.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw exhausted;
            return decline("workspace_declined");
        } catch (ArithmeticException unrepresentable) {
            return decline("unrepresentable_domain");
        }
        return done;
    }

    private void discover(K key) {
        if (seen.contains(key)) return;
        if (!reserve(128)) return;
        seen.add(key); pending.addLast(key);
    }

    private void root(K key) {
        if (seen.contains(key)) return;
        discover(key);
        if (done) return;
        consumed.add(key);
    }

    /** Every possible one-firing input demand is bounded independently per
     * slot, then summed within its recipe. Retaining this exact physical key
     * covers all startup thresholds without enumerating alternative products. */
    private void checkRetained() {
        if (thresholdRoot == consumed.size()) { accepted = done = true; reason = "retained_physical_startup"; return; }
        K key = consumed.get(thresholdRoot);
        if (thresholdFinal == null) {
            boolean produced = producers.containsKey(key) || stateProducers.containsKey(key) ||
                    stateFamilies && (stateClosure == null || stateClosure.returns(key));
            if (!produced) for (var family : states.keySet()) {
                budget.compilationCheck();
                if (family.accepts(key)) { produced = true; break; }
            }
            if (!produced) { thresholdRoot++; return; }
            thresholdFinal = PlanningInventory.exactAmount(stock, key).max(plan.initialExact().getOrDefault(key, BigInteger.ZERO)).add(summary.delta(key));
            if (key.equals(plan.target())) thresholdFinal = thresholdFinal.subtract(BigInteger.valueOf(plan.amount()));
        }
        if (thresholdEntry == entries.size()) {
            thresholdRoot++; thresholdEntry = thresholdInput = thresholdCandidate = 0;
            thresholdFinal = null; return;
        }
        var recipe = entries.get(thresholdEntry).recipe();
        if (thresholdInput == recipe.inputs().size()) {
            if (thresholdRecipe.compareTo(thresholdFinal) > 0) {
                if (stateFamilies && stateClosure == null) {
                    stateClosure = new CapturedStateReturnClosure<>(states, budget); phase = 5;
                } else phase = 2;
                return;
            }
            thresholdEntry++; thresholdInput = thresholdCandidate = 0;
            thresholdRecipe = thresholdSlot = BigInteger.ZERO; return;
        }
        var input = recipe.inputs().get(thresholdInput);
        if (thresholdCandidate < input.candidates().size()) {
            var candidate = input.candidates().get(thresholdCandidate++);
            if (candidate.stack().what().equals(key) || candidate.family() != null && candidate.family().accepts(key))
                thresholdSlot = thresholdSlot.max(BigInteger.valueOf(candidate.stack().amount()).multiply(BigInteger.valueOf(input.multiplier())));
        } else {
            thresholdRecipe = thresholdRecipe.add(thresholdSlot); thresholdSlot = BigInteger.ZERO;
            thresholdInput++; thresholdCandidate = 0;
        }
    }

    private void remember(K key) {
        if (key == null || known.contains(key)) return;
        if (known.size() == MAX_PORTS || !reserve(96)) { decline("state_boundary_limit"); return; }
        known.add(key);
    }

    /** Only exact physical states enter this boundary. A family identifier is
     * never an equivalence relation between entities or transformation rules. */
    private void prepareStates() {
        if (stockKeys.hasNext()) { remember(stockKeys.next()); return; }
        if (wordInputs != null) {
            if (wordInputs.hasNext()) remember(wordInputs.next());
            else if (wordOutputs.hasNext()) remember(wordOutputs.next());
            else { wordInputs = null; wordOutputs = null; }
            return;
        }
        if (wordRecipes.hasNext()) {
            var recipe = wordRecipes.next(); wordInputs = recipe.inputs().keySet().iterator(); wordOutputs = recipe.outputs().keySet().iterator(); return;
        }
        if (scanningEntry == entries.size()) { phase = -1; return; }
        var recipe = entries.get(scanningEntry).recipe();
        if (scanningOutput < recipe.outputs().size()) { remember(recipe.outputs().get(scanningOutput++).what()); return; }
        if (scanningInput < recipe.inputs().size()) {
            var input = recipe.inputs().get(scanningInput);
            if (scanningCandidate < input.candidates().size()) {
                var candidate = input.candidates().get(scanningCandidate++);
                remember(candidate.stack().what()); remember(candidate.remaining());
                var family = candidate.family();
                if (family != null) {
                    if (candidate.stack().amount() != 1 || candidate.configuration() || candidate.reusable()) { decline("unsupported_state_rule"); return; }
                    if (slotFamilies.add(family) && reserve(128)) uses.add(new StateUse<>(scanningEntry, scanningInput, family));
                }
            } else { scanningInput++; scanningCandidate = 0; slotFamilies.clear(); }
        } else { scanningEntry++; scanningInput = scanningCandidate = scanningOutput = 0; }
    }

    private void expandStates() {
        if (applyingStates != null) {
            if (applyingStates.hasNext()) {
                var candidate = applyingStates.next();
                if (!reserve(128)) return;
                stateChoices.computeIfAbsent(applyingUse.entry(), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(applyingUse.slot(), ignored -> new LinkedHashSet<>()).add(candidate);
                K after = candidate.remaining();
                if (after != null && !after.equals(candidate.stack().what())) {
                    var indices = stateProducers.computeIfAbsent(after, ignored -> new ArrayList<>());
                    if (indices.isEmpty() || indices.get(indices.size() - 1) != applyingUse.entry()) indices.add(applyingUse.entry());
                }
            } else { applyingStates = null; applyingUse = null; }
            return;
        }
        if (activeFamily != null) {
            if (stateKeys.hasNext()) {
                K before = stateKeys.next();
                if (!activeFamily.accepts(before)) return;
                long capacity = activeFamily.capacity(before);
                if (capacity <= 0) return;
                K after = activeFamily.nominalAfterOne(before);
                if (!reserve(192)) return;
                states.get(activeFamily).add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(before, 1), after, false));
            } else { activeFamily = null; stateKeys = null; }
            return;
        }
        if (use == uses.size()) { phase = 0; return; }
        var value = uses.get(use);
        if (!states.containsKey(value.family())) {
            if (!reserve(128)) return;
            states.put(value.family(), new ArrayList<>()); activeFamily = value.family(); stateKeys = known.iterator(); return;
        }
        use++; applyingUse = value; applyingStates = states.get(value.family()).iterator();
    }

    private void expand() {
        if (dependencies != null) {
            if (dependencies.hasNext()) discover(dependencies.next());
            else dependencies = null;
        } else if (domain != null) {
            if (domain.hasNext()) {
                if (++variants > MAX_VARIANTS) { decline("variant_limit"); return; }
                BigInteger ordinal = domain.coveredPrefix();
                var value = domain.next();
                ports += value.slots().size() + value.outputs().size();
                if (ports > MAX_PORTS || !reserve(256L + 64L * (value.slots().size() + value.outputs().size()))) {
                    decline("port_limit"); return;
                }
                var recipe = new GraphRecipe<>(entry.id() + "#startup#" + ordinal, entry.binding(), value.slots(), value.outputs());
                available.add(recipe);
                dependencies = recipe.inputs().keySet().iterator();
            } else { domain = null; entry = null; }
        } else if (source < sources.size()) {
            int index = sources.get(source++);
            if (!selected.add(index)) return;
            if (!reserve(96)) return;
            if (selected.size() > MAX_SOURCES) { decline("source_limit"); return; }
            entry = entries.get(index);
            var captured = entry.recipe();
            if (stateChoices.containsKey(index)) {
                var inputs = new ArrayList<CapturedRecipe.Input<K>>();
                for (int slot = 0; slot < captured.inputs().size(); slot++) {
                    budget.compilationCheck(); var input = captured.inputs().get(slot);
                    var candidates = new LinkedHashSet<>(input.candidates());
                    candidates.addAll(stateChoices.get(index).getOrDefault(slot, Set.of()));
                    if (!reserve(96L + 64L * candidates.size())) return;
                    inputs.add(new CapturedRecipe.Input<>(input.multiplier(), List.copyOf(candidates), input.mode()));
                }
                captured = new CapturedRecipe<>(inputs, captured.outputs(), captured.external(), true);
            }
            domain = captured.domain(budget); retained.add(domain);
            if (domain.size().compareTo(BigInteger.valueOf(MAX_VARIANTS - variants)) > 0) {
                decline("variant_limit"); return;
            }
        } else if (wearBoundary != null) {
            if (!wearBoundary.step()) return;
            boolean safe = wearBoundary.certified(); wearBoundary.close(); wearBoundary = null;
            if (!safe) { decline("open_state_feedback"); return; }
            acyclicWear.add(boundaryFamily); boundaryFamily = null;
        } else if (rootFamilies != null) {
            if (rootFamilies.hasNext()) {
                var family = rootFamilies.next();
                if (!family.accepts(checkedRoot)) return;
                checkedState = true;
                if (!acyclicWear.contains(family)) {
                    // A predecessor may exist between two captured durability
                    // states. Ordinary tool wear is allowed only with a
                    // separate no-entity-feedback certificate.
                    boundaryFamily = family; wearBoundary = new CapturedWearBoundary<>(entries, producers, family, stateClosure, budget);
                }
            } else {
                if (unknownReturn && !checkedState) { decline("unexpanded_state_return"); return; }
                rootFamilies = null; checkedRoot = null;
            }
        } else if (!pending.isEmpty()) {
            K key = pending.removeFirst();
            var finite = producers.getOrDefault(key, List.of());
            var dynamic = stateProducers.getOrDefault(key, List.of());
            if (dynamic.isEmpty()) sources = finite;
            else {
                if (!reserve(96L + 32L * (finite.size() + dynamic.size()))) return;
                var combined = new LinkedHashSet<>(finite); combined.addAll(dynamic); sources = List.copyOf(combined);
            }
            source = 0;
            if (stateFamilies) {
                checkedRoot = key; rootFamilies = states.keySet().iterator();
                unknownReturn = stateClosure.returns(key) && dynamic.isEmpty(); checkedState = false;
            }
        } else {
            // Concrete state returns already present in this word remain exact;
            // unused finite producers supplement rather than replace bindings.
            if (!reserve(64L * plan.recipes().size())) return;
            available.addAll(plan.recipes().values());
            guard = new SeedStartupGuard<>(available, summary, stock, plan.seeds(), external,
                    plan.target(), plan.amount(), budget);
            phase = 3;
        }
    }

    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) { decline("workspace_declined"); return false; }
        memory += bytes; return true;
    }

    private boolean decline(String why) { done = true; accepted = false; reason = why; return true; }
    boolean accepted() { return done && accepted; }
    String reason() { return reason; }

    @Override public void close() {
        computation.close();
        if (guard != null) guard.close(); guard = null;
        if (wearBoundary != null) wearBoundary.close(); wearBoundary = null;
        if (stateClosure != null) stateClosure.close(); stateClosure = null;
        for (var value : retained) value.close(); retained.clear(); domain = null;
        budget.release(memory); memory = 0;
        available.clear(); seen.clear(); pending.clear(); selected.clear(); consumed.clear();
        known.clear(); uses.clear(); states.clear(); stateProducers.clear(); stateChoices.clear();
        slotFamilies.clear(); applyingStates = null; applyingUse = null;
        acyclicWear.clear(); boundaryFamily = null;
    }
}
