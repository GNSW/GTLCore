// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
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

    private record Key<K>(K physical, String family, boolean entity, List<String> group) {
        static <K> Key<K> physical(K key) { return new Key<>(key, null, false, null); }
        static <K> Key<K> capacity(String family) { return new Key<>(null, family, false, null); }
        static <K> Key<K> entities(String family) { return new Key<>(null, family, true, null); }
        static <K> Key<K> entities(List<String> group) { return new Key<>(null, "", true, group); }
    }
    private record Activation<K>(K state, List<String> families) {}
    private record Returned<K>(K before, K state, StateFamily<K> family, long amount) {}
    // The auxiliary variant pins the source binding, candidate choices and
    // family contracts. Equal net columns alone never identify a transition.
    private record Transition<K>(String variant, List<GraphRecipe.Slot<K>> slots, Map<K, Long> outputs) {}
    private record ToolGroup(String family, List<StateEntityAllocation.Demand> demands) {}

    enum Status { RUNNING, VERIFIED, DECLINED, LIMITED }
    enum Reason {
        NONE, LOCAL_WORK_LIMIT, WORKSPACE_DECLINED, AUXILIARY_RESULT, ACTIVATION_OWNERSHIP,
        UNKNOWN_AUXILIARY_BINDING, UNEXPECTED_CAPACITY_OUTPUT, INSUFFICIENT_DISTINCT_ENTITIES,
        INVALID_STATE_ALGEBRA, ORDINARY_INPUT_OWNERSHIP, UNFUNDED_EXACT_RESTORATION,
        FORCE_CRAFT_NOT_PROVED, UNREPRESENTABLE_RESTORATION
    }
    record Outcome(Status status, Reason reason) {}

    private final List<CapturedCatalog.Entry<K>> entries;
    private final K target;
    private final long amount, started = System.nanoTime();
    private final Map<K, Long> stock, seeds;
    private final Set<K> external;
    private final boolean preserve, force;
    private final PlanningBudget budget;
    private final long workStarted, allowance;
    private final Map<String, StateFamily<K>> families = new LinkedHashMap<>();
    private final Set<String> entityFamilies = new LinkedHashSet<>();
    private final Set<K> known = new LinkedHashSet<>();
    private final List<CapturedCatalog.Entry<Key<K>>> auxiliary = new ArrayList<>();
    private final Map<String, CapturedCatalog.Entry<K>> sources = new LinkedHashMap<>();
    private final Map<String, Activation<K>> activations = new LinkedHashMap<>();
    private final Map<String, Map<K, BigInteger>> pools = new LinkedHashMap<>();
    private final Map<K, BigInteger> free = new LinkedHashMap<>();
    private final Map<String, GraphRecipe<K>> restored = new LinkedHashMap<>();
    private final Map<Transition<K>, String> transitions = new LinkedHashMap<>();
    private final Map<String, List<ToolGroup>> toolGroups = new LinkedHashMap<>();
    private final Map<Integer, List<StateEntityAllocation.Pick<K>>> toolChoices = new LinkedHashMap<>();
    private final Map<String, Long> capacityCaps = new LinkedHashMap<>();
    private final Map<String, Long> entityFloors = new LinkedHashMap<>();
    private final Map<List<String>, Long> entityUnionFloors = new LinkedHashMap<>();
    private final Map<String, BigInteger> unclaimed = new LinkedHashMap<>(), advanced = new LinkedHashMap<>();
    private final List<PlanStep> program = new ArrayList<>();
    private List<K> activationStates = new ArrayList<>();
    private Iterator<Map.Entry<K, Long>> captureStock;
    private Iterator<K> captureExternal;
    private int captureSource, captureOutput, captureInput, captureCandidate;
    private boolean captureStarted, captured;
    private CapturedCatalog<Key<K>>.Work planning;
    private CapturedProposalRepair<Key<K>> proposalRepair;
    private CapturedStateExpansion<K> expansion;
    private GraphPlan<Key<K>> proposed;
    private PlanCursor cursor;
    private PlanStep restorationWord;
    private StateEntityAllocation<K> allocation;
    private StateEntityMatching<K> matching;
    private StateChoiceRepair<K> choiceRepair;
    private int choiceCandidates;
    private StateScheduleRepair<Key<K>> orderRepair;
    private StateFamily<K> orderFailure;
    private boolean orderTried, searchingOrder;
    private int orderCandidates;
    private StateRestorationCycle<K> cycle;
    private String cycleVariant;
    private boolean cycleDisabled;
    private StateRestorationCycle<K> loopCycle;
    private PlanCursor.LoopBoundary loopAnchor, declinedLoop;
    private boolean loopEvidence;
    private AllocationSearch.Candidate<K> assembly;
    private PlanVerification<K> verification;
    private ForceCraftProof<K> production;
    private PlanningBudget.CandidateObservation observation;
    private GraphPlan<K> result;
    private int entry, activation;
    private long memory;
    private long restoreWork, restoreCalls;
    private long proposalMemory, choiceMemory;
    private int selectingGroup, refinements, activationAdvances;
    private long programNodes = 1; // Includes the root sequence before optimization.
    private BigInteger restoredFirings = BigInteger.ZERO;
    private BigInteger cycleFirings = BigInteger.ZERO;
    private int cycleJumps;
    private int loopJumps;
    private BigInteger loopFirings = BigInteger.ZERO;
    private Outcome outcome = new Outcome(Status.RUNNING, Reason.NONE);
    private boolean prepared, done;

    CapturedStatePlanning(List<CapturedCatalog.Entry<K>> entries, K target, long amount, Map<K, Long> stock,
                          Set<K> external, Map<K, Long> seeds, boolean preserve, boolean force, PlanningBudget budget) {
        this(entries, target, amount, stock, external, seeds, preserve, force, budget, budget.remainingWork() / 2);
    }

    CapturedStatePlanning(List<CapturedCatalog.Entry<K>> entries, K target, long amount, Map<K, Long> stock,
                          Set<K> external, Map<K, Long> seeds, boolean preserve, boolean force, PlanningBudget budget, long maximumWork) {
        this.entries = entries; this.target = target; this.amount = amount; this.stock = stock;
        this.external = external; this.seeds = seeds; this.preserve = preserve; this.force = force; this.budget = budget;
        workStarted = budget.nodes(); allowance = Math.min(maximumWork, budget.remainingWork() / 2);
        if (allowance < 1024) { stop(Reason.LOCAL_WORK_LIMIT); return; }
        if (!reserve(2048)) return;
        try {
            captureStock = stock.entrySet().iterator();
            captureExternal = external.iterator();
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    boolean step() {
        if (done) return true;
        var observed = observation;
        long observedWork = observed == null ? 0 : budget.threadWork(), observedNanos = observed == null ? 0 : System.nanoTime();
        try (var costs = budget.trace(PlanningCostTrace.Origin.ALLOCATION,
                !prepared ? PlanningCostTrace.Stage.PREPARE : searchingOrder ? PlanningCostTrace.Stage.SCHEDULE : verification != null ? PlanningCostTrace.Stage.VERIFY :
                        assembly != null ? PlanningCostTrace.Stage.ASSEMBLE : proposed != null ? PlanningCostTrace.Stage.RESTORE : PlanningCostTrace.Stage.SEARCH)) {
            budget.checkpoint();
            budget.phase(!prepared ? PlanningBudget.Phase.BUILD : assembly == null ? PlanningBudget.Phase.SOLVE : PlanningBudget.Phase.VERIFY);
            if (budget.nodes() - workStarted >= allowance) return stop(Reason.LOCAL_WORK_LIMIT);
            if (!captured) capture();
            else if (!prepared) prepare();
            else if (expansion != null) {
                if (expansion.step()) {
                    result = expansion.result();
                    expansion.close(); expansion = null;
                    if (result == null) return stop(Reason.AUXILIARY_RESULT);
                    done = true; outcome = new Outcome(Status.VERIFIED, Reason.NONE);
                }
            }
            else if (proposalRepair != null) {
                if (proposalRepair.step()) {
                    proposed = proposalRepair.result();
                    proposalRepair.close(); proposalRepair = null;
                    if (proposed == null) beginProposal();
                    else { proposalMemory = memory; startRestoration(proposed.steps()); }
                }
            }
            else if (searchingOrder) reschedule();
            else if (verification != null) verify();
            else if (assembly != null) {
                if (assembly.step()) {
                    if (assembly.plan == null) {
                        if (continueOrder()) return false;
                        if (expandStateBoundary(Reason.UNFUNDED_EXACT_RESTORATION)) return false;
                        return stop(Reason.UNFUNDED_EXACT_RESTORATION);
                    }
                    verification = new PlanVerification<>(assembly.plan, budget);
                }
            } else if (proposed != null) restore();
            else if (planning.step()) {
                proposed = planning.result().plan();
                planning.close(); planning = null;
                if (!proposed.feasible()) {
                    proposed = null;
                    if (expandStateBoundary(Reason.AUXILIARY_RESULT)) return false;
                    return stop(Reason.AUXILIARY_RESULT);
                }
                proposalMemory = memory;
                startRestoration(proposed.steps());
            }
            return done;
        } catch (PlanningBudget.Exhausted limit) {
            if (limit.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw limit;
            return stop(Reason.WORKSPACE_DECLINED);
        } catch (ArithmeticException overflow) {
            return stop(Reason.UNREPRESENTABLE_RESTORATION);
        } finally { if (observed != null) observed.step(observedWork, observedNanos); }
    }

    private boolean expandStateBoundary(Reason reason) {
        // Capacity activation omits exact returned states as ordinary material,
        // final seeds and newly eligible rules. Retry a fresh finite capture;
        // its negative results cannot prove anything about the original domain.
        finishObservation(PlanningBudget.CandidateOutcome.INCONCLUSIVE);
        if (proposed != null) resetProposal();
        // Activation has already checked eligibility for these exact keys.
        Iterable<K> eligible = () -> activations.values().stream().map(Activation::state).iterator();
        long remaining = Math.max(0, allowance - (budget.nodes() - workStarted));
        expansion = CapturedStateExpansion.create(entries, eligible, target, amount, stock, external, seeds, preserve, force, budget, remaining / 2);
        if (expansion == null) return false;
        budget.note("candidate_state", "expand_boundary; reason=" + reason + "; negative_proofs=false");
        return true;
    }

    /** Retained discovery stays inside the arm's allowance before building its view. */
    private void capture() {
        if (captureStock.hasNext()) {
            budget.compilationCheck();
            var value = captureStock.next();
            if (rememberKey(value.getKey())) free.put(value.getKey(), BigInteger.valueOf(value.getValue()));
        } else if (captureExternal.hasNext()) {
            budget.compilationCheck(); rememberKey(captureExternal.next());
        } else if (captureSource < entries.size()) {
            var recipe = entries.get(captureSource).recipe();
            if (!captureStarted) {
                budget.compilationCheck(); captureStarted = true;
            } else if (captureOutput < recipe.outputs().size()) {
                budget.compilationScan(); rememberKey(recipe.outputs().get(captureOutput++).what());
            } else if (captureInput < recipe.inputs().size()) {
                var choices = recipe.inputs().get(captureInput).candidates();
                if (captureCandidate < choices.size()) {
                    budget.compilationCheck(); var choice = choices.get(captureCandidate++);
                    if (!rememberKey(choice.stack().what()) || choice.remaining() != null && !rememberKey(choice.remaining())) return;
                    var family = choice.family();
                    if (family != null) {
                        String id = family.id();
                        if (id == null || id.isEmpty()) throw new IllegalArgumentException("Empty state family identity");
                        if (!families.containsKey(id) && reserve(160)) families.put(id, family);
                    }
                } else {
                    budget.compilationScan(); captureInput++; captureCandidate = 0;
                }
            } else {
                budget.compilationScan(); captureSource++; captureOutput = captureInput = captureCandidate = 0; captureStarted = false;
            }
        } else {
            budget.compilationCheck(); captured = true; captureStock = null; captureExternal = null;
            if (budget.proofJournal() != null) budget.proofJournal().markIncomplete();
            budget.note("candidate_state", "start; families=" + families.size() + "; known=" + known.size() +
                    "; allowance=" + allowance + "; negative_proofs=false");
        }
    }

    private boolean rememberKey(K key) {
        if (known.contains(key)) return true;
        if (!reserve(160)) return false;
        known.add(key); activationStates.add(key); return true;
    }

    private void prepare() {
        budget.compilationCheck();
        if (entry < entries.size()) {
            var source = entries.get(entry++);
            var inputs = new ArrayList<CapturedRecipe.Input<Key<K>>>();
            var entities = new LinkedHashMap<String, Long>();
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
                // A fixed family choice needs all these entities simultaneously.
                // Ambiguous family/ordinary choices keep the capacity-only view;
                // independent marginal choices must not invent a slot relation.
                String family = candidates.get(0).stack().what().family();
                if (family != null && candidates.stream().allMatch(c -> family.equals(c.stack().what().family())))
                    entities.merge(family, input.multiplier(), Math::addExact);
            }
            for (var need : entities.entrySet()) {
                // Positive capacity already requires at least one activation.
                // Do not enlarge ordinary one-tool graphs with a redundant arc.
                if (need.getValue() <= 1) continue;
                budget.compilationCheck();
                if (!reserve(256)) return;
                entityFamilies.add(need.getKey());
                inputs.add(new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(
                        new CapturedRecipe.Amount<>(Key.entities(need.getKey()), need.getValue()), null, true, true)),
                        CapturedRecipe.InputMode.INDIVISIBLE));
            }
            if (shared()) {
                long total = 0;
                for (long need : entities.values()) total = Math.addExact(total, need);
                if (total > 1) {
                    if (!reserve(256)) return;
                    entityFamilies.add("");
                    inputs.add(new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(
                            new CapturedRecipe.Amount<>(Key.entities(""), total), null, true, true)), CapturedRecipe.InputMode.INDIVISIBLE));
                }
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
            var offered = new ArrayList<String>();
            var outputs = new ArrayList<CapturedRecipe.Amount<Key<K>>>();
            for (var family : families.values()) {
                budget.compilationCheck();
                if (!family.accepts(state)) continue;
                long capacity = family.capacity(state);
                if (capacity <= 0) continue;
                if (!reserve(shared() || entityFamilies.contains(family.id()) ? 256 : 128)) return;
                offered.add(family.id());
                outputs.add(new CapturedRecipe.Amount<>(Key.capacity(family.id()), capacity));
                if (shared() || entityFamilies.contains(family.id())) outputs.add(new CapturedRecipe.Amount<>(Key.entities(family.id()), 1));
            }
            if (offered.isEmpty()) return;
            // One activation owns one physical entity, even if several wear
            // rules accept it. Marginal capacities are a proposal relaxation;
            // exact restoration charges all uses to this one shared inventory.
            if (entityFamilies.contains("")) {
                if (!reserve(128)) return;
                outputs.add(new CapturedRecipe.Amount<>(Key.entities(""), 1));
            }
            if (!reserve(384)) return;
            String id = "state_activate_" + activations.size();
            var input = new CapturedRecipe.Input<Key<K>>(1, List.of(new CapturedRecipe.Candidate<>(
                    new CapturedRecipe.Amount<>(Key.physical(state), 1), null, false)), CapturedRecipe.InputMode.INDIVISIBLE);
            activations.put(id, new Activation<>(state, List.copyOf(offered)));
            auxiliary.add(new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(List.of(input), outputs, false, false)));
            return;
        }
        beginProposal();
        prepared = true;
    }

    private void beginProposal() {
        beginProposal(null);
    }

    private void beginProposal(Map<String, BigInteger> previous) {
        var available = new LinkedHashMap<Key<K>, Long>();
        stock.forEach((key, count) -> available.put(Key.physical(key), count));
        var required = new LinkedHashMap<Key<K>, Long>();
        for (var seed : seeds.entrySet()) {
            // Activation removes a physical key only in the proposal. Its exact
            // final state is unknown here; retaining that same key as an auxiliary
            // seed would reject even an unchanged returned tool. The original
            // seed requirement remains mandatory in assembly and verification.
            boolean state = false;
            for (var family : families.values()) {
                budget.compilationCheck();
                if (family.accepts(seed.getKey())) { state = true; break; }
            }
            if (!state) required.put(Key.physical(seed.getKey()), seed.getValue());
        }
        entityFloors.forEach((family, count) -> required.put(Key.entities(family), count));
        entityUnionFloors.forEach((group, count) -> required.put(Key.entities(group), count));
        var emitted = new LinkedHashSet<Key<K>>();
        external.forEach(key -> emitted.add(Key.physical(key)));
        proposalRepair = CapturedProposalRepair.create(auxiliary, previous, Key.physical(target), amount, available, emitted,
                required, preserve, force, budget);
        if (proposalRepair != null) return;
        planning = new CapturedCatalog<>(auxiliary, false).begin(Key.physical(target), amount, available, emitted,
                required, preserve, force, budget);
    }

    private void restore() {
        long before = budget.threadWork();
        budget.check();
        if (cursor.hasMixedLoops()) observeLoop();
        var batch = cursor.current();
        if (batch == null) {
            restoreWork += budget.threadWork() - before;
            assembly = new AllocationSearch.Candidate<>(new PlanStep.Sequence(program), restored, target, amount, stock,
                    seeds, external, preserve, force, false, budget, started);
            return;
        }
        try { restore(batch); }
        finally { restoreWork += budget.threadWork() - before; restoreCalls++; }
    }

    private void restore(PlanStep.Batch batch) {
        var recipe = proposed.recipes().get(batch.recipe());
        if (!recipe.id().equals(cycleVariant)) {
            clearCycle(); cycleVariant = recipe.id(); cycleDisabled = false;
        }
        var activating = activations.get(recipe.binding());
        if (activating != null) {
            // Even a credited activation changes the proposal ownership ledger;
            // it cannot be silently included in a repeated physical word.
            clearLoop();
            BigInteger count = BigInteger.valueOf(batch.runs());
            BigInteger credited = advanced.getOrDefault(recipe.binding(), BigInteger.ZERO).min(count);
            if (credited.signum() > 0) advanced.compute(recipe.binding(), (key, value) -> value.subtract(credited));
            count = count.subtract(credited);
            if (count.signum() > 0) {
                if (!claim(recipe.binding(), activating, count)) { stop(Reason.ACTIVATION_OWNERSHIP); return; }
            }
            cursor.dispatched(batch.runs()); return;
        }
        var source = sources.get(recipe.binding());
        if (source == null) { stop(Reason.UNKNOWN_AUXILIARY_BINDING); return; }
        if (!selectTools(recipe, source)) return;
        boolean stateful = recipe.slots().stream().anyMatch(slot -> slot.key().family() != null);
        long runs = stateful ? 1 : batch.runs();
        var slots = new ArrayList<GraphRecipe.Slot<K>>();
        var outputs = new LinkedHashMap<K, Long>();
        var returns = new ArrayList<Returned<K>>();
        for (var output : recipe.outputs().entrySet()) {
            if (output.getKey().entity()) continue; // Auxiliary read-arc balance, never a real return.
            if (output.getKey().family() != null) { stop(Reason.UNEXPECTED_CAPACITY_OUTPUT); return; }
            outputs.merge(output.getKey().physical(), output.getValue(), Math::addExact);
        }
        int ordinal = -1;
        for (var slot : recipe.slots()) {
            ordinal++;
            budget.check();
            if (slot.key().entity()) continue;
            if (slot.key().family() == null) {
                slots.add(new GraphRecipe.Slot<>(slot.key().physical(), slot.amount(), slot.inputSlot(), slot.configuration(), slot.reusable()));
            } else {
                var family = families.get(slot.key().family());
                var input = source.recipe().inputs().get(slot.inputSlot());
                var selected = toolChoices.get(ordinal);
                boolean taken = selected == null ? takeTools(family, slot, source.recipe().mixed(input), slots, returns) :
                        takeSelected(family, slot, selected, slots, returns);
                if (!taken) {
                    if (!done) allocationFailed(family, null);
                    return;
                }
            }
        }
        // The physical inventory excludes activated entities. Ordinary inputs
        // cannot silently spend a tool already assigned to a capacity pool.
        var ordinaryNeeded = new LinkedHashMap<K, BigInteger>();
        var ordinarySpent = new LinkedHashMap<K, BigInteger>();
        var ordinaryProduced = new LinkedHashMap<K, BigInteger>();
        for (var slot : recipe.slots()) {
            if (slot.key().family() != null) continue;
            BigInteger quantity = BigInteger.valueOf(slot.amount()).multiply(BigInteger.valueOf(slot.configuration() ? 1 : runs));
            ordinaryNeeded.merge(slot.key().physical(), quantity, BigInteger::add);
            if (!slot.reusable()) ordinarySpent.merge(slot.key().physical(), quantity, BigInteger::add);
        }
        // A fixed point of the immutable exact transition is repeatable without
        // enumerating unused states. Keep serial firing boundaries: consumed
        // configurations are paid once per iteration, and tools return before
        // the next one. An arbitrary changing-state callback has no such shortcut.
        long repetitions = stateful && returns.stream().allMatch(r -> r.before().equals(r.state())) ? batch.runs() : 1;
        boolean repeating = repetitions > 1;
        if (repeating) for (var output : recipe.executionOutputs().entrySet()) {
            if (output.getKey().entity()) continue;
            ordinaryProduced.merge(output.getKey().physical(), BigInteger.valueOf(output.getValue()), BigInteger::add);
        }
        for (var needed : ordinaryNeeded.entrySet()) {
            if (repeating) budget.check();
            if (external.contains(needed.getKey())) continue;
            BigInteger available = free.getOrDefault(needed.getKey(), BigInteger.ZERO);
            if (available.compareTo(needed.getValue()) < 0) {
                if (continueOrder()) return;
                stop(Reason.ORDINARY_INPUT_OWNERSHIP); return;
            }
            if (repeating) {
                BigInteger loss = ordinarySpent.getOrDefault(needed.getKey(), BigInteger.ZERO)
                        .subtract(ordinaryProduced.getOrDefault(needed.getKey(), BigInteger.ZERO));
                if (loss.signum() > 0) repetitions = Math.min(repetitions,
                        ExactAmounts.capped(available.subtract(needed.getValue()).divide(loss).add(BigInteger.ONE)));
            }
        }
        if (repeating) {
            BigInteger repeat = BigInteger.valueOf(repetitions);
            var ordinaryKeys = new LinkedHashSet<>(ordinarySpent.keySet());
            ordinaryKeys.addAll(ordinaryProduced.keySet());
            for (K key : ordinaryKeys) {
                budget.check();
                BigInteger delta = ordinaryProduced.getOrDefault(key, BigInteger.ZERO).subtract(ordinarySpent.getOrDefault(key, BigInteger.ZERO));
                free.put(key, free.getOrDefault(key, BigInteger.ZERO).add(delta.multiply(repeat)).max(BigInteger.ZERO));
            }
        } else ordinarySpent.forEach(this::spend);
        // Even a terminal fragment cannot fund an ordinary input of this same
        // operation. Release returns only after all physical inputs are owned.
        boolean retainedEntities = true;
        for (var value : returns) {
            if (value.state() == null) { retainedEntities = false; continue; }
            outputs.merge(value.state(), value.amount(), Math::addExact);
            if (usable(value.state(), value.family()))
                pool(value.family()).merge(value.state(), BigInteger.valueOf(value.amount()), BigInteger::add);
            else {
                retainedEntities = false;
                free.merge(value.state(), BigInteger.valueOf(value.amount()), BigInteger::add);
            }
        }
        // Keep the simple one-dispatch path for changing states and ordinary
        // batches; it needs neither repeat-bound arithmetic nor a delta overlay.
        if (!repeating) for (var output : recipe.executionOutputs().entrySet()) {
            if (!output.getKey().entity())
                free.merge(output.getKey().physical(), BigInteger.valueOf(output.getValue()).multiply(BigInteger.valueOf(runs)), BigInteger::add);
        }
        var transition = new Transition<>(recipe.id(), slots, outputs);
        String id = transitions.get(transition);
        boolean reused = id != null;
        if (id == null) {
            if (!reserve(384L + 112L * (slots.size() + outputs.size()))) return;
            id = "state_exact_" + restored.size();
            var concrete = new GraphRecipe<>(id, source.binding(), slots, outputs);
            restored.put(id, concrete);
            transitions.put(new Transition<>(recipe.id(), concrete.slots(), concrete.outputs()), id);
        }
        if (!append(id, runs, repetitions)) return;
        long dispatched = Math.multiplyExact(runs, repetitions);
        restoredFirings = restoredFirings.add(BigInteger.valueOf(dispatched));
        cursor.dispatched(dispatched);
        clearSelection();
        if (cursor.hasMixedLoops()) {
            if (!retainedEntities) clearLoop();
            else {
                loopEvidence |= stateful && reused;
                if (loopCycle != null) recordLoop(recipe, id, runs, repetitions, ordinaryNeeded, ordinarySpent);
            }
        }
        if (stateful && retainedEntities && repetitions == 1 && batch.runs() > 1)
            repeatCycle(recipe, id, reused, batch.runs() - 1, ordinaryNeeded, ordinarySpent);
        else {
            clearCycle();
            // An exact count can span several long-sized cursor windows.
            // A completed window must not disable reuse in the next one.
            if (batch.runs() == dispatched) cycleDisabled = false;
        }
    }

    private void repeatCycle(GraphRecipe<Key<K>> recipe, String transition, boolean reused, long remaining,
                             Map<K, BigInteger> needed, Map<K, BigInteger> spent) {
        if (cycleDisabled) return;
        // Distinct one-way state chains do not allocate a snapshot on every
        // firing. A repeated exact transition is the first evidence of a word.
        if (cycle == null) {
            if (reused) {
                cycle = StateRestorationCycle.start(transition, pools, budget);
                if (cycle == null) cycleDisabled = true;
            }
            return;
        }
        long temporary = 64L + 160L * recipe.executionOutputs().size();
        if (!budget.tryReserve(temporary)) { clearCycle(); cycleDisabled = true; return; }
        try { recordCycle(recipe, transition, remaining, needed, spent); }
        finally { budget.release(temporary); }
    }

    private void recordCycle(GraphRecipe<Key<K>> recipe, String transition, long remaining,
                             Map<K, BigInteger> needed, Map<K, BigInteger> spent) {
        var produced = new LinkedHashMap<K, BigInteger>();
        for (var output : recipe.executionOutputs().entrySet()) {
            budget.check();
            if (!output.getKey().entity()) produced.merge(output.getKey().physical(), BigInteger.valueOf(output.getValue()), BigInteger::add);
        }
        if (!cycle.record(transition, needed, spent, produced)) {
            clearCycle(); cycleDisabled = true; return;
        }
        if (!cycle.matches(transition, pools)) return;
        long copies = cycle.copies(remaining, free, external);
        if (copies > 0) {
            // The returned word outlives the detector. Charge its retained
            // nodes separately before releasing the speculative workspace.
            long bytes = 128L + 96L * cycle.firings();
            if (budget.tryReserve(bytes)) {
                memory += bytes;
                PlanStep word = cycle.word();
                // A repeated inner batch is part of the outer exact word too.
                // Reuse its checked prefix/delta instead of expanding it or
                // silently omitting the skipped firings from the outer guard.
                if (loopCycle != null && !loopCycle.record(cycle, word, copies)) declineLoop();
                cycle.apply(copies, free);
                program.add(new PlanStep.Repeat(word, copies));
                programNodes += cycle.firings() + 2L;
                long skipped = Math.multiplyExact(copies, cycle.firings());
                cursor.dispatched(skipped);
                restoredFirings = restoredFirings.add(BigInteger.valueOf(skipped));
                cycleFirings = cycleFirings.add(BigInteger.valueOf(skipped)); cycleJumps++;
            }
        }
        clearCycle(); cycleDisabled = true;
    }

    private void clearCycle() {
        if (cycle != null) cycle.close(); cycle = null;
    }

    private void observeLoop() {
        var boundary = cursor.loopBoundary(budget);
        if (loopCycle != null && !cursor.insideLoop(loopAnchor)) clearLoop();
        if (boundary == null) return;
        if (loopCycle != null) {
            // Nested repeats remain part of the current word; only the exact
            // anchored Repeat occurrence can authorize advancing this cursor.
            if (!boundary.sameLoop(loopAnchor)) return;
            long iterations = loopAnchor.remaining() - boundary.remaining();
            if (iterations <= 0 || !loopCycle.matches("", pools)) return;
            long copies = loopCycle.copiesOfWord(boundary.remaining() / iterations, free, external);
            if (copies > 0) {
                long bytes = Math.addExact(128L, Math.multiplyExact(96L, loopCycle.nodes()));
                if (budget.tryReserve(bytes)) {
                    memory += bytes;
                    PlanStep word = loopCycle.word();
                    loopCycle.apply(copies, free);
                    program.add(new PlanStep.Repeat(word, copies));
                    programNodes += loopCycle.nodes() + 2;
                    cursor.skipLoop(boundary, Math.multiplyExact(iterations, copies));
                    BigInteger skipped = loopCycle.exactFirings().multiply(BigInteger.valueOf(copies));
                    restoredFirings = restoredFirings.add(skipped);
                    loopFirings = loopFirings.add(skipped); loopJumps++;
                    clearCycle(); cycleVariant = null; cycleDisabled = false;
                }
            }
            declinedLoop = boundary;
            clearLoop();
        } else if (loopEvidence && !pools.isEmpty() && boundary.remaining() > 1 && !boundary.sameLoop(declinedLoop)) {
            loopCycle = StateRestorationCycle.start("", pools, budget);
            if (loopCycle == null) declinedLoop = boundary;
            else loopAnchor = boundary;
        }
    }

    private void recordLoop(GraphRecipe<Key<K>> recipe, String transition, long runs, long repetitions,
                            Map<K, BigInteger> needed, Map<K, BigInteger> spent) {
        long temporary = 64L + 160L * recipe.executionOutputs().size();
        if (!budget.tryReserve(temporary)) { declineLoop(); return; }
        try {
            var produced = new LinkedHashMap<K, BigInteger>();
            for (var output : recipe.executionOutputs().entrySet()) {
                budget.check();
                if (!output.getKey().entity()) produced.merge(output.getKey().physical(),
                        BigInteger.valueOf(output.getValue()).multiply(BigInteger.valueOf(runs)), BigInteger::add);
            }
            if (!loopCycle.record(transition, runs, repetitions, needed, spent, produced)) declineLoop();
        } finally { budget.release(temporary); }
    }

    private void declineLoop() { declinedLoop = loopAnchor; clearLoop(); }

    private void clearLoop() {
        if (loopCycle != null) loopCycle.close();
        loopCycle = null; loopAnchor = null; loopEvidence = false;
    }

    /** Allocate all simultaneous slots before mutating any owned inventory. */
    private boolean selectTools(GraphRecipe<Key<K>> recipe, CapturedCatalog.Entry<K> source) {
        var groups = toolGroups.get(recipe.id());
        if (groups == null) {
            var grouped = new LinkedHashMap<String, List<StateEntityAllocation.Demand>>();
            for (int i = 0; i < recipe.slots().size(); i++) {
                var slot = recipe.slots().get(i);
                if (slot.key().family() == null || slot.key().entity()) continue;
                budget.check();
                grouped.computeIfAbsent(slot.key().family(), ignored -> new ArrayList<>()).add(
                        new StateEntityAllocation.Demand(i, slot.amount(), source.recipe().mixed(source.recipe().inputs().get(slot.inputSlot()))));
            }
            groups = new ArrayList<>();
            if (grouped.size() > 1) {
                var demands = new ArrayList<StateEntityAllocation.Demand>();
                grouped.values().forEach(demands::addAll);
                if (!reserve(160L + 64L * demands.size())) return false;
                groups.add(new ToolGroup("", List.copyOf(demands)));
            } else for (var group : grouped.entrySet()) {
                var demands = group.getValue();
                if (!shared() && demands.size() == 1 && demands.get(0).amount() == 1) continue;
                if (!reserve(160L + 64L * demands.size())) return false;
                groups.add(new ToolGroup(group.getKey(), List.copyOf(demands)));
            }
            if (!reserve(96)) return false;
            toolGroups.put(recipe.id(), groups);
        }
        if (selectingGroup == groups.size()) return true;
        var group = groups.get(selectingGroup);
        if (group.family().isEmpty()) return selectMatching(recipe, group);
        var family = families.get(group.family());
        if (allocation == null) {
            long transientBytes = 64L + 80L * pool(family).size();
            if (!budget.tryReserve(transientBytes)) { stop(Reason.WORKSPACE_DECLINED); return false; }
            try {
                var supplies = new ArrayList<StateEntityAllocation.Supply<K>>();
                for (var value : pool(family).entrySet()) {
                    budget.check();
                    long capacity = family.accepts(value.getKey()) ? family.capacity(value.getKey()) : 0;
                    if (capacity <= 0) {
                        if (shared()) continue;
                        stop(Reason.INVALID_STATE_ALGEBRA); return false;
                    }
                    supplies.add(new StateEntityAllocation.Supply<>(value.getKey(), value.getValue(), capacity));
                }
                var preferences = choiceRepair != null && choiceRepair.active() && supplies.size() <= 256 ? choiceRepair : null;
                allocation = StateEntityAllocation.create(group.demands(), supplies,
                        preferences == null ? null : state -> preferences.score(family, state), budget);
            } finally {
                budget.release(transientBytes);
            }
            if (allocation == null) stop(Reason.WORKSPACE_DECLINED);
            return false;
        }
        if (!allocation.step()) return false;
        if (!allocation.feasible()) { allocationFailed(family, recipe); return false; }
        long bytes = 128L + 192L * allocation.result().size();
        if (!reserve(bytes)) return false;
        choiceMemory += bytes;
        for (var pick : allocation.result()) {
            budget.check();
            toolChoices.computeIfAbsent(pick.slot(), ignored -> new ArrayList<>()).add(pick);
        }
        allocation.close(); allocation = null; selectingGroup++;
        return false;
    }

    private boolean selectMatching(GraphRecipe<Key<K>> recipe, ToolGroup group) {
        if (matching == null) {
            var owned = pools.computeIfAbsent("", ignored -> new LinkedHashMap<>());
            long transientBytes = 64L + 80L * owned.size();
            if (!budget.tryReserve(transientBytes)) { stop(Reason.WORKSPACE_DECLINED); return false; }
            try {
                var supplies = new ArrayList<StateEntityAllocation.Supply<K>>();
                for (var value : owned.entrySet()) {
                    long capacity = 0;
                    for (var demand : group.demands()) {
                        budget.check();
                        var family = families.get(recipe.slots().get(demand.slot()).key().family());
                        if (family.accepts(value.getKey())) capacity = Math.max(capacity, family.capacity(value.getKey()));
                    }
                    if (capacity > 0) supplies.add(new StateEntityAllocation.Supply<>(value.getKey(), value.getValue(), capacity));
                }
                var preferences = choiceRepair != null && choiceRepair.active() && supplies.size() <= 256 &&
                        group.demands().size() <= 32 ? choiceRepair : null;
                matching = StateEntityMatching.create(group.demands(), supplies, (demand, key) -> {
                    var family = families.get(recipe.slots().get(demand.slot()).key().family());
                    return family.accepts(key) && family.capacity(key) > 0;
                }, preferences == null ? null : (demand, key) ->
                        preferences.score(families.get(recipe.slots().get(demand.slot()).key().family()), key), budget);
            } finally { budget.release(transientBytes); }
            if (matching == null) stop(Reason.WORKSPACE_DECLINED);
            return false;
        }
        if (!matching.step()) return false;
        if (!matching.feasible()) {
            var family = families.get(recipe.slots().get(matching.failedSlot()).key().family());
            allocationFailed(family, recipe); return false;
        }
        long bytes = 128L + 192L * matching.result().size();
        if (!reserve(bytes)) return false;
        choiceMemory += bytes;
        for (var pick : matching.result()) {
            budget.check(); toolChoices.computeIfAbsent(pick.slot(), ignored -> new ArrayList<>()).add(pick);
        }
        matching.close(); matching = null; selectingGroup++;
        return false;
    }

    private boolean takeSelected(StateFamily<K> family, GraphRecipe.Slot<Key<K>> slot,
                                 List<StateEntityAllocation.Pick<K>> selected, List<GraphRecipe.Slot<K>> slots,
                                 List<Returned<K>> returns) {
        var pool = pool(family);
        long left = slot.amount();
        for (var pick : selected) {
            budget.check();
            K state = pick.state();
            BigInteger use = BigInteger.valueOf(pick.amount());
            BigInteger count = pool.getOrDefault(state, BigInteger.ZERO);
            if (count.compareTo(use) < 0 || pick.amount() > left) return false;
            long before = family.capacity(state);
            if (!family.accepts(state) || before <= 0) { stop(Reason.INVALID_STATE_ALGEBRA); return false; }
            K after = family.nominalAfterOne(state);
            long afterCapacity = after != null && family.accepts(after) ? family.capacity(after) : 0;
            if (afterCapacity < before - 1) { stop(Reason.INVALID_STATE_ALGEBRA); return false; }
            if (count.equals(use)) pool.remove(state); else pool.put(state, count.subtract(use));
            slots.add(new GraphRecipe.Slot<>(state, pick.amount(), slot.inputSlot()));
            returns.add(new Returned<>(state, after, family, pick.amount()));
            left -= pick.amount();
        }
        return left == 0;
    }

    private void clearSelection() {
        if (allocation != null) allocation.close(); allocation = null;
        if (matching != null) matching.close(); matching = null;
        toolChoices.clear(); selectingGroup = 0;
        budget.release(choiceMemory); memory -= choiceMemory; choiceMemory = 0;
    }

    private void allocationFailed(StateFamily<K> family, GraphRecipe<Key<K>> intactRecipe) {
        boolean fragmented = allocation != null && allocation.failure() == StateEntityAllocation.Failure.WHOLE_KEY_FRAGMENTATION;
        // Retain the failed residual graph while trying the cheaper repairs.
        // Its certificate storage remains owned until feedback has been used.
        var failed = matching; matching = null;
        try {
            clearSelection();
            if (intactRecipe != null && advanceActivations(family, intactRecipe)) return;
            if (done) return;
            if (repairChoices(family)) return;
            if (repairOrder(family, intactRecipe == null ? proposed.recipes().get(cursor.current().recipe()) : intactRecipe)) return;
            if (failed != null && intactRecipe != null && refineEntityUnion(failed, intactRecipe)) return;
            // The owned entity total already fits. Reducing per-entity lifetime
            // cannot repair this firing's whole-key partition; try another
            // activation first, retaining the actual wear capacities.
            if (!done && fragmented && refineEntityCount(family)) return;
            if (!done) refineEntities(family);
        } finally { if (failed != null) failed.close(); }
    }

    /** Request an additional entity eligible for any deficient or fragmented slot,
     * rather than arbitrarily replacing the last failed slot's tool. An entity
     * contributes once to this union even if several rules accept it. These
     * are restricted proposal hints, not canonical cuts or negative proofs.
     * A checked cohort subset excludes unrelated slots. Otherwise retain all
     * mixed competitors: replacing their entities can release an intact cohort. */
    private boolean refineEntityUnion(StateEntityMatching<K> failed, GraphRecipe<Key<K>> blocked) {
        var deficit = failed.deficit();
        if (deficit != null) return refineEntityUnion(deficit.slots(), deficit.required(), false,
                "; matching_required=" + deficit.required() + "; matching_available=" + deficit.available(), blocked);
        var fragmentation = failed.fragmentation();
        return fragmentation != null && refineEntityUnion(fragmentation.slots(), BigInteger.ZERO, true,
                "; failure=whole_key_fragmentation" + (fragmentation.threshold() > 0 ?
                        "; cohort_size=" + fragmentation.threshold() + "; matching_slots=" + fragmentation.slots().size() +
                        "; matching_cohorts=" + fragmentation.available() : ""), blocked);
    }

    private boolean refineEntityUnion(List<Integer> slots, BigInteger minimum, boolean fragmented,
                                      String evidence, GraphRecipe<Key<K>> blocked) {
        if (!shared() || activations.size() > 128 || proposed.patternTimesExact().size() > 128) return false;
        var ids = new java.util.TreeSet<String>();
        for (int slot : slots) {
            budget.check(); ids.add(blocked.slots().get(slot).key().family());
        }
        // A checked fragmented subset can contain several slots of just one
        // family. Its lifetime is not the obstruction: directly reuse that
        // family's entity coordinate instead of first shrinking capacities.
        if (fragmented && ids.size() == 1) return refineEntityCount(families.get(ids.first()));
        if (ids.size() < 2) return false;
        var group = List.copyOf(ids);
        boolean existing = entityUnionFloors.containsKey(group);
        if (!existing && entityUnionFloors.size() >= 8) return false;
        boolean partial = false;
        int producers = 0;
        for (var activating : activations.values()) {
            int accepted = 0;
            for (String id : group) { budget.check(); if (activating.families().contains(id)) accepted++; }
            if (accepted > 0) { producers++; partial |= accepted < group.size(); }
        }
        // Identical eligibility has no Hall choice to expose. Whole-key
        // fragmentation can still need manufacturing to complete a cohort.
        if (!partial && !fragmented) return false;
        BigInteger count = BigInteger.ZERO;
        for (var value : proposed.patternTimesExact().entrySet()) {
            budget.check();
            var activating = activations.get(proposed.recipes().get(value.getKey()).binding());
            if (activating == null) continue;
            for (String id : group) {
                budget.check();
                if (activating.families().contains(id)) { count = count.add(value.getValue()); break; }
            }
        }
        BigInteger needed = count.add(BigInteger.ONE).max(minimum);
        if (needed.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) return false;
        long required = needed.longValueExact();
        if (required <= entityUnionFloors.getOrDefault(group, 0L)) return false;
        finishObservation(PlanningBudget.CandidateOutcome.INCONCLUSIVE);
        budget.note("candidate_state", "refine_entity_union; families=" + group + "; required=" + required +
                evidence +
                "; retry=" + (refinements + 1) + "; negative_proofs=false");
        var previous = proposed.patternTimesExact();
        resetProposal();
        if (!existing && !reserve(256L + 64L * group.size() + 256L * producers)) return true;
        entityUnionFloors.put(group, required); refinements++;
        if (!existing) for (int i = 0; i < auxiliary.size(); i++) {
            budget.compilationCheck();
            var old = auxiliary.get(i);
            var activating = activations.get(old.binding());
            if (activating == null) continue;
            boolean relevant = false;
            for (String id : group) { budget.compilationCheck(); if (activating.families().contains(id)) { relevant = true; break; } }
            if (!relevant) continue;
            var outputs = new ArrayList<>(old.recipe().outputs());
            outputs.add(new CapturedRecipe.Amount<>(Key.<K>entities(group), 1));
            auxiliary.set(i, new CapturedCatalog.Entry<>(old.id(), old.binding(), new CapturedRecipe<>(
                    old.recipe().inputs(), outputs, old.recipe().external(), old.recipe().captureBounded())));
        }
        beginProposal(previous); return true;
    }

    /** Replaying the same word can choose another already owned entity at an
     * earlier firing. The failed rule is a preference, never a reserved quota. */
    private boolean repairChoices(StateFamily<K> family) {
        if (!shared() || proposed.patternTimesExact().size() > 128) return false;
        if (choiceRepair == null) {
            // A lone physical key has no different initial choice. Leave its
            // order / replacement recovery path and normal hot path unchanged.
            K first = null; boolean alternatives = false;
            long ports = 0;
            for (String id : proposed.patternTimesExact().keySet()) {
                budget.check(); var recipe = proposed.recipes().get(id);
                ports += recipe.inputs().size() + recipe.outputs().size();
                if (ports > 2048) return false;
                var activating = activations.get(recipe.binding());
                if (activating != null) {
                    if (first == null) first = activating.state();
                    else if (!first.equals(activating.state())) alternatives = true;
                }
            }
            if (!alternatives || !lostEligibility(family)) return false;
            choiceRepair = StateChoiceRepair.create(budget);
            if (choiceRepair == null) return false;
        } else if (!choiceRepair.active() || !lostEligibility(family)) return false;
        if (!choiceRepair.protect(family)) return false;
        finishObservation(PlanningBudget.CandidateOutcome.INCONCLUSIVE);
        resetRestoration(); startRestoration(restorationWord); choiceCandidates++;
        budget.note("candidate_state", "retry_entity_choices; family=" + family.id() + "; candidate=" + choiceCandidates +
                "; same_counts=true; same_order=true; negative_proofs=false");
        return true;
    }

    private void refineEntities(StateFamily<K> family) {
        if (!refineCapacity(family) && !done && !refineEntityCount(family) && !done) stop(Reason.INSUFFICIENT_DISTINCT_ENTITIES);
    }

    /** A changed physical key can disable another rule without exhausting the
     * first rule's marginal capacity. Prefer the blocked rule in another exact
     * schedule of these same counts before asking for additional entities. */
    private boolean repairOrder(StateFamily<K> family, GraphRecipe<Key<K>> blocked) {
        if (orderRepair != null) {
            orderFailure = family;
            return continueOrder(blocked.id());
        }
        if (orderTried || !shared() || proposed.patternTimesExact().size() > 128) return false;
        long ports = 0;
        for (String id : proposed.patternTimesExact().keySet()) {
            budget.check();
            var recipe = proposed.recipes().get(id);
            ports += recipe.inputs().size() + recipe.outputs().size();
            if (ports > 2048) return false;
        }
        if (!lostEligibility(family)) return false;
        orderTried = true;
        long transientBytes = 128L + 128L * ports;
        if (!budget.tryReserve(transientBytes)) return false;
        try {
            var available = new LinkedHashMap<Key<K>, Long>();
            var supplied = new LinkedHashSet<Key<K>>();
            for (String id : proposed.patternTimesExact().keySet()) for (var key : proposed.recipes().get(id).inputs().keySet()) {
                budget.check();
                if (key.family() != null) continue;
                long count = stock.getOrDefault(key.physical(), 0L);
                if (count > 0) available.put(key, count);
                if (external.contains(key.physical())) supplied.add(key);
            }
            orderRepair = StateScheduleRepair.create(proposed.recipes(), proposed.patternTimesExact(), available,
                    supplied, blocked.id(), budget);
        } finally { budget.release(transientBytes); }
        if (orderRepair == null) return false;
        orderFailure = family; searchingOrder = true;
        finishObservation(PlanningBudget.CandidateOutcome.INCONCLUSIVE);
        budget.note("candidate_state", "retry_same_counts; blocked=" + blocked.binding() + "; family=" + family.id() + "; negative_proofs=false");
        return true;
    }

    private boolean lostEligibility(StateFamily<K> family) {
        // Inspect already witnessed transitions only, on failure. This is an
        // admission hint, not a learned precedence or a necessary entity bound.
        if (transitions.size() > 512) return false;
        for (var transition : transitions.keySet()) {
            boolean otherRule = false;
            for (var slot : proposed.recipes().get(transition.variant()).slots()) {
                budget.check();
                if (slot.key().family() != null && !slot.key().entity() && !family.id().equals(slot.key().family())) otherRule = true;
            }
            if (!otherRule) continue;
            BigInteger before = BigInteger.ZERO, after = BigInteger.ZERO;
            for (var slot : transition.slots()) {
                budget.check();
                if (family.accepts(slot.key()) && family.capacity(slot.key()) > 0) before = before.add(BigInteger.valueOf(slot.amount()));
            }
            for (var output : transition.outputs().entrySet()) {
                budget.check();
                if (family.accepts(output.getKey()) && family.capacity(output.getKey()) > 0) after = after.add(BigInteger.valueOf(output.getValue()));
            }
            if (before.compareTo(after) > 0) return true;
        }
        return false;
    }

    private boolean continueOrder() {
        return continueOrder(null);
    }

    private boolean continueOrder(String blocked) {
        if (orderRepair == null || !orderRepair.reject(blocked)) return false;
        finishObservation(PlanningBudget.CandidateOutcome.INCONCLUSIVE);
        searchingOrder = true; return true;
    }

    private void reschedule() {
        if (!orderRepair.step()) return;
        var word = orderRepair.witness();
        if (word == null) {
            orderRepair.close(); orderRepair = null; searchingOrder = false;
            refineEntities(orderFailure); return;
        }
        resetRestoration();
        startRestoration(word); searchingOrder = false; orderCandidates++;
        budget.note("candidate_state", "restore_alternative_order; candidate=" + orderCandidates + "; same_counts=true");
    }

    private void startRestoration(PlanStep word) {
        restorationWord = word; cursor = new PlanCursor(word);
        for (var value : proposed.patternTimesExact().entrySet()) {
            budget.check();
            String binding = proposed.recipes().get(value.getKey()).binding();
            if (!activations.containsKey(binding)) continue;
            if (!unclaimed.containsKey(binding) && !reserve(224)) return;
            unclaimed.merge(binding, value.getValue(), BigInteger::add);
        }
        observation = budget.observeCandidate(PlanningBudget.CandidateOrigin.ALLOCATION);
    }

    private boolean claim(String binding, Activation<K> activating, BigInteger count) {
        BigInteger remaining = unclaimed.getOrDefault(binding, BigInteger.ZERO);
        if (count.compareTo(remaining) > 0 || !spend(activating.state(), count)) return false;
        // Replacing a depleted tool can restore the same pool shape while
        // consuming finite unclaimed inventory. That is not a closed orbit.
        clearCycle();
        clearLoop();
        unclaimed.put(binding, remaining.subtract(count));
        pool(families.get(activating.families().get(0))).merge(activating.state(), count, BigInteger::add);
        return true;
    }

    private boolean advanceActivations(StateFamily<K> family, GraphRecipe<Key<K>> recipe) {
        boolean changed = false;
        for (var value : unclaimed.entrySet()) {
            budget.check();
            var activating = activations.get(value.getKey());
            if (value.getValue().signum() <= 0) continue;
            boolean relevant = activating.families().contains(family.id());
            // An extra entity for another slot can free a shared eligible key
            // for the failed slot through an augmenting path.
            if (!relevant && shared()) for (var slot : recipe.slots()) {
                budget.check();
                if (slot.key().family() != null && !slot.key().entity() && activating.families().contains(slot.key().family())) { relevant = true; break; }
            }
            if (!relevant) continue;
            K state = activating.state();
            BigInteger available = external.contains(state) ? value.getValue() : free.getOrDefault(state, BigInteger.ZERO);
            for (var slot : recipe.slots()) {
                budget.check();
                if (slot.key().family() == null && state.equals(slot.key().physical()))
                    available = available.subtract(BigInteger.valueOf(slot.amount()));
            }
            BigInteger count = available.max(BigInteger.ZERO).min(value.getValue());
            if (count.signum() == 0) continue;
            if (!claim(value.getKey(), activating, count)) { stop(Reason.ACTIVATION_OWNERSHIP); return false; }
            advanced.merge(value.getKey(), count, BigInteger::add); changed = true;
        }
        if (changed) {
            activationAdvances++;
            budget.note("candidate_state", "advance_owned_activations; family=" + family.id() + "; advances=" + activationAdvances);
        }
        return changed;
    }

    /** Re-propose with a smaller positive-only use allowance per activated
     * entity. One entity cannot occupy two simultaneous slots in one firing.
     * The horizon comes from this proposal, not from a global upper bound on
     * all possible programs; it must never become a canonical theorem. */
    private boolean refineCapacity(StateFamily<K> family) {
        BigInteger firings = BigInteger.ZERO;
        for (var value : proposed.patternTimesExact().entrySet()) {
            budget.check();
            var recipe = proposed.recipes().get(value.getKey());
            for (var slot : recipe.slots()) {
                budget.check();
                if (!slot.key().entity() && family.id().equals(slot.key().family())) { firings = firings.add(value.getValue()); break; }
            }
        }
        long horizon = ExactAmounts.capped(firings);
        if (horizon <= 0 || horizon >= capacityCaps.getOrDefault(family.id(), Long.MAX_VALUE)) return false;
        boolean stronger = false;
        for (var value : proposed.patternTimesExact().entrySet()) {
            budget.check();
            var activating = activations.get(proposed.recipes().get(value.getKey()).binding());
            if (activating != null && activating.families().contains(family.id()) && family.capacity(activating.state()) > horizon) {
                stronger = true; break;
            }
        }
        if (!stronger) return false;
        finishObservation(PlanningBudget.CandidateOutcome.INCONCLUSIVE);
        budget.note("candidate_state", "refine_entity_horizon; family=" + family.id() + "; horizon=" + horizon +
                "; restored_firings=" + restoredFirings + "; retry=" + (refinements + 1) + "; negative_proofs=false");
        var previous = proposed.patternTimesExact();
        resetProposal();
        if (!capacityCaps.containsKey(family.id()) && !reserve(128)) return true;
        capacityCaps.put(family.id(), horizon); refinements++;
        for (int i = 0; i < auxiliary.size(); i++) {
            budget.compilationCheck();
            var old = auxiliary.get(i);
            var activating = activations.get(old.binding());
            if (activating == null || !activating.families().contains(family.id())) continue;
            var outputs = new ArrayList<CapturedRecipe.Amount<Key<K>>>();
            for (var output : old.recipe().outputs()) outputs.add(!output.what().entity() && family.id().equals(output.what().family()) ?
                    new CapturedRecipe.Amount<>(output.what(), Math.min(output.amount(), horizon)) : output);
            auxiliary.set(i, new CapturedCatalog.Entry<>(old.id(), old.binding(), new CapturedRecipe<>(
                    old.recipe().inputs(), outputs, old.recipe().external(), old.recipe().captureBounded())));
        }
        beginProposal(previous);
        return true;
    }

    /** Marginal capacities can fit while combined wear or whole-key slots do
     * not. A single family can also need another manufactured entity to join
     * fragments into whole slots. Try more owned entities in a fresh restricted
     * proposal; the extra count is a search hint, not a necessary bound. */
    private boolean refineEntityCount(StateFamily<K> family) {
        // A uniform family needs a declared simultaneous-entity coordinate.
        // Ordinary single-tool recipes keep their existing capacity path.
        if (!shared() && !entityFamilies.contains(family.id())) return false;
        BigInteger count = BigInteger.ZERO;
        for (var value : proposed.patternTimesExact().entrySet()) {
            budget.check();
            var activating = activations.get(proposed.recipes().get(value.getKey()).binding());
            if (activating != null && activating.families().contains(family.id())) count = count.add(value.getValue());
        }
        if (count.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) >= 0) return false;
        long required = count.longValueExact() + 1;
        if (required <= entityFloors.getOrDefault(family.id(), 0L)) return false;
        finishObservation(PlanningBudget.CandidateOutcome.INCONCLUSIVE);
        budget.note("candidate_state", "refine_entity_count; family=" + family.id() + "; shared=" + shared() + "; required=" + required +
                "; retry=" + (refinements + 1) + "; negative_proofs=false");
        var previous = proposed.patternTimesExact();
        resetProposal();
        if (!entityFloors.containsKey(family.id()) && !reserve(128)) return true;
        entityFloors.put(family.id(), required); refinements++;
        beginProposal(previous); return true;
    }

    private void resetProposal() {
        // All changed coordinates and the old physical restoration are private
        // to this arm. No old plan, certificate or partial inventory survives.
        resetRestoration(); proposed = null;
        restorationWord = null;
        if (choiceRepair != null) choiceRepair.close(); choiceRepair = null;
        if (orderRepair != null) orderRepair.close(); orderRepair = null;
        orderTried = false; searchingOrder = false; orderFailure = null;
    }

    private void resetRestoration() {
        cursor = null;
        clearSelection();
        if (production != null) production.close(); production = null;
        if (verification != null) verification.close(); verification = null;
        if (assembly != null) assembly.close(); assembly = null;
        clearCycle(); cycleVariant = null; cycleDisabled = false;
        clearLoop(); declinedLoop = null;
        toolGroups.clear(); pools.clear(); restored.clear(); transitions.clear(); program.clear(); free.clear(); unclaimed.clear(); advanced.clear();
        budget.release(memory - proposalMemory); memory = proposalMemory;
        for (var value : stock.entrySet()) { budget.check(); free.put(value.getKey(), BigInteger.valueOf(value.getValue())); }
        restoredFirings = BigInteger.ZERO; programNodes = 1;
    }

    private boolean append(String id, long runs, long repetitions) {
        PlanStep.Batch leaf = new PlanStep.Batch(id, runs);
        if (!program.isEmpty()) {
            int last = program.size() - 1;
            PlanStep previous = program.get(last);
            PlanStep body = previous instanceof PlanStep.Repeat r ? r.body() : previous;
            long count = previous instanceof PlanStep.Repeat r ? r.times() : 1;
            if (body.equals(leaf) && count <= Long.MAX_VALUE - repetitions) {
                if (!(previous instanceof PlanStep.Repeat)) programNodes++;
                program.set(last, new PlanStep.Repeat(body, count + repetitions));
                return true;
            }
        }
        if (!reserve(96)) return false;
        program.add(repetitions == 1 ? leaf : new PlanStep.Repeat(leaf, repetitions));
        programNodes += repetitions == 1 ? 1 : 2;
        return true;
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
            long before = family.accepts(state) ? family.capacity(state) : 0;
            if (before <= 0) {
                if (shared()) continue;
                stop(Reason.INVALID_STATE_ALGEBRA); return false;
            }
            if (!mixed && value.getValue().compareTo(BigInteger.valueOf(left)) < 0) continue;
            long use = value.getValue().min(BigInteger.valueOf(left)).longValueExact();
            K after = family.nominalAfterOne(state);
            // Capacity is a guaranteed lower bound, possibly saturated or
            // infinite. An exact return may retain more than before - 1 uses
            // (zero wear / repair / saturation); it must never guarantee less.
            long afterCapacity = after != null && family.accepts(after) ? family.capacity(after) : 0;
            if (afterCapacity < before - 1) { stop(Reason.INVALID_STATE_ALGEBRA); return false; }
            BigInteger remaining = value.getValue().subtract(BigInteger.valueOf(use));
            if (remaining.signum() == 0) iterator.remove();
            else value.setValue(remaining);
            slots.add(new GraphRecipe.Slot<>(state, use, slot.inputSlot()));
            returns.add(new Returned<>(state, after, family, use));
            left -= use;
            if (left == 0) return true;
        }
        return false;
    }

    private Map<K, BigInteger> pool(StateFamily<K> family) {
        return pools.computeIfAbsent(shared() ? "" : family.id(), ignored -> new LinkedHashMap<>());
    }

    private boolean shared() { return families.size() > 1; }

    private boolean usable(K state, StateFamily<K> used) {
        if (!shared()) return used.accepts(state) && used.capacity(state) > 0;
        for (var family : families.values()) {
            budget.check();
            if (family.accepts(state) && family.capacity(state) > 0) return true;
        }
        return false;
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
            if (!production.proved()) {
                if (!continueOrder()) stop(Reason.FORCE_CRAFT_NOT_PROVED);
                return;
            }
        }
        result = assembly.plan; done = true;
        outcome = new Outcome(Status.VERIFIED, Reason.NONE);
        finishObservation(PlanningBudget.CandidateOutcome.ACCEPTED);
        budget.note("candidate_state", "verified_exact_execution; variants=" + restored.size() +
                "; program_nodes=" + programNodes + "; restored_firings=" + restoredFirings +
                "; restore_calls=" + restoreCalls + "; restore_work=" + restoreWork + "; refinements=" + refinements +
                "; activation_advances=" + activationAdvances + "; cycle_jumps=" + cycleJumps + "; cycle_firings=" + cycleFirings +
                "; loop_jumps=" + loopJumps + "; loop_firings=" + loopFirings +
                "; order_candidates=" + orderCandidates +
                "; choice_candidates=" + choiceCandidates +
                "; work=" + (budget.nodes() - workStarted));
    }

    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) { stop(Reason.WORKSPACE_DECLINED); return false; }
        memory += bytes; return true;
    }

    private boolean stop(Reason reason) {
        done = true; finishObservation(PlanningBudget.CandidateOutcome.INCONCLUSIVE);
        outcome = new Outcome(reason == Reason.LOCAL_WORK_LIMIT || reason == Reason.WORKSPACE_DECLINED ? Status.LIMITED : Status.DECLINED, reason);
        budget.note("candidate_state", "declined; reason=" + reason + "; variants=" + restored.size() +
                "; program_nodes=" + programNodes + "; restored_firings=" + restoredFirings + "; restore_work=" + restoreWork); return true;
    }

    GraphPlan<K> result() { return result; }
    Outcome outcome() { return outcome; }

    CompletableFuture<?> waitingFor() { return expansion != null ? expansion.waitingFor() : planning == null ? null : planning.waitingFor(); }

    private void finishObservation(PlanningBudget.CandidateOutcome outcome) {
        if (observation != null) observation.finish(outcome);
        observation = null;
    }

    @Override public void close() {
        finishObservation(PlanningBudget.CandidateOutcome.ABANDONED);
        clearSelection();
        clearCycle();
        clearLoop(); declinedLoop = null;
        if (choiceRepair != null) choiceRepair.close(); choiceRepair = null;
        if (orderRepair != null) orderRepair.close(); orderRepair = null;
        if (planning != null) planning.close(); planning = null;
        if (proposalRepair != null) proposalRepair.close(); proposalRepair = null;
        if (expansion != null) expansion.close(); expansion = null;
        if (production != null) production.close(); production = null;
        if (verification != null) verification.close(); verification = null;
        if (assembly != null) assembly.close(); assembly = null;
        cursor = null; restorationWord = null; proposed = null;
        captureStock = null; captureExternal = null;
        families.clear(); entityFamilies.clear(); known.clear(); activationStates = List.of(); auxiliary.clear(); sources.clear(); activations.clear(); pools.clear();
        free.clear(); restored.clear(); transitions.clear(); program.clear(); toolGroups.clear(); capacityCaps.clear(); entityFloors.clear(); entityUnionFloors.clear(); unclaimed.clear(); advanced.clear();
        budget.release(memory); memory = 0; done = true;
    }
}
