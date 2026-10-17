// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Positive-only capacity proposal followed by exact physical-state restoration.
 * Capacity tokens exist exclusively in this auxiliary coordinate system. Every
 * activation spends a physical entity, and restoration keeps entities separate
 * until all slots of an operation have been allocated. No capacity relaxation
 * failure is a proof about the captured state family.
 */
final class CapturedStatePlanning<K> implements AutoCloseable {

    private record Key<K>(K physical, String family) {
        static <K> Key<K> physical(K key) { return new Key<>(key, null); }
        static <K> Key<K> capacity(String family) { return new Key<>(null, family); }
    }
    private record Activation<K>(K state, StateFamily<K> family) {}
    private record Returned<K>(K state, StateFamily<K> family, long amount) {}

    private final List<CapturedCatalog.Entry<K>> entries;
    private final K target;
    private final long amount, started = System.nanoTime();
    private final Map<K, Long> stock, seeds;
    private final Set<K> external;
    private final boolean preserve, force;
    private final PlanningBudget budget;
    private final long workStarted, allowance;
    private final Map<String, StateFamily<K>> families = new LinkedHashMap<>();
    private final Set<K> known = new LinkedHashSet<>();
    private final List<CapturedCatalog.Entry<Key<K>>> auxiliary = new ArrayList<>();
    private final Map<String, CapturedCatalog.Entry<K>> sources = new LinkedHashMap<>();
    private final Map<String, Activation<K>> activations = new LinkedHashMap<>();
    private final Map<String, Map<K, BigInteger>> pools = new LinkedHashMap<>();
    private final Map<K, BigInteger> free = new LinkedHashMap<>();
    private final Map<String, GraphRecipe<K>> restored = new LinkedHashMap<>();
    private final List<PlanStep> program = new ArrayList<>();
    private List<K> activationStates = List.of();
    private CapturedCatalog<Key<K>>.Work planning;
    private GraphPlan<Key<K>> proposed;
    private PlanCursor cursor;
    private AllocationSearch.Candidate<K> assembly;
    private PlanVerification<K> verification;
    private ForceCraftProof<K> production;
    private PlanningBudget.CandidateObservation observation;
    private GraphPlan<K> result;
    private int entry, activation;
    private long memory;
    private boolean prepared, done;

    CapturedStatePlanning(List<CapturedCatalog.Entry<K>> entries, K target, long amount, Map<K, Long> stock,
                          Set<K> external, Map<K, Long> seeds, boolean preserve, boolean force, PlanningBudget budget) {
        this.entries = entries; this.target = target; this.amount = amount; this.stock = stock;
        this.external = external; this.seeds = seeds; this.preserve = preserve; this.force = force; this.budget = budget;
        workStarted = budget.nodes(); allowance = budget.remainingWork() / 2;
        if (allowance < 1024 || !reserve(2048)) { done = true; return; }
        try {
            for (var value : stock.entrySet()) { budget.compilationCheck(); known.add(value.getKey()); free.put(value.getKey(), BigInteger.valueOf(value.getValue())); }
            known.addAll(external);
            for (var source : entries) {
                budget.compilationCheck();
                for (var output : source.recipe().outputs()) known.add(output.what());
                for (var input : source.recipe().inputs()) for (var candidate : input.candidates()) {
                    budget.compilationCheck(); known.add(candidate.stack().what());
                    if (candidate.remaining() != null) known.add(candidate.remaining());
                    var family = candidate.family();
                    if (family != null) {
                        if (family.id() == null || family.id().isEmpty()) throw new IllegalArgumentException("Empty state family identity");
                        families.putIfAbsent(family.id(), family);
                    }
                }
            }
            if (!reserve(160L * (known.size() + families.size()))) return;
            activationStates = List.copyOf(known);
            if (budget.proofJournal() != null) budget.proofJournal().markIncomplete();
            budget.note("candidate_state", "start; families=" + families.size() + "; known=" + known.size() +
                    "; allowance=" + allowance + "; negative_proofs=false");
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    boolean step() {
        if (done) return true;
        var observed = observation;
        long observedWork = observed == null ? 0 : budget.threadWork(), observedNanos = observed == null ? 0 : System.nanoTime();
        try (var costs = budget.trace(PlanningCostTrace.Origin.ALLOCATION,
                !prepared ? PlanningCostTrace.Stage.PREPARE : assembly == null ? PlanningCostTrace.Stage.SEARCH : PlanningCostTrace.Stage.VERIFY)) {
            budget.checkpoint();
            budget.phase(!prepared ? PlanningBudget.Phase.BUILD : assembly == null ? PlanningBudget.Phase.SOLVE : PlanningBudget.Phase.VERIFY);
            if (budget.nodes() - workStarted >= allowance) return stop("local_work_limit");
            if (!prepared) prepare();
            else if (verification != null) verify();
            else if (assembly != null) {
                if (assembly.step()) {
                    if (assembly.plan == null) return stop("unfunded_exact_restoration");
                    verification = new PlanVerification<>(assembly.plan, budget);
                }
            } else if (proposed != null) restore();
            else if (planning.step()) {
                proposed = planning.result().plan();
                planning.close(); planning = null;
                if (!proposed.feasible()) return stop("auxiliary_" + proposed.result());
                cursor = new PlanCursor(proposed.steps());
            }
            return done;
        } catch (PlanningBudget.Exhausted limit) {
            if (limit.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw limit;
            return stop("workspace_declined");
        } catch (ArithmeticException overflow) {
            return stop("unrepresentable_restoration");
        } finally { if (observed != null) observed.step(observedWork, observedNanos); }
    }

    private void prepare() {
        budget.compilationCheck();
        if (entry < entries.size()) {
            var source = entries.get(entry++);
            var inputs = new ArrayList<CapturedRecipe.Input<Key<K>>>();
            for (var input : source.recipe().inputs()) {
                var candidates = new ArrayList<CapturedRecipe.Candidate<Key<K>>>();
                var seen = new LinkedHashSet<String>();
                for (var candidate : input.candidates()) {
                    budget.compilationCheck();
                    if (candidate.family() == null) {
                        if (!reserve(128)) return;
                        candidates.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(Key.physical(candidate.stack().what()), candidate.stack().amount()),
                                candidate.remaining() == null ? null : Key.physical(candidate.remaining()), candidate.configuration(), candidate.reusable()));
                    } else {
                        // One state transition describes one entity, not an
                        // unspecified stack or dispatch-level configuration.
                        if (candidate.stack().amount() != 1 || candidate.configuration() || candidate.reusable()) continue;
                        var family = candidate.family();
                        if (seen.add(family.id())) {
                            if (!reserve(128)) return;
                            candidates.add(new CapturedRecipe.Candidate<>(
                                    new CapturedRecipe.Amount<>(Key.capacity(family.id()), 1), null, false));
                        }
                    }
                }
                if (candidates.isEmpty()) return; // This entry has no supported auxiliary interpretation.
                inputs.add(new CapturedRecipe.Input<>(input.multiplier(), candidates, input.mode()));
            }
            var outputs = new ArrayList<CapturedRecipe.Amount<Key<K>>>();
            for (var output : source.recipe().outputs()) outputs.add(new CapturedRecipe.Amount<>(Key.physical(output.what()), output.amount()));
            String id = "state_recipe_" + (entry - 1);
            sources.put(id, source);
            if (!reserve(256L + 160L * (inputs.size() + outputs.size()))) return;
            auxiliary.add(new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, outputs, source.recipe().external(), true)));
            return;
        }
        if (activation < activationStates.size()) {
            // Only initial / declared / ordinary produced states are activated.
            // Never close the remaining-state chain or enumerate capacity values.
            K state = activationStates.get(activation++);
            for (var family : families.values()) {
                budget.compilationCheck();
                if (!family.accepts(state)) continue;
                long capacity = family.capacity(state);
                if (capacity <= 0) continue;
                String id = "state_activate_" + activations.size();
                var input = new CapturedRecipe.Input<Key<K>>(1, List.of(new CapturedRecipe.Candidate<>(
                        new CapturedRecipe.Amount<>(Key.physical(state), 1), null, false)), CapturedRecipe.InputMode.INDIVISIBLE);
                var output = new CapturedRecipe.Amount<Key<K>>(Key.capacity(family.id()), capacity);
                if (!reserve(512)) return;
                activations.put(id, new Activation<>(state, family));
                auxiliary.add(new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(List.of(input), List.of(output), false, false)));
            }
            return;
        }
        var available = new LinkedHashMap<Key<K>, Long>();
        stock.forEach((key, count) -> available.put(Key.physical(key), count));
        var required = new LinkedHashMap<Key<K>, Long>();
        seeds.forEach((key, count) -> required.put(Key.physical(key), count));
        var emitted = new LinkedHashSet<Key<K>>();
        external.forEach(key -> emitted.add(Key.physical(key)));
        planning = new CapturedCatalog<>(auxiliary, false).begin(Key.physical(target), amount, available, emitted,
                required, preserve, force, budget);
        prepared = true;
    }

    private void restore() {
        budget.check();
        var batch = cursor.current();
        if (batch == null) {
            observation = budget.observeCandidate(PlanningBudget.CandidateOrigin.ALLOCATION);
            long work = budget.threadWork(), nanos = System.nanoTime();
            try {
                assembly = new AllocationSearch.Candidate<>(new PlanStep.Sequence(program), restored, target, amount, stock,
                        seeds, external, preserve, force, false, budget, started);
            } finally { if (observation != null) observation.step(work, nanos); }
            return;
        }
        var recipe = proposed.recipes().get(batch.recipe());
        var activating = activations.get(recipe.binding());
        if (activating != null) {
            BigInteger count = BigInteger.valueOf(batch.runs());
            if (!spend(activating.state(), count)) { stop("activation_ownership"); return; }
            pool(activating.family()).merge(activating.state(), count, BigInteger::add);
            cursor.dispatched(batch.runs()); return;
        }
        var source = sources.get(recipe.binding());
        if (source == null) { stop("unknown_auxiliary_binding"); return; }
        boolean stateful = recipe.slots().stream().anyMatch(slot -> slot.key().family() != null);
        long runs = stateful ? 1 : batch.runs();
        var slots = new ArrayList<GraphRecipe.Slot<K>>();
        var outputs = new LinkedHashMap<K, Long>();
        var returns = new ArrayList<Returned<K>>();
        for (var output : recipe.outputs().entrySet()) {
            if (output.getKey().family() != null) { stop("unexpected_capacity_output"); return; }
            outputs.merge(output.getKey().physical(), output.getValue(), Math::addExact);
        }
        for (var slot : recipe.slots()) {
            budget.check();
            if (slot.key().family() == null) {
                slots.add(new GraphRecipe.Slot<>(slot.key().physical(), slot.amount(), slot.inputSlot(), slot.configuration(), slot.reusable()));
            } else {
                var family = families.get(slot.key().family());
                var input = source.recipe().inputs().get(slot.inputSlot());
                if (!takeTools(family, slot, source.recipe().mixed(input), slots, returns)) {
                    stop("insufficient_distinct_entities"); return;
                }
            }
        }
        // The physical inventory excludes activated entities. Ordinary inputs
        // cannot silently spend a tool already assigned to a capacity pool.
        var ordinaryNeeded = new LinkedHashMap<K, BigInteger>();
        var ordinarySpent = new LinkedHashMap<K, BigInteger>();
        for (var slot : recipe.slots()) {
            if (slot.key().family() != null) continue;
            BigInteger quantity = BigInteger.valueOf(slot.amount()).multiply(BigInteger.valueOf(slot.configuration() ? 1 : runs));
            ordinaryNeeded.merge(slot.key().physical(), quantity, BigInteger::add);
            if (!slot.reusable()) ordinarySpent.merge(slot.key().physical(), quantity, BigInteger::add);
        }
        for (var needed : ordinaryNeeded.entrySet()) {
            if (!external.contains(needed.getKey()) && free.getOrDefault(needed.getKey(), BigInteger.ZERO).compareTo(needed.getValue()) < 0) {
                stop("ordinary_input_ownership"); return;
            }
        }
        ordinarySpent.forEach(this::spend);
        // Even a terminal fragment cannot fund an ordinary input of this same
        // operation. Release returns only after all physical inputs are owned.
        for (var value : returns) if (value.state() != null) {
            outputs.merge(value.state(), value.amount(), Math::addExact);
            if (value.family().accepts(value.state()) && value.family().capacity(value.state()) > 0)
                pool(value.family()).merge(value.state(), BigInteger.valueOf(value.amount()), BigInteger::add);
            else free.merge(value.state(), BigInteger.valueOf(value.amount()), BigInteger::add);
        }
        // Reusable configuration balances are logical read arcs, not physical
        // returns. Consumed configurations are charged once for this dispatch.
        for (var output : recipe.executionOutputs().entrySet())
            free.merge(output.getKey().physical(), BigInteger.valueOf(output.getValue()).multiply(BigInteger.valueOf(runs)), BigInteger::add);
        String id = "state_exact_" + restored.size();
        if (!reserve(256L + 112L * (slots.size() + outputs.size()))) return;
        var concrete = new GraphRecipe<>(id, source.binding(), slots, outputs);
        restored.put(id, concrete); program.add(new PlanStep.Batch(id, runs));
        cursor.dispatched(runs);
    }

    private boolean takeTools(StateFamily<K> family, GraphRecipe.Slot<Key<K>> slot, boolean mixed,
                              List<GraphRecipe.Slot<K>> slots, List<Returned<K>> returns) {
        long left = slot.amount();
        var pool = pool(family);
        var iterator = pool.entrySet().iterator();
        while (iterator.hasNext()) {
            var value = iterator.next();
            budget.check();
            K state = value.getKey();
            long before = family.capacity(state);
            if (!family.accepts(state) || before <= 0) return false;
            if (!mixed && value.getValue().compareTo(BigInteger.valueOf(left)) < 0) continue;
            long use = value.getValue().min(BigInteger.valueOf(left)).longValueExact();
            K after = family.nominalAfterOne(state);
            // Capacity is a guaranteed lower bound, possibly saturated or
            // infinite. An exact return may retain more than before - 1 uses
            // (zero wear / repair / saturation); it must never guarantee less.
            long afterCapacity = after != null && family.accepts(after) ? family.capacity(after) : 0;
            if (afterCapacity < before - 1) return false;
            BigInteger remaining = value.getValue().subtract(BigInteger.valueOf(use));
            if (remaining.signum() == 0) iterator.remove();
            else value.setValue(remaining);
            slots.add(new GraphRecipe.Slot<>(state, use, slot.inputSlot()));
            returns.add(new Returned<>(after, family, use));
            left -= use;
            if (left == 0) return true;
        }
        return false;
    }

    private Map<K, BigInteger> pool(StateFamily<K> family) {
        return pools.computeIfAbsent(family.id(), ignored -> new LinkedHashMap<>());
    }

    private boolean spend(K key, BigInteger amount) {
        BigInteger available = free.getOrDefault(key, BigInteger.ZERO);
        if (available.compareTo(amount) < 0 && !external.contains(key)) return false;
        free.put(key, available.subtract(amount).max(BigInteger.ZERO));
        return true;
    }

    private void verify() {
        if (!verification.step()) return;
        if (force) {
            if (production == null) production = new ForceCraftProof<>(assembly.plan, verification, seeds, budget);
            if (!production.step()) return;
            if (!production.proved()) { stop("force_craft_not_proved"); return; }
        }
        result = assembly.plan; done = true;
        finishObservation(PlanningBudget.CandidateOutcome.ACCEPTED);
        budget.note("candidate_state", "verified_exact_execution; variants=" + restored.size() +
                "; work=" + (budget.nodes() - workStarted));
    }

    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) { stop("workspace_declined"); return false; }
        memory += bytes; return true;
    }

    private boolean stop(String reason) {
        done = true; finishObservation(PlanningBudget.CandidateOutcome.INCONCLUSIVE);
        budget.note("candidate_state", "declined; reason=" + reason); return true;
    }

    GraphPlan<K> result() { return result; }

    CompletableFuture<?> waitingFor() { return planning == null ? null : planning.waitingFor(); }

    private void finishObservation(PlanningBudget.CandidateOutcome outcome) {
        if (observation != null) observation.finish(outcome);
        observation = null;
    }

    @Override public void close() {
        finishObservation(PlanningBudget.CandidateOutcome.ABANDONED);
        if (planning != null) planning.close(); planning = null;
        if (production != null) production.close(); production = null;
        if (verification != null) verification.close(); verification = null;
        if (assembly != null) assembly.close(); assembly = null;
        cursor = null; proposed = null;
        families.clear(); known.clear(); activationStates = List.of(); auxiliary.clear(); sources.clear(); activations.clear(); pools.clear();
        free.clear(); restored.clear(); program.clear();
        budget.release(memory); memory = 0; done = true;
    }
}
