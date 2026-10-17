// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
    private final boolean stateFamilies;
    private static final long MAX_PRODUCER_CACHE_BYTES = 8L << 20;
    /** Storage is transferred only after its final insertion; published lists are read-only. */
    private record Producers<K>(Map<K, List<Integer>> storage, Map<K, List<Integer>> view,
                                long references, long bytes) {}
    private volatile Producers<K> retainedProducers;
    private long producerHits, producerMisses;
    private final CapturedSupportCache<K> stateSupports;
    private final CapturedStateProducers<K> stateProducers;
    private final CapturedStateSources<K> stateSourceHints;

    CapturedStateSources.Metrics stateSourceMetrics() {
        return stateSourceHints == null ? new CapturedStateSources.Metrics(0, 0, 0) : stateSourceHints.metrics();
    }

    CapturedStateProducers.Metrics stateProducerMetrics() {
        return stateProducers == null ? new CapturedStateProducers.Metrics(0, 0, 0, 0) : stateProducers.metrics();
    }

    /** Source/key hints only, scoped to this capture and exact target/seed-key set. */
    public record StateSupportCacheMetrics(long hits, long misses, int entries, long estimatedBytes, long maximumBytes) {}

    public StateSupportCacheMetrics stateSupportMetrics() {
        if (stateSupports == null) return new StateSupportCacheMetrics(0, 0, 0, 0, CapturedSupportCache.MAX_BYTES);
        var values = stateSupports.metrics();
        return new StateSupportCacheMetrics(values.hits(), values.misses(), values.entries(), values.bytes(), values.maximumBytes());
    }

    /** Logical retained auxiliary caches, independent of a request's workspace reservation. */
    public long retainedCacheBytes() {
        return stateSupportMetrics().estimatedBytes() + producerIndexMetrics().estimatedBytes() +
                stateProducerMetrics().bytes() + stateSourceMetrics().bytes();
    }

    /** Structural containers only; stock, coverage and solver results are never retained here. */
    public record ProducerCacheMetrics(long hits, long misses, int keys, long references,
                                       long estimatedBytes, long maximumBytes) {}

    public synchronized ProducerCacheMetrics producerIndexMetrics() {
        var saved = retainedProducers;
        return new ProducerCacheMetrics(producerHits, producerMisses, saved == null ? 0 : saved.view.size(),
                saved == null ? 0 : saved.references, saved == null ? 0 : saved.bytes, MAX_PRODUCER_CACHE_BYTES);
    }

    private synchronized Producers<K> cachedProducers() {
        if (retainedProducers == null) { if (producerMisses < Long.MAX_VALUE) producerMisses++; }
        else if (producerHits < Long.MAX_VALUE) producerHits++;
        return retainedProducers;
    }

    private Producers<K> retainProducers(Map<K, List<Integer>> completed, long bytes, long references, PlanningBudget budget) {
        synchronized (this) {
            if (retainedProducers != null) return retainedProducers;
            // One-off auxiliary catalogs keep their original request-owned lifetime.
            if (producerMisses < 2 || bytes == 0 || bytes > MAX_PRODUCER_CACHE_BYTES - 128) return null;
        }
        // Transfer completed storage without cloning it. No host callback or
        // budget checkpoint runs while holding the catalog publication monitor.
        if (!budget.tryCacheCharge(1)) return null;
        synchronized (this) {
            if (retainedProducers == null)
                retainedProducers = new Producers<>(completed, Collections.unmodifiableMap(completed), references, bytes + 128);
            return retainedProducers;
        }
    }

    public CapturedCatalog(List<Entry<K>> entries) { this(entries, true); }

    /** complete=false also covers omitted providers, not just truncated input alternatives. */
    public CapturedCatalog(List<Entry<K>> entries, boolean complete) {
        this.entries = List.copyOf(entries);
        Set<String> ids = new HashSet<>();
        boolean families = false;
        for (var entry : entries) {
            if (!ids.add(entry.id())) throw new IllegalArgumentException("Duplicate captured recipe id: " + entry.id());
            complete &= !entry.recipe().captureBounded();
            for (var input : entry.recipe().inputs()) for (var candidate : input.candidates())
                families |= candidate.family() != null;
        }
        captureComplete = complete;
        stateFamilies = families;
        stateSupports = families && entries.size() > 128 ? new CapturedSupportCache<>() : null;
        stateProducers = stateSupports == null ? null : new CapturedStateProducers<>();
        stateSourceHints = stateSupports == null ? null : new CapturedStateSources<>();
    }

    /** Same potential-output scope as Work's index, including unexpanded returns. */
    boolean hasPotentialProducer(K target) {
        var saved = retainedProducers;
        if (saved != null) return saved.view.containsKey(target);
        for (var entry : entries) {
            var recipe = entry.recipe();
            if (recipe.size() == 0) continue;
            for (var output : recipe.outputs())
                if (output.amount() > 0 && target.equals(output.what())) return true;
            for (var input : recipe.inputs())
                for (var candidate : input.candidates())
                    if (target.equals(candidate.remaining())) return true;
        }
        return false;
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
        private Map<K, List<Integer>> producers = new HashMap<>();
        private boolean producerLookup, sharedProducers;
        private long producerMemory, producerReferences;
        private final Map<Integer, DomainState> active = new LinkedHashMap<>();
        private final Set<Integer> sourceOrder = new TreeSet<>();
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
        private CapturedReachableWitness<K> reachable;
        private CapturedStatePlanning<K> statePlanning;
        private CapturedRefillRecovery<K> refillRecovery;
        private GraphPlan<K> refillCandidate;
        private CapturedStateSupport<K> stateSupport;
        private CapturedStockProjection<K> stateStock;
        private List<Entry<K>> stateSources;
        private List<Integer> stateSourceIndices;
        private boolean fullStateTried, stateRecoveryTried, stateHintsTried, stateHintTrial;
        private long stateStarted, stateAllowance, stockTrialStarted, stockTrialAllowance;
        private long hintTrialStarted, hintTrialAllowance;
        private boolean reachableTried;
        private GraphPlan<K> reachablePlan;
        private List<CapturedReachableWitness.Variant<K>> reachableVariants = List.of();
        private int reachableVariant;
        private DomainState expanding;
        private List<Integer> providers = List.of();
        private int provider;
        private int entry, output, input, candidate;
        private int phase; // 0 index, 1 discover, 2 freeze, 3 solve, 4 stock, 5 flow, 6 recovered counts, 7 reachable, 8 state capacity, 9 state support, 10 stock projection
        private Iterator<Integer> flattenSources;
        private DomainState flattenState;
        private int flattenVariant;
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
            this.stock = PlanningInventory.snapshot(stock);
            this.external = Set.copyOf(external);
            this.requiredSeeds = PlanningInventory.snapshot(requiredSeeds);
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

        private boolean conditionalPreviews;

        /** Request owners may retain a verified refill witness after domain search. */
        Work conditionalPreviews(boolean enabled) {
            if (producerLookup || entry != 0 || phase != 0) throw new IllegalStateException("Planning already started");
            conditionalPreviews = enabled;
            return this;
        }

        /**
         * Export this request's final candidate scope and optional concrete witness.
         * May be called after close(); no live keys or solver state enter the archive.
         * Export has separate explicit limits and cannot consume the solve allowance.
         * Scoped CountProof journals remain separate, with their original assumptions.
         */
        public CapturedProof.Certificate certificate(long maximumWork, long maximumBytes) {
            if (stateFamilies) throw new UnsupportedOperationException("Captured state-family algebra is not portable; finite-domain certificates cannot certify dynamic states");
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
            if (stateHintTrial && (phase == 8 || phase == 9) && budget.nodes() - hintTrialStarted >= hintTrialAllowance) {
                if (stateSupport != null) stateSupport.close();
                stateSupport = null;
                if (statePlanning != null) statePlanning.close();
                statePlanning = null; closeStateStock();
                budget.note("candidate_state_source_hints", "limited; remaining=" + stateRemaining() + "; negative_transfer=false");
                if (!beginStateRecovery()) {
                    if (!fullStateTried) beginStatePlanning(entries);
                    else phase = 1;
                }
                return false;
            }
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
            } else if (phase == 7) {
                recoverReachable();
            } else if (phase == 8) {
                if (stateStock != null && budget.nodes() - stockTrialStarted >= stockTrialAllowance) {
                    retryFullStock(); return false;
                }
                if (statePlanning.step()) {
                    var plan = statePlanning.result(); statePlanning.close(); statePlanning = null;
                    if (plan != null) {
                        if (stateSourceHints != null && sharedProducers)
                            stateSourceHints.retain(entries, stateSourceIndices, budget);
                        if ((stateRecoveryTried || stateHintsTried) && stateSources != entries && stateSupports != null && sharedProducers)
                            stateSupports.retainExpanded(target, requiredSeeds.keySet(), stateSources, budget);
                        if (stateStock == null && stateSupports != null && sharedProducers)
                            stateSupports.retainStockKeys(target, requiredSeeds.keySet(), stateSources,
                                    plan.initialExact().keySet(), stock.size(), budget);
                        closeStateStock();
                        allocationView = true;
                        finish(plan, GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL);
                    } else if (stateStock != null) retryFullStock();
                    else if (beginStateRecovery()) { /* Continue the same arm with additional source support. */ }
                    else if (!fullStateTried) beginStatePlanning(entries);
                    else phase = 1;
                }
            } else if (phase == 9) {
                if (stateSupport.step()) {
                    stateSourceIndices = sharedProducers ? stateSupport.retainedIndices() : null;
                    var selected = stateSupport.result(); stateSupport.close(); stateSupport = null;
                    if (selected != null && sharedProducers) stateSupports.retain(target, requiredSeeds.keySet(), selected, budget);
                    if (selected == null && beginStateRecovery()) return false;
                    beginStatePlanning(selected == null ? entries : selected);
                }
            } else if (phase == 10) {
                if (budget.nodes() - stockTrialStarted >= stockTrialAllowance) retryFullStock();
                else if (stateStock.step()) {
                    var projected = stateStock.result();
                    if (projected == null) retryFullStock();
                    else {
                        budget.note("candidate_state_stock", "projected=" + projected.size() + "; inventory=" + stock.size() +
                                "; trial_allowance=" + stockTrialAllowance + "; negative_proofs=false");
                        startStatePlanning(projected);
                    }
                }
            } else if (phase == 11) {
                if (refillRecovery.step()) {
                    var recovered = refillRecovery.result(); refillRecovery.close(); refillRecovery = null;
                    finish(recovered == null ? refillCandidate : recovered, recovered != null && recovered.feasible() ?
                            GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : GraphPlan.Result.INCOMPLETE_MODEL);
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
            if (!producerLookup) {
                producerLookup = true;
                var cached = cachedProducers();
                if (cached != null) {
                    producers = cached.view; sharedProducers = true; entry = entries.size();
                    budget.note("candidate_index", "hit; keys=" + producers.size() + "; references=" + cached.references);
                }
            }
            if (entry == entries.size()) {
                if (!sharedProducers) {
                    var saved = retainProducers(producers, producerMemory, producerReferences, budget);
                    if (saved != null) {
                        // Another request may have published first. Release only
                        // this request's duplicate storage, never the shared map.
                        if (saved.storage != producers) producers.clear();
                        producers = saved.view; sharedProducers = true;
                        budget.release(producerMemory); memory -= producerMemory; producerMemory = 0;
                        budget.note("candidate_index", "retained; keys=" + producers.size() + "; bytes=" + saved.bytes);
                    }
                }
                discover(target);
                for (K key : requiredSeeds.keySet()) discover(key);
                if (stateFamilies) {
                    stateStarted = budget.nodes(); stateAllowance = budget.remainingWork() / 2;
                    var hint = stateSupports == null ? null : stateSupports.select(target, requiredSeeds.keySet(), budget);
                    if (hint != null) {
                        budget.note("candidate_state_support", "reused; selected=" + hint.sources().size() + "; catalog=" + entries.size() + "; negative_proofs=false");
                        beginStatePlanning(hint.sources(), hint.stockKeys()); return;
                    }
                    if (entries.size() > 128) stateSupport = CapturedStateSupport.create(entries, producers, target, requiredSeeds.keySet(), budget,
                            sharedProducers ? stateProducers : null);
                    if (stateSupport != null) phase = 9;
                    else beginStatePlanning(entries);
                } else phase = 1;
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

        private void beginStatePlanning(List<Entry<K>> sources) {
            beginStatePlanning(sources, null);
        }

        private boolean beginStateRecovery() {
            stateHintTrial = false;
            if (entries.size() <= 128) return false;
            if (!stateHintsTried) {
                stateHintsTried = true;
                var hints = sharedProducers && stateSourceHints != null ? stateSourceHints.get(entries, budget) : null;
                stateSupport = CapturedStateSupport.recoverHints(entries, producers, target, requiredSeeds.keySet(), budget,
                        sharedProducers ? stateProducers : null, stateRemaining(), hints);
                if (stateSupport != null) {
                    stateHintTrial = true; hintTrialStarted = budget.nodes();
                    hintTrialAllowance = Math.min(65_536, stateRemaining() / 4);
                    budget.note("candidate_state_source_hints", "start; sources=" + hints.size() + "; remaining=" + stateRemaining() +
                            "; allowance=" + hintTrialAllowance + "; negative_proofs=false");
                    phase = 9; return true;
                }
            }
            if (stateRecoveryTried) return false;
            stateRecoveryTried = true;
            stateSupport = CapturedStateSupport.recover(entries, producers, target, requiredSeeds.keySet(), budget,
                    sharedProducers ? stateProducers : null, stateRemaining());
            if (stateSupport == null) return false;
            budget.note("candidate_state_support_recovery", "start; remaining=" + stateRemaining() + "; negative_proofs=false");
            phase = 9; return true;
        }

        private void beginStatePlanning(List<Entry<K>> sources, List<K> stockKeys) {
            stateSources = sources;
            fullStateTried = sources == entries;
            if (fullStateTried) stateSourceIndices = null;
            long remaining = stateRemaining();
            if (fullStateTried && phase == 8) budget.note("candidate_state_support", "retry_full_state; remaining=" + remaining +
                    "; shared_allowance=" + stateAllowance + "; negative_transfer=false");
            if (remaining >= 2048) stateStock = CapturedStockProjection.create(stockKeys, stock, budget);
            if (stateStock != null) {
                stockTrialStarted = budget.nodes(); stockTrialAllowance = remaining / 2;
                phase = 10;
            } else startStatePlanning(stock);
        }

        private long stateRemaining() { return Math.max(0, stateAllowance - (budget.nodes() - stateStarted)); }

        private void startStatePlanning(Map<K, Long> view) {
            // Selection, the small positive attempt and original state attempt
            // share one lifetime allowance. A private miss cannot exclude sources.
            // The outer stock trial owns its deadline; do not halve the nested
            // recovery admission again before it has performed any actual work.
            long remaining = stateRemaining();
            if (stateHintTrial) remaining = Math.min(remaining, Math.max(0, hintTrialAllowance - (budget.nodes() - hintTrialStarted)));
            statePlanning = new CapturedStatePlanning<>(stateSources, target, amount, view, external, requiredSeeds,
                    preserve, forceCraft, budget, remaining);
            phase = 8;
        }

        private void retryFullStock() {
            if (statePlanning != null) statePlanning.close();
            statePlanning = null; closeStateStock();
            budget.note("candidate_state_stock", "retry_full_stock; remaining=" + stateRemaining() +
                    "; shared_allowance=" + stateAllowance + "; negative_transfer=false");
            startStatePlanning(stock);
        }

        private void closeStateStock() {
            if (stateStock != null) stateStock.close();
            stateStock = null;
        }

        private void producer(K key) {
            var values = producers.get(key);
            if (values == null) {
                reserve(144);
                producerMemory += 144;
                values = new ArrayList<>();
                producers.put(key, values);
            }
            if (values.isEmpty() || values.get(values.size() - 1) != entry) {
                reserve(16);
                producerMemory += 16; producerReferences++;
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
                    var state = activate(index);
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
                flattenSources = null; flattenState = null; flattenVariant = 0;
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
                } else {
                    if (flattenSources == null) flattenSources = sourceOrder.iterator();
                    if (flattenState == null && flattenSources.hasNext()) flattenState = active.get(flattenSources.next());
                    if (flattenState != null) {
                        if (flattenVariant < flattenState.recipes.size()) concrete.add(flattenState.recipes.get(flattenVariant++));
                        else { flattenState = null; flattenVariant = 0; }
                    } else {
                        indexing = new CatalogIndex<>(concrete);
                        concrete = new ArrayList<>();
                    }
                }
            } else if (indexing.step(budget)) {
                // A new immutable compiler owns this exact coverage. Never import
                // restricted negative clauses, closures or sessions into the next one.
                solving = new GraphPlanningWork<>(indexing.result(), target, amount, stock, external, requiredSeeds,
                        preserve, forceCraft, budget).catalysts(catalystPolicy)
                        .missingPreview(!allocationView && (coverage.complete() || conditionalPreviews && !remaining));
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
                if (!reachableTried) {
                    reachableTried = true;
                    reachable = new CapturedReachableWitness<>(entries, producers, target, amount, stock, external,
                            requiredSeeds, preserve, forceCraft, budget);
                    phase = 7;
                } else beginInputAllocation();
            } else {
                var reason = plan.result();
                if (!coverage.complete() && negative(reason)) reason = conditionalPreviews && !plan.missingExact().isEmpty() ?
                        GraphPlan.Result.INCOMPLETE_MODEL : GraphPlan.Result.UNKNOWN;
                if (reason == GraphPlan.Result.INCOMPLETE_MODEL && stateFamilies && budget.remainingWork() >= 4096) {
                    refillCandidate = plan;
                    refillRecovery = new CapturedRefillRecovery<>(CapturedCatalog.this, entries, plan, stock, external,
                            requiredSeeds, forceCraft, catalystPolicy, budget);
                    phase = 11;
                } else finish(plan, reason);
            }
        }

        private void beginInputAllocation() {
            for (var state : active.values()) {
                budget.compilationCheck();
                if (state.domain.hasNext() && !state.allocationTried) {
                    state.allocationTried = true;
                    pendingAllocations.addLast(state);
                }
            }
            addedAllocation = false;
            phase = 4;
        }

        private void recoverReachable() {
            if (reachablePlan == null) {
                if (!reachable.step()) return;
                reachablePlan = reachable.result();
                if (reachablePlan == null) {
                    reachable.close(); reachable = null;
                    beginInputAllocation();
                } else reachableVariants = reachable.variants();
                return;
            }
            if (reachableVariant < reachableVariants.size()) {
                budget.compilationCheck();
                var variant = reachableVariants.get(reachableVariant++);
                var state = active.get(variant.entry());
                if (state == null) {
                    state = activate(variant.entry());
                }
                if (variant.ordinal().compareTo(state.domain.coveredPrefix()) >= 0 && state.additional.add(variant.ordinal())) {
                    reserve(128); state.recipes.add(variant.recipe());
                }
                return;
            }
            var scopes = new ArrayList<DomainCoverage>();
            boolean complete = captureComplete;
            for (var state : active.values()) {
                budget.compilationCheck();
                complete &= !state.domain.hasNext();
                scopes.add(new DomainCoverage(entries.get(state.index).id(), state.domain.coveredPrefix(), state.domain.size(),
                        List.copyOf(state.additional), state.domain.captureComplete()));
            }
            coverage = new Coverage(++revision, scopes, complete);
            allocationView = true;
            finish(reachablePlan, GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL);
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
                    state = activate(selected.entry());
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
            // A funded refill witness is useful even when omitted candidates
            // prevent an infeasibility proof. Keep that scope explicit and never
            // transfer seed optimality or negative conclusions from the view.
            boolean conditional = conditionalPreviews && reason == GraphPlan.Result.INCOMPLETE_MODEL && !plan.missingExact().isEmpty();
            boolean preview = coverage.complete() || plan.feasible() || conditional;
            var answer = new GraphPlan<>(target, amount, preserve,
                    preview ? plan.steps() : new PlanStep.Sequence(List.of()), preview ? plan.recipes() : Map.of(),
                    preview ? plan.initialExact() : Map.of(), preview ? plan.seeds() : Map.of(),
                    preview ? plan.missingExact() : Map.of(), reason, budget.nodes(), System.nanoTime() - started);
            if (coverage.complete() && !allocationView) answer = answer.withSeedOptimality(plan.seedOptimality()).withAlternatives(plan.alternatives());
            if (conditional) {
                budget.failureDetail("CONDITIONAL_REFILL: verified when supplied; omitted alternatives may need less or no refill");
                budget.note("candidate_refill", "verified; missing_keys=" + answer.missingExact().size() + "; infeasibility_proven=false");
            }
            result = new Result<>(answer, coverage);
            budget.phase(PlanningBudget.Phase.COMPLETE);
        }

        @Override
        public CompletableFuture<?> waitingFor() {
            return refillRecovery != null ? refillRecovery.waitingFor() :
                    statePlanning != null ? statePlanning.waitingFor() : solving == null ? null : solving.waitingFor();
        }

        @Override
        public Result<K> result() {
            if (result == null) throw new IllegalStateException("Captured planning is incomplete");
            return result;
        }

        @Override
        public Result<K> limited(PlanningBudget.Exhausted limit) {
            if (result != null) return result;
            checkHandoffCancellation();
            if (refillCandidate != null) {
                // This prefix was verified before the optional tool-refill
                // improvement began. An interrupted retry cannot erase it.
                finish(refillCandidate, GraphPlan.Result.INCOMPLETE_MODEL);
                return result;
            }
            GraphPlan<K> retained = solving == null ? null : solving.limited(limit);
            checkHandoffCancellation();
            if (retained != null && retained.feasible()) finish(retained, GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL);
            else if (retained != null && !retained.missingExact().isEmpty() && !allocationView &&
                    (coverage.complete() || conditionalPreviews && !remaining) &&
                    (retained.result() == GraphPlan.Result.MISSING_INPUT || retained.result() == GraphPlan.Result.MISSING_SEED))
                finish(retained, coverage.complete() ? retained.result() : GraphPlan.Result.INCOMPLETE_MODEL);
            // Only the canonical model with complete relevant capture coverage
            // can close the order. A completed root proof must survive the same
            // budget handoff as a verified witness; restricted support/prefix
            // failures and ordinary missing previews carry no such authority.
            else if (retained != null && retained.result() == GraphPlan.Result.INFEASIBLE &&
                    coverage.complete() && !allocationView) finish(retained, GraphPlan.Result.INFEASIBLE);
            else {
                var reason = GraphPlan.Result.valueOf(limit.limit().name());
                var empty = new GraphPlan<K>(target, amount, preserve, new PlanStep.Sequence(List.of()), Map.of(), Map.of(),
                        Map.of(), Map.of(), reason, budget.nodes(), System.nanoTime() - started);
                finish(empty, reason);
            }
            return result;
        }

        private void checkHandoffCancellation() {
            try {
                budget.checkpoint();
            } catch (PlanningBudget.Exhausted exhausted) {
                // This method handles an exhausted budget. Cancellation remains
                // exceptional, including cancellation during child harvesting.
            }
        }

        private void reserve(long bytes) {
            budget.reserve(bytes);
            memory += bytes;
        }

        private DomainState activate(int index) {
            reserve(224);
            // Discovery order still controls allocation and coverage. The small
            // ordered set only skips inactive sources while freezing canonical
            // variants, preserving their exact original catalog priority.
            budget.compilationCharge(32 - Integer.numberOfLeadingZeros(active.size() + 1));
            var state = new DomainState(index);
            active.put(index, state); sourceOrder.add(index);
            return state;
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
            if (reachable != null) reachable.close();
            reachable = null;
            if (statePlanning != null) statePlanning.close();
            statePlanning = null;
            if (refillRecovery != null) refillRecovery.close();
            refillRecovery = null; refillCandidate = null;
            if (stateSupport != null) stateSupport.close();
            stateSupport = null;
            closeStateStock(); stateSources = null;
            reachablePlan = null;
            reachableVariants = List.of();
            flowCounts.clear();
            allocating = null;
            allocated = List.of();
            expanding = null;
            for (var state : active.values()) state.domain.close();
            active.clear();
            sourceOrder.clear(); flattenSources = null; flattenState = null;
            pendingDomains.clear();
            pendingAllocations.clear();
            pendingKeys.clear();
            discovered.clear();
            if (!sharedProducers) producers.clear();
            producers = Map.of();
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
