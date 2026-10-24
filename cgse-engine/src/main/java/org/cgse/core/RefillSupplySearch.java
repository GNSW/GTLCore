// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Optional main-planner prefix witnesses, followed by bounded verified supply tradeoffs. */
final class RefillSupplySearch<K> implements AutoCloseable {
    private enum Phase { ROOT, DISCOVER, INPUTS, OUTPUTS, COMPILE, MACROS, MACRO_SUMMARY, CYCLES, COMPLETION, OPTION_SIZE, OPTIONS, REFINE, FRONTIER, TRIAL, SUPPLY, SEARCH, PREFIXES, WORDS, SIZE_SUMMARY, SIZE, SUMMARY, COPY, NEEDS, BINDINGS, MISSING, VERIFY, DONE }
    private static final class WordFrame {
        final PlanStep step;
        int child;
        WordFrame(PlanStep step) { this.step = step; }
    }
    private final GraphPlan<K> original;
    private GraphPlan<K> result, base, candidate;
    private final RefillProducerOrder<K> order;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final boolean force, ignoreTargetStock;
    private final PlanningBudget budget;
    private final long started;
    private long passStarted, allowance;
    private final String retainedFailure;
    private Iterator<K> roots;
    private Phase phase = Phase.ROOT;
    private K root;
    private int rank, trial, incidences;
    private BigInteger missing, trialAmount;
    private Set<BigInteger> tried;
    private Map<String, GraphRecipe<K>> recipes, bindings;
    private Deque<RefillProducerOrder<K>.Node> pending;
    private Set<K> visited, domain, trialExternal;
    private Iterator<RefillProducerOrder<K>.Source> sources;
    private Iterator<K> ports;
    private GraphRecipe<K> recipe;
    private Map<K, BigInteger> supplied, goals, initial, deficits;
    private Iterator<Map.Entry<K, BigInteger>> entries;
    private Iterator<Map.Entry<String, GraphRecipe<K>>> bindingEntries;
    private BackwardCoverability<K> searching;
    private LinearMacroCompilation<K> compiling;
    private RefillCycleActions<K> cycling;
    private RefillCycleCompletion<K> completing;
    private List<BackwardCoverability.Action<K>> macros;
    private Iterator<GraphRecipe<K>> compiledRecipes;
    private PlanStep macro;
    private SummaryComputation<K> summarizing;
    private SequenceSummary<K> summary;
    private PlanVerification<K> verifying;
    private SeedStartupGuard<K> startup;
    private PlanStep prefix;
    private boolean strict, mergingBindings, accelerated, shortcutsRelevant, resizing;
    private PlanStep retainedWord;
    private Deque<WordFrame> words;
    private Set<PlanStep> seenWords;
    private int wordVisits, sizedWords;
    private RefillRepeatSizing<K> sizing;
    private GraphCompiler<K> tradeoffCompiler;
    private Set<String> tradeoffExcluded;
    private boolean trading;
    private RefillFrontier<K> frontier;
    private MissingMaterialExpansion<K> refining;
    private Iterator<BackwardCoverability.Action<K>> optionActions;
    private BackwardCoverability.Action<K> optionAction;
    private BigInteger optionRuns, optionFundedRuns;
    private int optionTrial, offered;
    private long memory;

    RefillSupplySearch(GraphPlan<K> original, RefillProducerOrder<K> order, Map<K, Long> stock,
                       Set<K> external, boolean force, boolean ignoreTargetStock, PlanningBudget budget) {
        this.original = result = original; this.order = order; this.stock = stock; this.external = external;
        this.force = force; this.ignoreTargetStock = ignoreTargetStock; this.budget = budget;
        roots = original.missingExact().keySet().iterator(); started = budget.nodes();
        passStarted = started;
        allowance = Math.min(8192, budget.remainingWork() / 4); retainedFailure = budget.failureDetail();
    }

    /** Main planning opts in only after its existing supply refinements. */
    RefillSupplySearch<K> tradeoffs(GraphCompiler<K> compiler, Set<String> excluded) {
        tradeoffCompiler = compiler; tradeoffExcluded = excluded; return this;
    }

    boolean step() {
        try {
            budget.checkpoint();
            if (phase == Phase.DONE) return true;
            if (budget.nodes() - passStarted >= allowance) return finish();
            if (phase == Phase.COMPILE) {
                if (!compiling.step()) return false;
                compiledRecipes = compiling.recipes().values().iterator(); phase = Phase.MACROS; return false;
            }
            if (phase == Phase.MACRO_SUMMARY) {
                if (!summarizing.step()) return false;
                macros.add(new BackwardCoverability.Action<>(macro, summarizing.result()));
                summarizing.close(); summarizing = null; macro = null; phase = Phase.MACROS; return false;
            }
            if (phase == Phase.CYCLES) {
                if (!cycling.step()) return false;
                macros = cycling.actions();
                if (trading) { optionActions = macros.iterator(); phase = Phase.OPTIONS; }
                else if (macros.isEmpty()) endRoot();
                else phase = Phase.TRIAL;
                return false;
            }
            if (phase == Phase.COMPLETION) {
                if (!completing.step()) return false;
                optionActions = completing.actions().iterator(); phase = Phase.OPTIONS; return false;
            }
            if (phase == Phase.OPTION_SIZE) {
                if (!sizing.step()) return false;
                var runs = sizing.result(); sizing = null;
                if (runs.compareTo(BigInteger.ONE) > 0 && !runs.equals(optionRuns)) optionFundedRuns = runs;
                phase = Phase.OPTIONS; return false;
            }
            if (phase == Phase.REFINE) {
                if (!refining.step()) return false;
                if (refining.result() != candidate) memory += refining.takeResultCharge();
                candidate = refining.result(); refining.close(); refining = null;
                frontier.offer(candidate); phase = Phase.FRONTIER; return false;
            }
            if (phase == Phase.FRONTIER) {
                if (!frontier.step()) return false;
                result = frontier.result(); endTrial(); return false;
            }
            if (phase == Phase.SEARCH) {
                if (!searching.step()) return false;
                if (searching.result() != BackwardCoverability.Result.WITNESS) { endTrial(); return false; }
                prefix = searching.witness(); searching.close(); searching = null;
                summarizing = new SummaryComputation<>(prefix, recipes, budget); phase = Phase.SUMMARY; return false;
            }
            if (phase == Phase.SIZE_SUMMARY) {
                if (!summarizing.step()) return false;
                sizing = new RefillRepeatSizing<>(summarizing.result(), base, stock, external, force, ignoreTargetStock, budget);
                phase = Phase.SIZE; return false;
            }
            if (phase == Phase.SIZE) {
                if (!sizing.step()) return false;
                BigInteger runs = sizing.result(); sizing = null;
                if (runs.signum() == 0) { endTrial(); return false; }
                prefix = PlanStep.repeat(prefix, runs);
                summarizing.close(); summarizing = new SummaryComputation<>(prefix, recipes, budget);
                phase = Phase.SUMMARY; return false;
            }
            if (phase == Phase.SUMMARY) {
                if (!summarizing.step()) return false;
                summary = summarizing.result();
                // The suffix's production credit is unchanged only if the
                // added prefix consumes no net finished target, even externally.
                if (force && summary.delta(base.target()).signum() < 0) { endTrial(); return false; }
                initial = new LinkedHashMap<>(); deficits = new LinkedHashMap<>(); bindings = new LinkedHashMap<>();
                mergingBindings = false;
                entries = base.initialExact().entrySet().iterator(); phase = Phase.COPY; return false;
            }
            if (phase == Phase.VERIFY) {
                if (!verifying.step()) return false;
                if (candidate.preserveSeeds()) {
                    if (startup == null) startup = new SeedStartupGuard<>(
                            tradeoffCompiler == null ? bindings.values() : tradeoffCompiler.catalog(),
                            tradeoffCompiler == null ? Set.of() : tradeoffExcluded, verifying.summary(),
                            stock, candidate.seeds(), external, candidate.target(), candidate.amount(), budget);
                    if (!startup.step()) return false;
                    boolean lost = !startup.lost().isEmpty();
                    startup.close(); startup = null;
                    // A funded prefix can consume the network remainder that
                    // kept the old suffix restartable. Its explicit seed map
                    // alone cannot protect that implicit startup material.
                    if (lost) { rejectCandidate(); return false; }
                }
                if (trading) {
                    verifying.close(); verifying = null;
                    if (candidate.feasible()) { frontier.offer(candidate); phase = Phase.FRONTIER; }
                    else {
                        refining = MissingMaterialExpansion.alternative(tradeoffCompiler, candidate, stock, external,
                                tradeoffExcluded, force, ignoreTargetStock, budget, order);
                        phase = Phase.REFINE;
                    }
                } else { result = candidate; if (resizing) endTrial(); else endRoot(); }
                return false;
            }
            for (int i = 0; i < 64; i++) {
                budget.check();
                if (advance()) return true;
                if (phase == Phase.COMPILE || phase == Phase.MACRO_SUMMARY || phase == Phase.CYCLES || phase == Phase.COMPLETION || phase == Phase.OPTION_SIZE || phase == Phase.SEARCH || phase == Phase.SIZE_SUMMARY || phase == Phase.SIZE || phase == Phase.SUMMARY || phase == Phase.VERIFY || phase == Phase.REFINE || phase == Phase.FRONTIER) return false;
            }
            return false;
        } catch (PlanningBudget.Exhausted limit) {
            budget.failureDetail(retainedFailure); return complete();
        }
    }

    private boolean advance() {
        switch (phase) {
            case OPTIONS -> {
                if (offered == 8) return complete();
                if (optionAction == null) {
                    if (!optionActions.hasNext()) {
                        // Preserve all old candidates and their publication
                        // order before spending the same remaining allowance
                        // on entries hidden inside a contracted linear chain.
                        // Every completion starts by spending this root, so
                        // it cannot lower an initial requirement already at one.
                        if (completing == null && !cycling.hasRoundTrip() && result.missingExact().containsKey(root) &&
                                base.initialExact().getOrDefault(root, BigInteger.ZERO).compareTo(BigInteger.ONE) > 0) {
                            completing = new RefillCycleCompletion<>(recipes, cycling.components(), visited, root, budget);
                            phase = Phase.COMPLETION; return false;
                        }
                        endRoot(); break;
                    }
                    optionAction = optionActions.next();
                    var summary = optionAction.summary(); BigInteger gain = summary.delta(root);
                    BigInteger gap = base.initialExact().getOrDefault(root, BigInteger.ZERO)
                            .subtract(summary.required(root).max(available(root)));
                    if (gain.signum() <= 0 || gap.signum() <= 0 || force && summary.delta(base.target()).signum() < 0) {
                        optionAction = null; break;
                    }
                    optionRuns = CheckedAmounts.ceilDiv(gap, gain); optionTrial = 0; optionFundedRuns = null;
                    if (completing != null) {
                        // This word was not present during the earlier sizing
                        // pass. Reuse its exact stock breakpoint without new
                        // route enumeration or a larger offer allowance.
                        sizing = new RefillRepeatSizing<>(summary, base, stock, external, force, ignoreTargetStock, budget);
                        phase = Phase.OPTION_SIZE; return false;
                    }
                }
                // Keep two fixed-word endpoints. No price is assigned to
                // extra feedstocks in exchange for fewer cyclic startup units.
                // New completion words may add their funded interior point.
                BigInteger runs = switch (optionTrial++) {
                    case 0 -> BigInteger.ONE;
                    case 1 -> optionRuns;
                    default -> optionFundedRuns;
                };
                prefix = PlanStep.repeat(optionAction.program(), runs);
                if (optionTrial == 1 && optionRuns.equals(BigInteger.ONE)) optionTrial = 2;
                if (optionTrial == 3 || optionTrial == 2 && optionFundedRuns == null) optionAction = null;
                offered++; summarizing = new SummaryComputation<>(prefix, recipes, budget); phase = Phase.SUMMARY;
            }
            case PREFIXES -> {
                // Only inspect prefixes accepted by THIS search. The original
                // suffix can be huge or contain per-push configurations; it is
                // not a source of repeat hints. The wrapper is created below
                // only after a complete funded-word verification.
                if (retainedWord == original.steps() || !(retainedWord instanceof PlanStep.Sequence sequence) || sequence.children().size() != 2) return stop();
                PlanStep next = sequence.children().get(0); retainedWord = sequence.children().get(1);
                if (seenWords.add(next)) { reserve(128); words.push(new WordFrame(next)); }
                phase = Phase.WORDS;
            }
            case WORDS -> {
                if (++wordVisits > 256 || sizedWords == 16) return stop();
                if (words.isEmpty()) { phase = Phase.PREFIXES; break; }
                WordFrame frame = words.peek(); PlanStep child = null;
                if (frame.step instanceof PlanStep.Sequence sequence && frame.child < sequence.children().size())
                    child = sequence.children().get(frame.child++);
                else if (frame.step instanceof PlanStep.Repeat repeat && repeat.times() > 0 && frame.child++ == 0) child = repeat.body();
                if (child != null) {
                    if (seenWords.add(child)) { reserve(128); words.push(new WordFrame(child)); }
                } else {
                    words.pop();
                    // Postorder exposes a compact loop body before the larger
                    // prefix which contains many copies of that same body.
                    if (frame.step instanceof PlanStep.Sequence sequence && sequence.children().size() > 1) {
                        reserve(512); sizedWords++; base = result; recipes = base.recipes(); prefix = frame.step;
                        summarizing = new SummaryComputation<>(prefix, recipes, budget); phase = Phase.SIZE_SUMMARY;
                    }
                }
            }
            case ROOT -> {
                if (!roots.hasNext()) return finish();
                root = roots.next(); var node = order.get(root);
                missing = result.missingExact().getOrDefault(root, BigInteger.ZERO);
                if (missing.signum() == 0 || node == null || !node.cycle) break;
                reserve(768); base = trading ? frontier.primary() : result; rank = node.rank; trial = incidences = 0;
                recipes = new LinkedHashMap<>(); pending = new ArrayDeque<>(); visited = new HashSet<>();
                domain = new LinkedHashSet<>(); tried = new HashSet<>(); sources = null;
                pending.add(node); visited.add(root); phase = Phase.DISCOVER;
            }
            case DISCOVER -> {
                if (sources != null && sources.hasNext()) {
                    recipe = sources.next().recipe;
                    if (recipes.containsKey(recipe.id())) break;
                    var bound = base.recipes().get(recipe.id());
                    if (bound != null && !bound.equals(recipe)) break;
                    // Keep this optional neighborhood small before constructing
                    // the existing exact antichain. Its constructor is not a
                    // resumable catalog compiler, so cap ports as well as keys.
                    if (recipe.batchSensitiveInputs() || recipes.size() >= 12 ||
                            (long) incidences + recipe.inputs().size() + recipe.outputs().size() > 128) { endRoot(); break; }
                    reserve(192L + 128L * (recipe.inputs().size() + recipe.outputs().size()));
                    incidences += recipe.inputs().size() + recipe.outputs().size(); recipes.put(recipe.id(), recipe);
                    ports = recipe.inputs().keySet().iterator(); phase = Phase.INPUTS;
                } else if (!pending.isEmpty()) sources = pending.removeFirst().sources.iterator();
                else {
                    sources = null; recipe = null;
                    if (recipes.isEmpty() || visited.size() < 2) endRoot();
                    else if (accelerated) {
                        macros = new ArrayList<>();
                        compiling = new LinearMacroCompilation<>(recipes, base.target(), Set.of(), budget);
                        phase = Phase.COMPILE;
                    } else { shortcutsRelevant = true; macros = List.of(); phase = Phase.TRIAL; }
                }
            }
            case INPUTS, OUTPUTS -> {
                if (ports.hasNext()) {
                    K key = ports.next();
                    if (domain.add(key) && domain.size() > 16) { endRoot(); break; }
                    if (phase == Phase.INPUTS) {
                        var node = order.get(key);
                        if (node != null && node.cycle && node.rank == rank && visited.add(key)) pending.addLast(node);
                    }
                } else if (phase == Phase.INPUTS) { ports = recipe.outputs().keySet().iterator(); phase = Phase.OUTPUTS; }
                else { ports = null; phase = Phase.DISCOVER; }
            }
            case MACROS -> {
                if (compiledRecipes.hasNext()) {
                    var compiled = compiledRecipes.next();
                    if (recipes.containsKey(compiled.id())) break;
                    // Preserve the actual chain word and all intermediate keys.
                    // The contracted endpoint recipe alone is not an exact
                    // summary when an intermediate is also a suffix obligation.
                    reserve(512L * (domain.size() + recipes.size()));
                    macro = compiling.expand(new PlanStep.Batch(compiled.id(), 1));
                    summarizing = new SummaryComputation<>(macro, recipes, budget); phase = Phase.MACRO_SUMMARY;
                } else {
                    compiledRecipes = null; compiling.close(); compiling = null;
                    cycling = new RefillCycleActions<>(recipes, macros, root, budget); phase = Phase.CYCLES;
                }
            }
            case TRIAL -> {
                if (trial == 4) { endRoot(); break; }
                trialAmount = switch (trial++) {
                    case 0 -> BigInteger.ZERO;
                    case 1 -> BigInteger.ONE;
                    case 2 -> missing.shiftRight(1);
                    default -> missing.subtract(BigInteger.ONE);
                };
                if (trialAmount.compareTo(missing) >= 0 || !tried.add(trialAmount)) break;
                // Includes the bounded child's immutable action summaries,
                // key/position vectors and our supply/goal/external maps.
                reserve(1024L + 384L * (domain.size() + incidences));
                supplied = new LinkedHashMap<>(); goals = new LinkedHashMap<>(); trialExternal = new HashSet<>();
                ports = domain.iterator(); phase = Phase.SUPPLY;
            }
            case SUPPLY -> {
                if (ports.hasNext()) {
                    K key = ports.next();
                    BigInteger have = available(key).add(key.equals(root) ? trialAmount : base.missingExact().getOrDefault(key, BigInteger.ZERO));
                    BigInteger need = base.initialExact().getOrDefault(key, BigInteger.ZERO);
                    if (force && key.equals(base.target())) need = need.max(have);
                    supplied.put(key, have); goals.put(key, need);
                    if (external.contains(key) && !(force && key.equals(base.target()))) trialExternal.add(key);
                } else {
                    ports = null;
                    searching = new BackwardCoverability<>(List.copyOf(recipes.values()), supplied, goals, trialExternal,
                            macros, budget, Math.min(2048, Math.max(0, allowance - (budget.nodes() - passStarted))), true);
                    phase = Phase.SEARCH;
                }
            }
            case COPY -> {
                if (entries.hasNext()) { var e = entries.next(); reserve(128); initial.put(e.getKey(), e.getValue()); }
                else { entries = summary.delta().entrySet().iterator(); phase = Phase.NEEDS; }
            }
            case NEEDS -> {
                if (entries.hasNext()) {
                    var e = entries.next(); K key = e.getKey();
                    BigInteger need = summary.required(key).max(base.initialExact().getOrDefault(key, BigInteger.ZERO).subtract(e.getValue()));
                    if (need.signum() == 0) initial.remove(key);
                    else { if (!initial.containsKey(key)) reserve(128); initial.put(key, need); }
                } else { entries = null; bindingEntries = base.recipes().entrySet().iterator(); phase = Phase.BINDINGS; }
            }
            case BINDINGS -> {
                if (bindingEntries.hasNext()) { var e = bindingEntries.next(); reserve(96); bindings.put(e.getKey(), e.getValue()); }
                else {
                    // The discovered binding map has at most12 entries.
                    // Merge it through the same cursor, without copying a
                    // possibly large original suffix's physical bindings twice.
                    if (!mergingBindings && recipes != base.recipes()) {
                        mergingBindings = true; bindingEntries = recipes.entrySet().iterator();
                    } else {
                        bindingEntries = null; entries = initial.entrySet().iterator(); strict = false; phase = Phase.MISSING;
                    }
                }
            }
            case MISSING -> {
                if (entries.hasNext()) {
                    var e = entries.next();
                    BigInteger gap = external.contains(e.getKey()) ? BigInteger.ZERO : e.getValue().subtract(available(e.getKey())).max(BigInteger.ZERO);
                    int comparison = gap.compareTo(base.missingExact().getOrDefault(e.getKey(), BigInteger.ZERO));
                    if (comparison > 0 && !trading) { rejectCandidate(); break; }
                    strict |= comparison < 0;
                    if (gap.signum() > 0) { reserve(128); deficits.put(e.getKey(), gap); }
                } else {
                    entries = null; strict |= deficits.size() < base.missingExact().size();
                    if (trading) strict |= deficits.getOrDefault(root, BigInteger.ZERO)
                            .compareTo(base.missingExact().getOrDefault(root, BigInteger.ZERO)) < 0;
                    if (!strict) { rejectCandidate(); break; }
                    candidate = GraphPlan.ownedRefill(base, new PlanStep.Sequence(List.of(prefix, base.steps())), bindings, initial, deficits,
                            deficits.isEmpty() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : base.result(), budget.nodes());
                    verifying = PlanVerification.refill(GraphPlan.ownedRefill(candidate, candidate.steps(), candidate.recipes(), candidate.initialExact(), Map.of(),
                            GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, budget.nodes()), budget);
                    phase = Phase.VERIFY;
                }
            }
            default -> throw new IllegalStateException("Unexpected refill supply phase " + phase);
        }
        return false;
    }

    private BigInteger available(K key) { return ignoreTargetStock && original.target().equals(key) ? BigInteger.ZERO : PlanningInventory.exactAmount(stock, key); }
    private void rejectCandidate() { if (resizing || trading) endTrial(); else endRoot(); }
    private void endTrial() {
        if (searching != null) { searching.close(); searching = null; }
        if (summarizing != null) { summarizing.close(); summarizing = null; }
        if (verifying != null) { verifying.close(); verifying = null; }
        if (startup != null) { startup.close(); startup = null; }
        if (refining != null) { refining.close(); refining = null; }
        summary = null; prefix = null; candidate = null; entries = null; bindingEntries = null; ports = null;
        sizing = null; phase = trading ? Phase.OPTIONS : resizing ? Phase.WORDS : Phase.TRIAL;
    }
    private void endRoot() {
        endTrial(); sources = null; recipe = null; pending = null; visited = null; domain = null;
        if (compiling != null) { compiling.close(); compiling = null; }
        if (completing != null) { completing.close(); completing = null; }
        if (cycling != null) { cycling.close(); cycling = null; }
        macros = null; compiledRecipes = null; macro = null;
        optionActions = null; optionAction = null; optionRuns = optionFundedRuns = null;
        recipes = null; bindings = null; initial = null; deficits = null; supplied = null; goals = null; trialExternal = null; tried = null;
        phase = Phase.ROOT;
    }
    private boolean finish() {
        if (!trading && !accelerated && !resizing && shortcutsRelevant && !result.missingExact().isEmpty() && budget.remainingWork() >= 4096) {
            // Keep the old bounded search's completed incumbent. A faster
            // shortcut can otherwise choose an incomparable bill early and
            // prevent a later root from retaining its previous improvement.
            endRoot(); accelerated = true; roots = result.missingExact().keySet().iterator();
            passStarted = budget.nodes(); allowance = Math.min(8192, budget.remainingWork() / 4);
            return false;
        }
        if (!trading && !resizing && result != original && !result.missingExact().isEmpty() && budget.remainingWork() >= 1024) {
            // Completed primitive and shortcut searches keep their existing
            // quotas and incumbent. Exact sizing adds no route enumeration.
            endRoot(); resizing = true; retainedWord = result.steps();
            reserve(512); words = new ArrayDeque<>(); seenWords = Collections.newSetFromMap(new IdentityHashMap<>());
            passStarted = budget.nodes(); allowance = Math.min(4096, budget.remainingWork() / 4);
            phase = Phase.PREFIXES; return false;
        }
        return stop();
    }
    private boolean stop() {
        if (!trading && tradeoffCompiler != null && shortcutsRelevant && !result.missingExact().isEmpty() &&
                result.alternatives().isEmpty() && budget.remainingWork() >= 4096) {
            // Previous primitive, macro and quantity passes keep their entire
            // quota and completed primary. Extra bills are refined and verified
            // separately; an incomparable one can only enter the small archive.
            endRoot(); trading = true; accelerated = true; resizing = false;
            reserve(512); frontier = new RefillFrontier<>(result, budget);
            roots = result.missingExact().keySet().iterator();
            passStarted = budget.nodes(); allowance = Math.min(8192, budget.remainingWork() / 8);
            phase = Phase.ROOT; return false;
        }
        return complete();
    }
    private boolean complete() {
        phase = Phase.DONE;
        budget.note("refill_supply", (result == original ? "retained_original" : "improved") + "; work=" + (budget.nodes() - started));
        return true;
    }
    GraphPlan<K> result() { return result; }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    @Override public void close() {
        endRoot(); phase = Phase.DONE;
        words = null; seenWords = null; retainedWord = null;
        budget.release(memory); memory = 0;
    }
}
