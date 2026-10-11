// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A catalog retaining captured input domains instead of only their first 256
 * combinations. Entries are in host priority order. The host must capture all
 * providers (including providers for later alternatives) before submitting it;
 * no worker calls back into the live recipe registry.
 */
public final class CapturedCatalog<K> {

    public record Entry<K>(String id, String binding, CapturedRecipe<K> recipe) {
        public Entry {
            Objects.requireNonNull(id);
            Objects.requireNonNull(binding);
            Objects.requireNonNull(recipe);
        }
    }

    /** expanded is a contiguous prefix; additional contains instantiated ordinals beyond it. */
    public record DomainCoverage(String id, BigInteger expanded, BigInteger total, List<BigInteger> additional, boolean captureComplete) {
        public DomainCoverage { additional = List.copyOf(additional); }
    }

    /** Revision is local to this Work; it is not a cache key shared by different captures. */
    public record Coverage(long revision, List<DomainCoverage> domains, boolean complete) {
        public Coverage { domains = List.copyOf(domains); }
    }

    public record Result<K>(GraphPlan<K> plan, Coverage coverage) {}

    private final List<Entry<K>> entries;
    private final boolean captureComplete;

    public CapturedCatalog(List<Entry<K>> entries) { this(entries, true); }

    /** complete=false also covers omitted providers, not just truncated input alternatives. */
    public CapturedCatalog(List<Entry<K>> entries, boolean complete) {
        this.entries = List.copyOf(entries);
        Set<String> ids = new HashSet<>();
        for (var entry : entries) {
            if (!ids.add(entry.id())) throw new IllegalArgumentException("Duplicate captured recipe id: " + entry.id());
            complete &= !entry.recipe().captureBounded();
        }
        captureComplete = complete;
    }

    public Work begin(K target, long amount, Map<K, Long> stock, boolean preserve, boolean forceCraft, PlanningBudget budget) {
        return begin(target, amount, stock, Set.of(), Map.of(), preserve, forceCraft, budget);
    }

    public Work begin(K target, long amount, Map<K, Long> stock, Set<K> external, Map<K, Long> requiredSeeds,
                      boolean preserve, boolean forceCraft, PlanningBudget budget) {
        return new Work(target, amount, stock, external, requiredSeeds, preserve, forceCraft, budget);
    }

    public Result<K> plan(K target, long amount, Map<K, Long> stock, boolean preserve, boolean forceCraft, PlanningBudget budget) {
        Work work = begin(target, amount, stock, preserve, forceCraft, budget);
        try {
            while (!work.step()) { /* Same continuation as the background scheduler. */ }
            return work.result();
        } catch (PlanningBudget.Exhausted limit) {
            return work.limited(limit);
        } finally {
            work.close();
        }
    }

    public final class Work implements PlanningScheduler.Work<Result<K>>, AutoCloseable {

        private final K target;
        private final long amount, started = System.nanoTime();
        private final Map<K, Long> stock, requiredSeeds;
        private final Set<K> external;
        private final boolean preserve, forceCraft;
        private final PlanningBudget budget;
        private final Map<K, List<Integer>> producers = new HashMap<>();
        private final Map<Integer, DomainState> active = new LinkedHashMap<>();
        private final Deque<K> pendingKeys = new ArrayDeque<>();
        private final Set<K> discovered = new HashSet<>();
        private final Deque<DomainState> pendingDomains = new ArrayDeque<>();
        private final Deque<DomainState> pendingAllocations = new ArrayDeque<>();
        private DomainState allocating;
        private CapturedInputAllocation<K> allocation;
        private long allocationFirings;
        private List<BigInteger> allocated = List.of();
        private int allocatedIndex;
        private boolean addedAllocation;
        private CapturedFlowAllocation<K> flow;
        private boolean flowTried;
        private List<CapturedFlowAllocation.Variant> flowVariants = List.of();
        private int flowVariant;
        private final Map<String, BigInteger> flowCounts = new LinkedHashMap<>();
        private CapturedFlowWitness<K> flowWitness;
        private DomainState expanding;
        private List<Integer> providers = List.of();
        private int provider;
        private int entry, output, input, candidate;
        private int phase; // 0 index, 1 discover, 2 freeze, 3 solve, 4 stock, 5 flow, 6 recovered counts
        private int flattenEntry, flattenVariant;
        private List<GraphRecipe<K>> concrete = new ArrayList<>();
        private List<GraphRecipe<K>> allocationRecipes = new ArrayList<>();
        private boolean allocationView;
        private CatalogIndex<K> indexing;
        private GraphPlanningWork<K> solving;
        private CatalystPolicy catalystPolicy = CatalystPolicy.STOCK;
        private Coverage coverage = new Coverage(0, List.of(), false);
        private Result<K> result;
        private long memory, revision, solveStarted, solveAllowance;
        private boolean remaining, closed;

        private Work(K target, long amount, Map<K, Long> stock, Set<K> external, Map<K, Long> requiredSeeds,
                     boolean preserve, boolean forceCraft, PlanningBudget budget) {
            if (amount <= 0) throw new IllegalArgumentException("Non-positive order");
            this.target = Objects.requireNonNull(target);
            this.amount = amount;
            this.stock = GraphRecipe.amounts(stock);
            this.external = Set.copyOf(external);
            this.requiredSeeds = GraphRecipe.amounts(requiredSeeds);
            this.preserve = preserve;
            this.forceCraft = forceCraft;
            this.budget = budget;
        }

        public Work catalysts(CatalystPolicy policy) {
            if (entry != 0 || phase != 0) throw new IllegalStateException("Planning already started");
            catalystPolicy = Objects.requireNonNull(policy);
            return this;
        }

        public Coverage coverage() { return coverage; }

        /**
         * Export this request's final candidate scope and optional concrete witness.
         * May be called after close(); no live keys or solver state enter the archive.
         * Export has separate explicit limits and cannot consume the solve allowance.
         * Scoped CountProof journals remain separate, with their original assumptions.
         */
        public CapturedProof.Certificate certificate(long maximumWork, long maximumBytes) {
            return CapturedProof.capture(entries, captureComplete, target, amount, stock, external, requiredSeeds,
                    preserve, forceCraft, result(), maximumWork, maximumBytes);
        }

        @Override
        public boolean advance(PlanningScheduler.Slice slice) {
            while (slice.nextUncharged()) {
                if (step(slice)) return true;
                if (waitingFor() != null) return false;
            }
            return false;
        }

        public boolean step() { return step(null); }

        private boolean step(PlanningScheduler.Slice slice) {
            if (result != null) return true;
            if (closed) throw new IllegalStateException("Closed captured planning work");
            budget.checkpoint();
            if (phase != 3) budget.phase(PlanningBudget.Phase.BUILD);
            if (phase == 0) {
                indexPotentialProducer();
            } else if (phase == 1) {
                expandOrDiscover();
            } else if (phase == 2) {
                freeze();
            } else if (phase == 4) {
                allocateInput();
            } else if (phase == 5) {
                allocateFlow();
            } else if (phase == 6) {
                if (flowWitness.step()) {
                    var plan = flowWitness.result(); flowWitness.close(); flowWitness = null;
                    if (plan != null) finish(plan, GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL);
                    else { allocationView = false; phase = 2; }
                }
            } else {
                boolean done = slice == null ? solving.step() : solving.advance(slice);
                if (done) acceptOrExpand(solving.result());
                else if (remaining && waitingFor() == null && budget.searchWork() - solveStarted >= solveAllowance) {
                    // A restricted prefix must not spend the whole allowance before
                    // the rest of the captured domain has ever been considered.
                    acceptOrExpand(solving.limited(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT)));
                }
            }
            return result != null;
        }

        private void indexPotentialProducer() {
            budget.compilationCheck();
            if (entry == entries.size()) {
                discover(target);
                for (K key : requiredSeeds.keySet()) discover(key);
                phase = 1;
                return;
            }
            var recipe = entries.get(entry).recipe();
            if (recipe.size() == 0) {
                nextIndexEntry();
            } else if (output < recipe.outputs().size()) {
                var value = recipe.outputs().get(output++);
                if (value.amount() > 0) producer(value.what());
            } else if (input < recipe.inputs().size()) {
                var choices = recipe.inputs().get(input).candidates();
                if (candidate < choices.size()) {
                    var value = choices.get(candidate++);
                    // Actual container returns can introduce a later producer.
                    // Virtual reusable self-returns never manufacture stock.
                    if (value.remaining() != null) producer(value.remaining());
                } else {
                    input++;
                    candidate = 0;
                }
            } else nextIndexEntry();
        }

        private void nextIndexEntry() {
            entry++;
            output = input = candidate = 0;
        }

        private void producer(K key) {
            var values = producers.get(key);
            if (values == null) {
                reserve(144);
                values = new ArrayList<>();
                producers.put(key, values);
            }
            if (values.isEmpty() || values.get(values.size() - 1) != entry) {
                reserve(16);
                values.add(entry);
            }
        }

        private void discover(K key) {
            budget.compilationCheck();
            if (discovered.contains(key)) return;
            reserve(112);
            discovered.add(key);
            pendingKeys.addLast(key);
        }

        private void expandOrDiscover() {
            budget.compilationCheck();
            if (expanding != null) {
                if (expanding.domain.coveredPrefix().compareTo(expanding.through) < 0) {
                    BigInteger ordinal = expanding.domain.coveredPrefix();
                    var value = expanding.domain.next();
                    var source = entries.get(expanding.index);
                    if (!expanding.additional.remove(ordinal)) {
                        expanding.recipes.add(new GraphRecipe<>(source.id() + "#" + ordinal, source.binding(), value.slots(), value.outputs()));
                        for (var slot : value.slots()) discover(slot.key());
                    }
                } else expanding = null;
            } else if (!pendingDomains.isEmpty()) {
                expanding = pendingDomains.removeFirst();
            } else if (provider < providers.size()) {
                int index = providers.get(provider++);
                if (!active.containsKey(index)) {
                    reserve(160);
                    var state = new DomainState(index);
                    active.put(index, state);
                    pendingDomains.addLast(state);
                }
            } else if (!pendingKeys.isEmpty()) {
                providers = producers.getOrDefault(pendingKeys.removeFirst(), List.of());
                provider = 0;
            } else {
                remaining = false;
                var scopes = new ArrayList<DomainCoverage>();
                for (var state : active.values()) {
                    budget.compilationCheck();
                    remaining |= state.domain.hasNext();
                    scopes.add(new DomainCoverage(entries.get(state.index).id(), state.domain.coveredPrefix(),
                            state.domain.size(), List.copyOf(state.additional), state.domain.captureComplete()));
                }
                coverage = new Coverage(++revision, scopes, !remaining && captureComplete);
                // Existing portable journals certify their concrete count model.
                // Until they also carry capture domains, explicitly prevent the
                // archive being presented as a complete full-catalog proof.
                if (!coverage.complete() && budget.proofJournal() != null) budget.proofJournal().markIncomplete();
                budget.note("candidate_coverage", "revision=" + revision + "; domains=" + scopes.size() + "; complete=" + coverage.complete());
                flattenEntry = flattenVariant = 0;
                phase = 2;
            }
        }

        private void freeze() {
            budget.compilationCheck();
            if (indexing == null) {
                if (!flowCounts.isEmpty()) {
                    flowWitness = new CapturedFlowWitness<>(allocationRecipes, flowCounts, target, amount, stock, external,
                            requiredSeeds, preserve, forceCraft, budget);
                    flowCounts.clear(); allocationView = true; phase = 6;
                } else if (!allocationRecipes.isEmpty()) {
                    // The recovered support is a positive-only trial. Even with
                    // all its variants present, the full catalog can spend its
                    // short turn revisiting hundreds of unfunded alternatives.
                    indexing = new CatalogIndex<>(allocationRecipes);
                    allocationRecipes = new ArrayList<>();
                    allocationView = true;
                } else if (flattenEntry < entries.size()) {
                    var state = active.get(flattenEntry);
                    if (state != null && flattenVariant < state.recipes.size()) concrete.add(state.recipes.get(flattenVariant++));
                    else {
                        flattenEntry++;
                        flattenVariant = 0;
                    }
                } else {
                    indexing = new CatalogIndex<>(concrete);
                    concrete = new ArrayList<>();
                }
            } else if (indexing.step(budget)) {
                // A new immutable compiler owns this exact coverage. Never import
                // restricted negative clauses, closures or sessions into the next one.
                solving = new GraphPlanningWork<>(indexing.result(), target, amount, stock, external, requiredSeeds,
                        preserve, forceCraft, budget).catalysts(catalystPolicy);
                indexing = null;
                solveStarted = budget.searchWork();
                solveAllowance = Math.max(256, Math.min(32768, budget.remainingWork() / 4));
                phase = 3;
            }
        }

        private void acceptOrExpand(GraphPlan<K> plan) {
            solving.close();
            solving = null;
            if (plan.feasible()) {
                finish(plan, coverage.complete() && !allocationView ? plan.result() : GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL);
            } else if (allocationView) {
                // No negative conclusion, source learning or count session is
                // imported from the reduced support into the canonical view.
                allocationView = false;
                phase = 2;
            } else if (remaining && budget.remainingWork() > 0 && expandable(plan.result())) {
                for (var state : active.values()) {
                    budget.compilationCheck();
                    if (state.domain.hasNext() && !state.allocationTried) {
                        state.allocationTried = true;
                        pendingAllocations.addLast(state);
                    }
                }
                addedAllocation = false;
                phase = 4;
            } else {
                var reason = plan.result();
                if (!coverage.complete() && negative(reason)) reason = GraphPlan.Result.UNKNOWN;
                finish(plan, reason);
            }
        }

        private void allocateInput() {
            if (allocatedIndex < allocated.size()) {
                BigInteger ordinal = allocated.get(allocatedIndex++);
                var value = allocating.domain.resolve(ordinal);
                var source = entries.get(allocating.index);
                var concreteRecipe = new GraphRecipe<>(source.id() + "#" + ordinal, source.binding(), value.slots(), value.outputs());
                allocationRecipes.add(concreteRecipe);
                if (ordinal.compareTo(allocating.domain.coveredPrefix()) >= 0 && allocating.additional.add(ordinal)) {
                    allocating.recipes.add(0, concreteRecipe);
                    for (var slot : value.slots()) discover(slot.key());
                    addedAllocation = true;
                    budget.note("candidate_allocation", "domain=" + source.id() + "; ordinal=" + ordinal + "; firings=" + allocationFirings);
                }
                if (allocatedIndex == allocated.size()) { allocated = List.of(); allocatedIndex = 0; allocating = null; }
            } else if (allocation != null) {
                if (!allocation.step()) return;
                allocated = allocation.results();
                allocation.close();
                allocation = null;
                if (allocated.isEmpty()) {
                    // A whole-order stock view can fail even though one firing
                    // enables upstream returns. Preserve the smaller recovery.
                    if (allocationFirings > 1) {
                        allocationFirings = 1;
                        allocation = new CapturedInputAllocation<>(entries.get(allocating.index).recipe(), allocating.domain, stock, external, budget);
                    } else allocating = null;
                }
            } else if (!pendingAllocations.isEmpty()) {
                allocating = pendingAllocations.removeFirst();
                var recipe = entries.get(allocating.index).recipe();
                BigInteger output = BigInteger.ZERO;
                for (var value : recipe.outputs()) {
                    budget.compilationCheck();
                    if (value.what().equals(target)) output = output.add(BigInteger.valueOf(value.amount()));
                }
                BigInteger needed = BigInteger.valueOf(amount);
                if (!forceCraft) needed = needed.add(BigInteger.valueOf(requiredSeeds.getOrDefault(target, 0L)))
                        .subtract(BigInteger.valueOf(stock.getOrDefault(target, 0L))).max(BigInteger.ONE);
                allocationFirings = output.signum() > 0 ? needed.subtract(BigInteger.ONE).divide(output).add(BigInteger.ONE).min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact() : 1;
                allocation = new CapturedInputAllocation<>(recipe, allocating.domain, stock, external, allocationFirings, budget);
            } else if (!flowTried && allocationRecipes.isEmpty()) {
                flowTried = true;
                flow = new CapturedFlowAllocation<>(entries, producers, target, amount, stock, external, requiredSeeds, forceCraft, budget);
                phase = 5;
            } else continueExpansion();
        }

        private void allocateFlow() {
            if (flow != null) {
                if (!flow.step()) return;
                flowVariants = flow.results(); reserve(128L * flowVariants.size()); flow.close(); flow = null;
            } else if (flowVariant < flowVariants.size()) {
                var selected = flowVariants.get(flowVariant++);
                var state = active.get(selected.entry());
                if (state == null) {
                    reserve(160);
                    state = new DomainState(selected.entry()); active.put(selected.entry(), state);
                    // A sparse support trial need not instantiate an unrelated
                    // prefix of a newly reached upstream domain first.
                }
                var value = state.domain.resolve(selected.ordinal());
                var source = entries.get(selected.entry());
                var recipe = new GraphRecipe<>(source.id() + "#" + selected.ordinal(), source.binding(), value.slots(), value.outputs());
                allocationRecipes.add(recipe);
                reserve(128);
                flowCounts.put(recipe.id(), selected.firings());
                if (selected.ordinal().compareTo(state.domain.coveredPrefix()) >= 0 && state.additional.add(selected.ordinal())) {
                    state.recipes.add(0, recipe); addedAllocation = true;
                }
                for (var slot : value.slots()) discover(slot.key());
            } else {
                flowVariants = List.of(); flowVariant = 0;
                continueExpansion();
            }
        }

        private void continueExpansion() {
            if (!addedAllocation && allocationRecipes.isEmpty()) for (var state : active.values()) {
                budget.compilationCheck();
                if (!state.domain.hasNext()) continue;
                state.through = state.domain.coveredPrefix().max(BigInteger.valueOf(CapturedRecipe.MAX_VARIANTS))
                        .shiftLeft(1).min(state.domain.size());
                pendingDomains.addLast(state);
            }
            phase = 1;
        }

        private boolean expandable(GraphPlan.Result reason) {
            return negative(reason) || reason == GraphPlan.Result.UNKNOWN || reason == GraphPlan.Result.SEARCH_LIMIT;
        }

        private boolean negative(GraphPlan.Result reason) {
            return reason == GraphPlan.Result.INFEASIBLE || reason == GraphPlan.Result.MISSING_INPUT || reason == GraphPlan.Result.MISSING_SEED;
        }

        private void finish(GraphPlan<K> plan, GraphPlan.Result reason) {
            // Restricted previews/seed certificates aren't exported as conclusions
            // about omitted candidates. A positive witness remains independently valid.
            boolean preview = coverage.complete() || plan.feasible();
            var answer = new GraphPlan<>(target, amount, preserve,
                    preview ? plan.steps() : new PlanStep.Sequence(List.of()), preview ? plan.recipes() : Map.of(),
                    preview ? plan.initialExact() : Map.of(), preview ? plan.seeds() : Map.of(),
                    preview ? plan.missingExact() : Map.of(), reason, budget.nodes(), System.nanoTime() - started);
            if (coverage.complete() && !allocationView) answer = answer.withSeedOptimality(plan.seedOptimality()).withAlternatives(plan.alternatives());
            result = new Result<>(answer, coverage);
            budget.phase(PlanningBudget.Phase.COMPLETE);
        }

        @Override
        public CompletableFuture<?> waitingFor() { return solving == null ? null : solving.waitingFor(); }

        @Override
        public Result<K> result() {
            if (result == null) throw new IllegalStateException("Captured planning is incomplete");
            return result;
        }

        @Override
        public Result<K> limited(PlanningBudget.Exhausted limit) {
            if (result != null) return result;
            GraphPlan<K> retained = solving == null ? null : solving.limited(limit);
            if (retained != null && retained.feasible()) finish(retained, GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL);
            else {
                var reason = GraphPlan.Result.valueOf(limit.limit().name());
                var empty = new GraphPlan<K>(target, amount, preserve, new PlanStep.Sequence(List.of()), Map.of(), Map.of(),
                        Map.of(), Map.of(), reason, budget.nodes(), System.nanoTime() - started);
                finish(empty, reason);
            }
            return result;
        }

        private void reserve(long bytes) {
            budget.reserve(bytes);
            memory += bytes;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (solving != null) solving.close();
            solving = null;
            indexing = null;
            if (allocation != null) allocation.close();
            allocation = null;
            if (flow != null) flow.close();
            flow = null;
            flowVariants = List.of();
            if (flowWitness != null) flowWitness.close();
            flowWitness = null;
            flowCounts.clear();
            allocating = null;
            allocated = List.of();
            expanding = null;
            for (var state : active.values()) state.domain.close();
            active.clear();
            pendingDomains.clear();
            pendingAllocations.clear();
            pendingKeys.clear();
            discovered.clear();
            producers.clear();
            providers = List.of();
            concrete.clear();
            allocationRecipes.clear();
            budget.release(memory);
            memory = 0;
        }

        private final class DomainState {
            final int index;
            final CapturedRecipeDomain<K> domain;
            final List<GraphRecipe<K>> recipes = new ArrayList<>();
            final Set<BigInteger> additional = new java.util.LinkedHashSet<>();
            BigInteger through;
            boolean allocationTried;

            DomainState(int index) {
                this.index = index;
                domain = entries.get(index).recipe().domain(budget);
                through = BigInteger.valueOf(CapturedRecipe.MAX_VARIANTS).min(domain.size());
            }
        }
    }
}
