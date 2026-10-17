// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Positive-only forward recovery over factorized captured inputs. Each selected
 * combination spends real current inventory before adding its exact returns;
 * reachable keys are never treated as an inventory supply. Failure is heuristic.
 */
final class CapturedReachableWitness<K> implements AutoCloseable {

    record Variant<K>(int entry, BigInteger ordinal, GraphRecipe<K> recipe) {}

    private final List<CapturedCatalog.Entry<K>> entries;
    private final Map<K, List<Integer>> producers;
    private final K target;
    private final long amount, started = System.nanoTime();
    private final Map<K, Long> stock, seeds;
    private final Set<K> external;
    private final boolean preserve, force;
    private final PlanningBudget budget;
    private final long workStarted, allowance;
    private final BigInteger targetGoal;
    private final ArrayDeque<K> keys = new ArrayDeque<>();
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private final Set<K> discovered = new HashSet<>();
    private final Set<Integer> relevant = new HashSet<>(), declined = new HashSet<>();
    private final List<Integer> order = new ArrayList<>();
    private final Map<Integer, CapturedRecipeDomain<K>> domains = new LinkedHashMap<>();
    private final Map<K, BigInteger> inventory = new LinkedHashMap<>(), needed = new LinkedHashMap<>();
    private final List<Map<Integer, Long>> choices = new ArrayList<>();
    private final Map<String, GraphRecipe<K>> recipes = new LinkedHashMap<>();
    private final Map<String, Variant<K>> variants = new LinkedHashMap<>();
    private final List<PlanStep> steps = new ArrayList<>();
    private List<Integer> providers = List.of();
    private int provider, discovering = -1, discoverSlot, discoverCandidate;
    private int cursor, slot, candidate;
    private boolean indexed, done;
    private long memory;
    private AllocationSearch.Candidate<K> assembly;
    private PlanVerification<K> verification;
    private ForceCraftProof<K> production;
    private PlanningBudget.CandidateObservation observation;
    private GraphPlan<K> result;

    CapturedReachableWitness(List<CapturedCatalog.Entry<K>> entries, Map<K, List<Integer>> producers,
                            K target, long amount, Map<K, Long> stock, Set<K> external, Map<K, Long> seeds,
                            boolean preserve, boolean force, PlanningBudget budget) {
        this.entries = entries; this.producers = producers; this.target = target; this.amount = amount;
        this.stock = stock; this.external = external; this.seeds = seeds; this.preserve = preserve;
        this.force = force; this.budget = budget;
        workStarted = budget.nodes();
        allowance = budget.remainingWork() / 8;
        targetGoal = BigInteger.valueOf(amount).add(BigInteger.valueOf(force ?
                Math.max(stock.getOrDefault(target, 0L), seeds.getOrDefault(target, 0L)) : seeds.getOrDefault(target, 0L)));
        if (allowance < 1024 || !reserve(1024)) { done = true; return; }
        try {
            discover(target);
            for (K key : seeds.keySet()) { budget.compilationCheck(); discover(key); }
            budget.note("candidate_reachable", "start; allowance=" + allowance + "; exact_returns=true; negative_proofs=false");
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    boolean step() {
        if (done) return true;
        var observed = observation;
        long observedWork = observed == null ? 0 : budget.threadWork();
        long observedNanos = observed == null ? 0 : System.nanoTime();
        try (var costs = budget.trace(PlanningCostTrace.Origin.ALLOCATION,
                !indexed ? PlanningCostTrace.Stage.PREPARE : assembly == null ? PlanningCostTrace.Stage.SEARCH : PlanningCostTrace.Stage.VERIFY)) {
            budget.checkpoint();
            if (budget.nodes() - workStarted >= allowance) return stop("local_work_limit");
            if (!indexed) index();
            else if (verification != null) verify();
            else if (assembly != null) {
                if (assembly.step()) {
                    if (assembly.plan == null) return stop("assembly_declined");
                    verification = new PlanVerification<>(assembly.plan, budget);
                }
            } else if (goalsReached()) {
                observation = budget.observeCandidate(PlanningBudget.CandidateOrigin.ALLOCATION);
                long work = budget.threadWork(), nanos = System.nanoTime();
                try {
                    assembly = new AllocationSearch.Candidate<>(new PlanStep.Sequence(steps), recipes, target, amount, stock,
                            seeds, external, preserve, force, false, budget, started);
                } finally { if (observation != null) observation.step(work, nanos); }
            } else choose();
            return done;
        } catch (PlanningBudget.Exhausted failure) {
            if (failure.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw failure;
            return stop("workspace_declined");
        } catch (ArithmeticException overflow) {
            return stop("unrepresentable_candidate");
        } finally {
            if (observed != null) observed.step(observedWork, observedNanos);
        }
    }

    private void discover(K key) {
        if (!discovered.contains(key)) {
            if (!reserve(128)) return;
            discovered.add(key); keys.addLast(key);
        }
    }

    private void index() {
        budget.compilationCheck();
        if (discovering >= 0) {
            var recipe = entries.get(discovering).recipe();
            if (discoverSlot == recipe.inputs().size()) { discovering = -1; return; }
            var input = recipe.inputs().get(discoverSlot);
            if (discoverCandidate == input.candidates().size()) { discoverSlot++; discoverCandidate = 0; return; }
            var value = input.candidates().get(discoverCandidate++);
            if (value.configuration() || value.reusable()) declined.add(discovering);
            discover(value.stack().what());
        } else if (provider < providers.size()) {
            int entry = providers.get(provider++);
            if (relevant.add(entry)) {
                var recipe = entries.get(entry).recipe();
                if (!reserve(256L + 256L * recipe.inputs().size())) return;
                pending.addLast(entry); order.add(entry);
            }
        } else if (!keys.isEmpty()) {
            providers = producers.getOrDefault(keys.removeFirst(), List.of()); provider = 0;
        } else if (!pending.isEmpty()) {
            discovering = pending.removeFirst(); discoverSlot = discoverCandidate = 0;
        } else {
            indexed = true;
            if (order.isEmpty()) stop("no_relevant_producers");
        }
    }

    private BigInteger have(K key) {
        return inventory.getOrDefault(key, BigInteger.valueOf(stock.getOrDefault(key, 0L)));
    }

    private boolean supplied(K key) { return external.contains(key) && (!force || !key.equals(target)); }

    private boolean goalsReached() {
        budget.check();
        if (!supplied(target) && have(target).compareTo(targetGoal) < 0) return false;
        for (var seed : seeds.entrySet()) {
            budget.check();
            if (!seed.getKey().equals(target) && !supplied(seed.getKey()) &&
                    have(seed.getKey()).compareTo(BigInteger.valueOf(seed.getValue())) < 0) return false;
        }
        return !force || !steps.isEmpty();
    }

    private void choose() {
        budget.check();
        if (cursor == order.size()) { stop("no_executable_combination"); return; }
        int entry = order.get(cursor);
        var recipe = entries.get(entry).recipe();
        if (declined.contains(entry)) { next(); return; }
        if (slot == recipe.inputs().size()) { fire(entry); return; }
        var input = recipe.inputs().get(slot);
        if (candidate == input.candidates().size()) { next(); return; }
        int at = candidate++;
        var value = input.candidates().get(at).stack();
        BigInteger quantity = BigInteger.valueOf(value.amount()).multiply(BigInteger.valueOf(input.multiplier()));
        if (quantity.signum() <= 0) { declined.add(entry); next(); return; }
        BigInteger total = needed.getOrDefault(value.what(), BigInteger.ZERO).add(quantity);
        if (!supplied(value.what()) && total.compareTo(have(value.what())) > 0) return;
        needed.put(value.what(), total);
        choices.add(Map.of(at, input.multiplier()));
        slot++; candidate = 0;
    }

    private void next() {
        cursor++; slot = candidate = 0; needed.clear(); choices.clear();
    }

    private void fire(int entry) {
        var source = entries.get(entry);
        var domain = domains.get(entry);
        if (domain == null) { domain = source.recipe().domain(budget); domains.put(entry, domain); }
        BigInteger ordinal = domain.ordinal(choices);
        String id = source.id() + "#" + ordinal;
        var recipe = recipes.get(id);
        if (recipe == null) {
            var resolved = domain.resolve(ordinal);
            if (!reserve(512L + 192L * (resolved.slots().size() + resolved.outputs().size()))) return;
            recipe = new GraphRecipe<>(id, source.binding(), resolved.slots(), resolved.outputs());
            recipes.put(id, recipe); variants.put(id, new Variant<>(entry, ordinal, recipe));
        }
        BigInteger deltaTarget = BigInteger.valueOf(recipe.outputs().getOrDefault(target, 0L))
                .subtract(BigInteger.valueOf(recipe.inputs().getOrDefault(target, 0L)));
        BigInteger runs = deltaTarget.signum() > 0 ? targetGoal.subtract(have(target)).max(BigInteger.ONE)
                .add(deltaTarget).subtract(BigInteger.ONE).divide(deltaTarget) : BigInteger.ONE;
        runs = runs.min(BigInteger.valueOf(Long.MAX_VALUE));
        for (var input : recipe.inputs().entrySet()) {
            budget.check();
            if (supplied(input.getKey())) continue;
            BigInteger required = BigInteger.valueOf(input.getValue());
            BigInteger available = have(input.getKey());
            if (required.compareTo(available) > 0) { next(); return; }
            BigInteger loss = required.subtract(BigInteger.valueOf(recipe.outputs().getOrDefault(input.getKey(), 0L)));
            if (loss.signum() > 0) runs = runs.min(available.subtract(required).divide(loss).add(BigInteger.ONE));
        }
        for (var input : recipe.inputs().entrySet()) {
            budget.check();
            if (!supplied(input.getKey())) put(input.getKey(), have(input.getKey()).subtract(BigInteger.valueOf(input.getValue()).multiply(runs)));
        }
        for (var output : recipe.outputs().entrySet()) {
            budget.check();
            if (!supplied(output.getKey())) put(output.getKey(), have(output.getKey()).add(BigInteger.valueOf(output.getValue()).multiply(runs)));
        }
        if (!reserve(64)) return;
        steps.add(PlanStep.batch(id, runs));
        cursor = slot = candidate = 0; needed.clear(); choices.clear();
    }

    private void put(K key, BigInteger value) {
        if (!inventory.containsKey(key) && !reserve(160)) return;
        inventory.put(key, value);
    }

    private void verify() {
        if (!verification.step()) return;
        if (force) {
            if (production == null) production = new ForceCraftProof<>(assembly.plan, verification, seeds, budget);
            if (!production.step()) return;
            if (!production.proved()) { stop("production_not_proved"); return; }
        }
        result = assembly.plan; done = true;
        finishObservation(PlanningBudget.CandidateOutcome.ACCEPTED);
        budget.note("candidate_reachable", "verified_execution; variants=" + variants.size() + "; batches=" + steps.size() +
                "; work=" + (budget.nodes() - workStarted));
    }

    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) { stop("workspace_declined"); return false; }
        memory += bytes; return true;
    }

    private boolean stop(String reason) {
        done = true;
        finishObservation(reason.equals("assembly_declined") || reason.equals("production_not_proved") ?
                PlanningBudget.CandidateOutcome.REJECTED : PlanningBudget.CandidateOutcome.INCONCLUSIVE);
        budget.note("candidate_reachable", reason + "; variants=" + variants.size() + "; work=" + (budget.nodes() - workStarted));
        return true;
    }

    GraphPlan<K> result() { return result; }
    List<Variant<K>> variants() { return List.copyOf(variants.values()); }

    /** Bounded scheduling estimate, never evidence of feasibility or reachability. */
    boolean promising(long remainingWork) {
        if (remainingWork <= 0) return false;
        budget.operation(PlanningBudget.Operation.SCAN, 0);
        if (assembly != null || verification != null) return true;
        if (!indexed) return false;
        BigInteger available = have(target);
        budget.operations(PlanningBudget.Operation.INTEGER, Math.max(available.bitLength(), targetGoal.bitLength()) + 64, 8);
        BigInteger progress = available.subtract(BigInteger.valueOf(stock.getOrDefault(target, 0L)));
        if (progress.signum() <= 0) return false;
        long spent = budget.nodes() - workStarted;
        BigInteger left = targetGoal.subtract(available).max(BigInteger.ZERO);
        // Use the observed cost per net target gain and keep one spent prefix
        // for assembly/verification. Long requests with negligible progress do
        // not qualify merely because a few legal firings were found.
        BigInteger predicted = left.multiply(BigInteger.valueOf(spent)).add(progress.subtract(BigInteger.ONE)).divide(progress)
                .add(BigInteger.valueOf(spent));
        return predicted.compareTo(BigInteger.valueOf(remainingWork)) <= 0;
    }

    private void finishObservation(PlanningBudget.CandidateOutcome outcome) {
        if (observation != null) observation.finish(outcome);
        observation = null;
    }

    @Override public void close() {
        finishObservation(PlanningBudget.CandidateOutcome.ABANDONED);
        if (production != null) production.close(); production = null;
        if (verification != null) verification.close(); verification = null;
        if (assembly != null) assembly.close(); assembly = null;
        domains.values().forEach(CapturedRecipeDomain::close); domains.clear();
        budget.release(memory); memory = 0; done = true;
    }
}
