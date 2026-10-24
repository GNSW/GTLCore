// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Goal-aware reduction of a completed refill word, within its original supply envelope.
 * Walking right to left leaves the original prefix fixed. At each boundary its
 * inventory C funds the already shortened suffix. For a unit with net delta d,
 * each positive d imposes n >= ceil((suffixNeed - C) / d). Reducing the old count
 * to their maximum cannot worsen a nonpositive-d resource's prefix requirement.
 * Exact backward requirements then replace that suffix's need. This induction
 * preserves executability without requiring all unrequested final coproducts.
 */
final class RefillWordPruning<K> implements AutoCloseable {
    private enum Phase { RECIPES, STRUCTURE, CONSUMED, PRODUCED, ORIGINAL, INITIAL, FINAL, GOALS, NEXT, UNWRAP, SUMMARY, SUPPORT, APPLY, EMIT, STEPS, DEFICITS, VERIFY, BLOCKS, ANCHORS, SHAPES, NORMAL_FORMS, SPANS, BLOCK_PRUNE, PERIODS, PERIOD_PRUNE, DONE }
    private final GraphPlan<K> original;
    private final Map<K, Long> stock;
    private final Set<K> external;
    private final boolean forceCraft, ignoreTargetStock;
    private final PlanningBudget budget;
    private Collection<GraphRecipe<K>> startupRecipes;
    private Set<String> startupExcluded = Set.of();
    private final long started, allowance;
    private final String retainedFailure;
    private final Map<K, BigInteger> inventory = new LinkedHashMap<>(), required = new LinkedHashMap<>(), missing = new LinkedHashMap<>();
    private final List<PlanStep> reversed = new ArrayList<>(), steps = new ArrayList<>();
    private final Deque<Cursor> stack = new ArrayDeque<>();
    private Iterator<GraphRecipe<K>> recipes;
    private Iterator<Map.Entry<K, BigInteger>> entries;
    private Iterator<Map.Entry<K, Long>> seeds;
    private Deque<Iterator<PlanStep>> structure;
    private Set<K> consumed;
    private Iterator<K> ports;
    private GraphRecipe<K> inspecting;
    private GraphRecipe<K> unitRecipe;
    private boolean unitOutputs;
    private PlanVerification<K> verification;
    private SeedStartupGuard<K> startup;
    private SummaryComputation<K> computation;
    private RefillWordBlocks blocks;
    private RefillShortBlockHint shortHint;
    private RefillWordAnchors anchors;
    private RefillWordShapes shapes;
    private RefillWordNormalForms normalForms;
    private RefillWordSpans spans;
    private List<PlanStep> groupedAtoms;
    private RefillWordPruning<K> blockPruning;
    private RefillUnitPeriods periods;
    private RefillWordPruning<K> periodPruning;
    private GraphPlan<K> factored;
    private PlanStep roundedUnit;
    private boolean trackRounding, unitRounded;
    private SequenceSummary<K> summary, unitSummary;
    private PlanStep lastUnit;
    private SequenceSummary<K> lastUnitSummary;
    private boolean reuseUnit;
    private long lastUnitBytes;
    private final SequenceSummary<K> verifiedOriginal;
    private GraphPlan<K> candidate, result;
    private GraphPlan<K> regrouped;
    private PlanStep atom, unit;
    private BigInteger oldRuns, runs, targetCredit = BigInteger.ZERO, tailTarget = BigInteger.ZERO;
    private boolean feedback, changed, allocated, compactCursors, hintTried, shortBlocksImpossible;
    private boolean allowBlocks = true, inheritedStructure;
    private Phase phase = Phase.RECIPES;
    private int reverseIndex, flatBatches, opaqueAtoms;
    private boolean shapesTried, normalTried, spansTried;
    private long memory, summaryBytes, unitBytes, structureBytes, structureStarted;

    RefillWordPruning(GraphPlan<K> original, Map<K, Long> stock, Set<K> external, boolean forceCraft,
                      boolean ignoreTargetStock, PlanningBudget budget, long allowance) {
        this(original, stock, external, forceCraft, ignoreTargetStock, budget, allowance, null);
    }

    /** A completed verifier of this exact word/binding/initial tuple owns and
     * charges a borrowed summary until this pass closes. No cross-request reuse. */
    RefillWordPruning(GraphPlan<K> original, Map<K, Long> stock, Set<K> external, boolean forceCraft,
                      boolean ignoreTargetStock, PlanningBudget budget, long allowance, SequenceSummary<K> verified) {
        this.original = original; this.result = original; this.stock = stock; this.external = external;
        this.forceCraft = forceCraft; this.ignoreTargetStock = ignoreTargetStock; this.budget = budget;
        this.allowance = allowance; started = budget.nodes(); retainedFailure = budget.failureDetail();
        // The saturated quality pass has already been granted its full quota;
        // it need not spend 2048 units before fusing empty cursor handoffs.
        // Smaller work-scaled passes retain their previous admission schedule.
        compactCursors = allowance >= 8192;
        summary = verified; verifiedOriginal = verified;
        recipes = original.recipes().values().iterator();
        startupRecipes = original.recipes().values();
    }

    RefillWordPruning<K> startupCatalog(Collection<GraphRecipe<K>> available, Set<String> excluded) {
        if (allocated || phase != Phase.RECIPES) throw new IllegalStateException("Refill pruning already started");
        startupRecipes = available; startupExcluded = excluded;
        return this;
    }

    boolean step() {
        try {
            budget.checkpoint();
            if (phase == Phase.DONE) return true;
            long spent = budget.nodes() - started;
            if (spent >= allowance) return finish();
            // Small work-scaled refinements retain their previous scheduling.
            // Once quality quotas are saturated, combine empty cursor handoffs;
            // every hierarchy/atom and material-key visit is still charged.
            compactCursors |= spent >= 2048;
            if (!allocated) { reserve(256); allocated = true; }
            // Child folds/verifiers each own their slice; never append them to
            // a full cursor slice. The optional cap can overshoot by one slice.
            if (phase == Phase.ORIGINAL) {
                if (!verification.step()) return false;
                summary = verification.summary(); verification.close(); verification = null;
                summaryBytes = retain(summary);
                return beginOriginal();
            }
            if (phase == Phase.SUMMARY) {
                if (!computation.step()) return false;
                unitSummary = computation.result(); computation.close(); computation = null;
                unitBytes = retain(unitSummary);
                if (reuseUnit) {
                    lastUnit = unit; lastUnitSummary = unitSummary;
                    lastUnitBytes = unitBytes; unitBytes = 0;
                }
                startUnit(); return false;
            }
            if (phase == Phase.VERIFY) {
                if (startup == null && !verification.step()) return false;
                if (forceCraft && verification.summary().delta(original.target()).compareTo(targetCredit) < 0) return finish();
                if (candidate.preserveSeeds()) {
                    if (startup == null) startup = new SeedStartupGuard<>(startupRecipes, startupExcluded, verification.summary(),
                            stock, candidate.seeds(), external, candidate.target(), candidate.amount(), budget);
                    if (!startup.step()) return false;
                    boolean lost = !startup.lost().isEmpty();
                    startup.close(); startup = null;
                    // A redundant delivery coproduct can still be the last
                    // restart token. Keep the verified original word when a
                    // count reduction would erase that startup capability.
                    if (lost) return finish();
                }
                return complete(true);
            }
            if (phase == Phase.BLOCKS) {
                if (!blocks.step()) return false;
                if (blocks.result() != null) return pruneBlocks(blocks.result());
                // Keep the original short-period pass and every completed bill
                // first. An unsuccessful scan may spend leftover quota on
                // anchor hints; an unproductive short grouping can continue
                // at a larger period too, without a fresh allowance.
                blocks.close(); blocks = null;
                // Exact normalization is a new continuation. A failed short
                // tandem scan can expose separate spans directly, including
                // longer adjacent spans, without repeating the anchor scan.
                return normalTried ? finishGrouping() : startAnchors();
            }
            if (phase == Phase.ANCHORS) {
                if (!anchors.step()) return false;
                return anchors.result() == null ? finishGrouping() : pruneBlocks(anchors.result());
            }
            if (phase == Phase.SHAPES) {
                if (!shapes.step()) return false;
                if (shapes.result() == null || !shapes.changed() && flatBatches >= 4) return finishGrouping();
                groupedAtoms = shapes.result();
                long left = allowance - (budget.nodes() - started);
                if (left < 512) return finish();
                blocks = new RefillWordBlocks(groupedAtoms, budget, Math.min(2048, left / 2), compactCursors);
                phase = Phase.BLOCKS; return false;
            }
            if (phase == Phase.NORMAL_FORMS) {
                if (!normalForms.step()) return false;
                if (normalForms.result() == null || !normalForms.changed()) return finishGrouping();
                groupedAtoms = normalForms.result();
                long left = allowance - (budget.nodes() - started);
                if (left < 512) return finish();
                blocks = new RefillWordBlocks(groupedAtoms, budget, Math.min(2048, left / 2), compactCursors);
                phase = Phase.BLOCKS; return false;
            }
            if (phase == Phase.SPANS) {
                if (!spans.step()) return false;
                return spans.result() == null ? finish() : pruneBlocks(spans.result());
            }
            if (phase == Phase.BLOCK_PRUNE) {
                if (!blockPruning.step()) return false;
                if (blockPruning.result() != regrouped) {
                    result = blockPruning.result();
                    long left = allowance - (budget.nodes() - started);
                    if (blockPruning.roundedUnit == null || left < 1024) return finish();
                    periods = new RefillUnitPeriods(blockPruning.reversed, blockPruning.roundedUnit, budget, Math.min(2048, left / 2));
                    phase = Phase.PERIODS; return false;
                }
                else if (blocks != null || anchors != null && anchors.hasShortBlocks()) {
                    // A short tandem can be a necessary inner loop rather than
                    // a reducible outer round. Recognition alone is not quality
                    // progress. With the incumbent unchanged, the original
                    // reverse word and its verified binding are still exact.
                    // Release this failed attempt before using leftover quota.
                    blockPruning.close(); blockPruning = null; regrouped = null;
                    if (blocks != null) { blocks.close(); blocks = null; }
                    if (anchors != null) { anchors.close(); anchors = null; }
                    return normalTried ? finishGrouping() : startAnchors(true);
                }
                return finishGrouping();
            }
            if (phase == Phase.PERIODS) {
                if (!periods.step()) return false;
                if (periods.result() == null) return finish();
                // The accepted child and its verifier remain owned throughout.
                // Factoring proves the same complete dispatch trace, so its
                // checked fold is valid under this exact supply/binding tuple.
                factored = GraphPlan.ownedRefill(result, periods.result(), result.recipes(), result.initialExact(), result.missingExact(), result.result(), budget.nodes());
                periodPruning = new RefillWordPruning<>(factored, stock, external, forceCraft, ignoreTargetStock, budget,
                        allowance - (budget.nodes() - started), blockPruning.verification.summary())
                        .startupCatalog(startupRecipes, startupExcluded);
                periodPruning.allowBlocks = false; periodPruning.inheritedStructure = true;
                periodPruning.compactCursors = true; periodPruning.reuseUnit = true;
                phase = Phase.PERIOD_PRUNE; return false;
            }
            if (phase == Phase.PERIOD_PRUNE) {
                if (!periodPruning.step()) return false;
                if (periodPruning.result() != factored) result = periodPruning.result();
                return finish();
            }
            for (int i = 0; i < 64; i++) {
                budget.check();
                if (advance()) return true;
                if (phase == Phase.ORIGINAL || phase == Phase.SUMMARY || phase == Phase.VERIFY || phase == Phase.BLOCKS || phase == Phase.ANCHORS || phase == Phase.SHAPES || phase == Phase.NORMAL_FORMS || phase == Phase.SPANS) return false;
            }
            return false;
        } catch (PlanningBudget.Exhausted limit) {
            // A verified bill is already available. Optional quality work must
            // not turn it into a limit result; cancellation still propagates.
            budget.failureDetail(retainedFailure); return finish();
        }
    }

    private boolean advance() {
        switch (phase) {
            case RECIPES -> {
                if (inheritedStructure) {
                    recipes = null;
                    return startOriginal();
                }
                if (recipes.hasNext()) {
                    var recipe = recipes.next();
                    if (recipe.batchSensitiveInputs()) return finish();
                    feedback |= recipe.hasInputReturns();
                } else {
                    recipes = null;
                    if (feedback) return startOriginal();
                    // A returned startup resource can cross several recipes.
                    // Inspect the actual word, not arbitrary catalog ordering.
                    // Like the flat flow pass, a later output of an already
                    // consumed key is a hint; the exact verifier remains final.
                    structure = new ArrayDeque<>(); consumed = new HashSet<>();
                    structureStarted = budget.nodes(); pushStructure(List.of(original.steps())); phase = Phase.STRUCTURE;
                }
            }
            case STRUCTURE, CONSUMED, PRODUCED -> {
                if (budget.nodes() - structureStarted > Math.min(1024, Math.max(128, allowance / 4))) return finishStructure(false);
                if (phase == Phase.CONSUMED) {
                    if (ports.hasNext()) {
                        K key = ports.next();
                        if (!consumed.contains(key)) { reserveStructure(96); consumed.add(key); }
                    } else { ports = inspecting.outputs().keySet().iterator(); phase = Phase.PRODUCED; }
                } else if (phase == Phase.PRODUCED) {
                    if (ports.hasNext()) {
                        if (consumed.contains(ports.next())) return finishStructure(true);
                    } else { ports = null; inspecting = null; phase = Phase.STRUCTURE; }
                } else if (structure.isEmpty()) return finishStructure(false);
                else if (!structure.peek().hasNext()) { structure.pop(); release(64); structureBytes -= 64; }
                else {
                    PlanStep step = structure.peek().next();
                    if (step instanceof PlanStep.Sequence sequence) pushStructure(sequence.children());
                    else if (step instanceof PlanStep.Repeat repeat) {
                        // One structural body visit regardless of its count.
                        // Never enumerate execution occurrences of a huge loop.
                        if (repeat.times() > 0) pushStructure(List.of(repeat.body()));
                    } else if (((PlanStep.Batch) step).runs() > 0) {
                        inspecting = original.recipes().get(((PlanStep.Batch) step).recipe());
                        ports = inspecting.inputs().keySet().iterator(); phase = Phase.CONSUMED;
                    }
                }
            }
            case INITIAL -> {
                if (entries.hasNext()) { var e = entries.next(); put(inventory, e.getKey(), e.getValue()); }
                else { entries = summary.delta().entrySet().iterator(); phase = Phase.FINAL; }
            }
            case FINAL -> {
                if (entries.hasNext()) {
                    var e = entries.next(); put(inventory, e.getKey(), value(inventory, e.getKey()).add(e.getValue()));
                } else {
                    summary = null; release(summaryBytes); summaryBytes = 0;
                    entries = null; seeds = original.seeds().entrySet().iterator(); phase = Phase.GOALS;
                    put(required, original.target(), BigInteger.valueOf(original.amount()));
                }
            }
            case GOALS -> {
                if (seeds.hasNext()) {
                    var e = seeds.next(); put(required, e.getKey(), value(required, e.getKey()).add(BigInteger.valueOf(e.getValue())));
                } else { seeds = null; push(List.of(original.steps())); phase = Phase.NEXT; }
            }
            case NEXT -> {
                if (stack.isEmpty()) {
                    // Only a completed stream can certify absence. Release the
                    // optional index before constructing/verifying a candidate.
                    shortBlocksImpossible = shortHint != null && !shortHint.possible();
                    discardHint();
                    if (!changed) return complete(false);
                    reverseIndex = reversed.size(); phase = Phase.STEPS;
                } else if (stack.peek().index == 0) { stack.pop(); release(64); }
                else {
                    atom = stack.peek().previous();
                    // Reading the final child also closes this cursor. This
                    // does not skip any hierarchy or recipe visit; it only
                    // avoids a separate charged visit to an exhausted cursor.
                    if (compactCursors && stack.peek().index == 0) { stack.pop(); release(64); }
                    if (atom instanceof PlanStep.Sequence sequence) push(sequence.children());
                    else if (compactCursors && atom instanceof PlanStep.Batch batch) {
                        if (batch.runs() == 0) break;
                        oldRuns = BigInteger.valueOf(batch.runs());
                        unit = batch.runs() == 1 ? atom : new PlanStep.Batch(batch.recipe(), 1);
                        unitRecipe = original.recipes().get(batch.recipe()); startUnit();
                    }
                    else { unit = atom; oldRuns = BigInteger.ONE; phase = Phase.UNWRAP; }
                }
            }
            case UNWRAP -> {
                if (oldRuns.signum() == 0) { phase = Phase.NEXT; break; }
                if (unit instanceof PlanStep.Repeat repeat) { oldRuns = oldRuns.multiply(BigInteger.valueOf(repeat.times())); unit = repeat.body(); }
                else if (unit instanceof PlanStep.Sequence sequence && sequence.children().size() == 1) unit = sequence.children().get(0);
                else {
                    if (unit instanceof PlanStep.Batch batch) {
                        oldRuns = oldRuns.multiply(BigInteger.valueOf(batch.runs())); unit = new PlanStep.Batch(batch.recipe(), 1);
                    }
                    if (oldRuns.signum() == 0) { phase = Phase.NEXT; break; }
                    if (unit instanceof PlanStep.Batch batch) {
                        // A one-firing recipe already owns the exact immutable
                        // ports. Stream them instead of rebuilding three summary
                        // maps for every occurrence; no retained cache is needed.
                        unitRecipe = original.recipes().get(batch.recipe());
                        startUnit();
                    } else {
                        // Composite repeat bodies stay opaque: never unroll or
                        // reorder their internal execution into independent batches.
                        // A child fold has priority over the optional index.
                        discardHint();
                        if (reuseUnit && unit == lastUnit) {
                            unitSummary = lastUnitSummary; startUnit();
                        } else {
                            release(lastUnitBytes); lastUnitBytes = 0; lastUnit = null; lastUnitSummary = null;
                            computation = new SummaryComputation<>(unit, original.recipes(), budget); phase = Phase.SUMMARY;
                        }
                    }
                }
            }
            case SUPPORT -> {
                if (!visitUnit(false)) {
                    if (runs.compareTo(oldRuns) > 0) return finish();
                    unitKeys(); phase = Phase.APPLY;
                    if (compactCursors && (runs.signum() == 0 || !visitUnit(true))) emit();
                }
            }
            case APPLY -> {
                if (runs.signum() == 0 || !visitUnit(true)) {
                    if (compactCursors) emit();
                    else { entries = null; ports = null; phase = Phase.EMIT; }
                } else if (compactCursors && unitKeysFinished()) emit();
            }
            case EMIT -> emit();
            case STEPS -> {
                if (reverseIndex > 0) { reserve(16); steps.add(reversed.get(--reverseIndex)); }
                else { entries = required.entrySet().iterator(); phase = Phase.DEFICITS; }
            }
            case DEFICITS -> {
                if (entries.hasNext()) {
                    var e = entries.next(); K key = e.getKey(); BigInteger need = e.getValue();
                    if (need.compareTo(original.initialExact().getOrDefault(key, BigInteger.ZERO)) > 0) return finish();
                    BigInteger have = ignoreTargetStock && key.equals(original.target()) ? BigInteger.ZERO : PlanningInventory.exactAmount(stock, key);
                    BigInteger deficit = external.contains(key) ? BigInteger.ZERO : need.subtract(have).max(BigInteger.ZERO);
                    if (deficit.compareTo(original.missingExact().getOrDefault(key, BigInteger.ZERO)) > 0) return finish();
                    if (deficit.signum() > 0) put(missing, key, deficit);
                } else {
                    entries = null;
                    candidate = GraphPlan.ownedRefill(original, FrozenPlanSteps.owned(steps), original.recipes(), required, missing,
                            missing.isEmpty() ? GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL : original.result(), budget.nodes());
                    verification = verify(candidate); phase = Phase.VERIFY;
                }
            }
            default -> throw new IllegalStateException("Unexpected refill pruning phase " + phase);
        }
        return false;
    }

    private void startUnit() { unitKeys(); runs = BigInteger.ZERO; unitRounded = false; phase = Phase.SUPPORT; }
    private void emit() {
        changed |= !runs.equals(oldRuns);
        var delta = unitDelta(original.target());
        if (delta.signum() != 0) tailTarget = tailTarget.add(delta.multiply(runs));
        if (runs.signum() > 0) {
            reserve(64);
            var kept = runs.equals(oldRuns) ? atom : PlanStep.repeat(unit, runs);
            reversed.add(kept);
            if (unitRounded && roundedUnit == null) roundedUnit = kept;
            if (atom instanceof PlanStep.Batch) flatBatches++;
            else opaqueAtoms++;
            if (!hintTried) {
                hintTried = true;
                if (allowBlocks && allowance >= 4096 && original.recipes().size() > 16) shortHint = RefillShortBlockHint.tryCreate(budget);
            }
            if (shortHint != null) shortHint.offer(kept);
        }
        entries = null; ports = null;
        atom = null; unit = null; unitSummary = null; unitRecipe = null; phase = Phase.NEXT;
        release(unitBytes); unitBytes = 0;
    }
    private void unitKeys() {
        if (unitRecipe == null) entries = unitSummary.delta().entrySet().iterator();
        else { ports = unitRecipe.inputs().keySet().iterator(); unitOutputs = false; }
    }
    private boolean unitKeysFinished() {
        if (unitRecipe == null) return !entries.hasNext();
        if (!ports.hasNext() && !unitOutputs) {
            ports = unitRecipe.outputs().keySet().iterator(); unitOutputs = true;
        }
        return unitOutputs && !ports.hasNext();
    }
    private BigInteger unitDelta(K key) {
        // Both ports are nonnegative longs, so their difference fits in long.
        return unitRecipe == null ? unitSummary.delta(key) : BigInteger.valueOf(
                unitRecipe.outputs().getOrDefault(key, 0L) - unitRecipe.inputs().getOrDefault(key, 0L));
    }
    /** One port per charged cursor visit, including duplicate returned outputs. */
    private boolean visitUnit(boolean applying) {
        K key; BigInteger delta;
        if (unitRecipe == null) {
            if (!entries.hasNext()) return false;
            var e = entries.next(); key = e.getKey(); delta = e.getValue();
        } else {
            if (!ports.hasNext()) {
                if (unitOutputs) return false;
                ports = unitRecipe.outputs().keySet().iterator(); unitOutputs = true;
                if (!compactCursors) return true;
                if (!ports.hasNext()) return false;
            }
            key = ports.next();
            if (unitOutputs && unitRecipe.inputs().containsKey(key)) return true;
            delta = unitDelta(key);
        }
        if (applying) {
            BigInteger need = unitRecipe == null ? unitSummary.required(key) : BigInteger.valueOf(unitRecipe.inputs().getOrDefault(key, 0L));
            BigInteger first = need.add(delta.negate().max(BigInteger.ZERO).multiply(runs.subtract(BigInteger.ONE)));
            put(required, key, first.max(value(required, key).subtract(delta.multiply(runs))));
        } else {
            BigInteger before = value(inventory, key).subtract(delta.multiply(oldRuns));
            put(inventory, key, before);
            if (delta.signum() > 0) {
                supportRuns(value(required, key).subtract(before), delta);
                if (forceCraft && key.equals(original.target())) {
                    BigInteger prefix = before.subtract(original.initialExact().getOrDefault(key, BigInteger.ZERO));
                    supportRuns(targetCredit.subtract(prefix).subtract(tailTarget), delta);
                }
            }
        }
        return true;
    }

    private void supportRuns(BigInteger gap, BigInteger gain) {
        var bound = ceil(gap, gain);
        if (trackRounding && unitRecipe == null) {
            int comparison = bound.compareTo(runs);
            if (comparison >= 0) {
                boolean rounded = gap.signum() > 0 && gain.multiply(bound).compareTo(gap) > 0;
                unitRounded = comparison > 0 ? rounded : unitRounded && rounded;
            }
        }
        runs = runs.max(bound);
    }

    private boolean startOriginal() {
        if (summary != null) return beginOriginal();
        verification = verify(original); phase = Phase.ORIGINAL; return false;
    }
    private void reserveStructure(long bytes) { reserve(bytes); structureBytes += bytes; }
    private void pushStructure(List<PlanStep> children) { reserveStructure(64); structure.push(children.iterator()); }
    private boolean finishStructure(boolean found) {
        // Drop private scratch without an unbounded clear loop. No published
        // plan or borrowed summary can reference these traversal containers.
        structure = null; consumed = null; ports = null; inspecting = null;
        release(structureBytes); structureBytes = 0;
        return found ? startOriginal() : finish();
    }

    private boolean beginOriginal() {
        if (forceCraft) {
            targetCredit = summary.delta(original.target());
            // Some indivisible forced plans need a separate count proof.
            // Preserve those unchanged, rather than weakening its premise.
            if (targetCredit.compareTo(BigInteger.valueOf(original.amount())) < 0) return finish();
        }
        entries = original.initialExact().entrySet().iterator(); phase = Phase.INITIAL; return false;
    }

    private static BigInteger ceil(BigInteger gap, BigInteger gain) { return CheckedAmounts.ceilDiv(gap.max(BigInteger.ZERO), gain); }
    private BigInteger value(Map<K, BigInteger> map, K key) { return map.getOrDefault(key, BigInteger.ZERO); }
    private void put(Map<K, BigInteger> map, K key, BigInteger value) {
        if (value.signum() == 0) { if (map.remove(key) != null) release(128); return; }
        if (!map.containsKey(key)) reserve(128);
        map.put(key, value);
    }
    private long retain(SequenceSummary<K> value) {
        long bytes = 128L * ((long) value.delta().size() + value.required().size() + value.peak().size());
        reserve(bytes); return bytes;
    }
    private PlanVerification<K> verify(GraphPlan<K> plan) {
        var funded = GraphPlan.ownedRefill(plan, plan.steps(), plan.recipes(), plan.initialExact(), Map.of(),
                GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL, budget.nodes());
        return lastUnitSummary == null ? PlanVerification.refill(funded, budget) :
                PlanVerification.refillFolded(funded, budget, lastUnit, lastUnitSummary);
    }
    private void push(List<PlanStep> children) { reserve(64); stack.push(new Cursor(children)); }
    private static final class Cursor {
        final List<PlanStep> children;
        int index;
        Cursor(List<PlanStep> children) { this.children = children; index = children.size(); }
        PlanStep previous() { return children.get(--index); }
    }
    private boolean complete(boolean accept) {
        if (accept) result = candidate;
        // The original per-batch pass runs first with its unchanged allowance.
        // Further grouping starts from that completed incumbent, and every
        // interruption keeps it. Children cannot regroup recursively; only an
        // unproductive short grouping may try a larger-period sibling.
        long left = allowance - (budget.nodes() - started);
        if (allowBlocks && flatBatches >= 4 && left >= 512) {
            if (shortBlocksImpossible) return startAnchors();
            blocks = new RefillWordBlocks(reversed, budget, Math.min(2048, left / 2),
                    compactCursors || budget.nodes() - started >= 2048);
            phase = Phase.BLOCKS; return false;
        }
        return finishGrouping();
    }
    private List<PlanStep> groupingWord() { return groupedAtoms == null ? reversed : groupedAtoms; }
    private boolean finishGrouping() {
        if (!allowBlocks || spansTried || reversed.size() < 4) return finish();
        long left = allowance - (budget.nodes() - started);
        // First preserve every result of the existing identity-based passes.
        // An opaque-only word previously failed the flat-batch admission gate.
        // With no grouping improvement, exact local shapes can enable one more
        // bounded attempt, using only the same remaining quality allowance.
        if (left < 1024) return finish();
        if (blockPruning != null) { blockPruning.close(); blockPruning = null; regrouped = null; }
        if (blocks != null) { blocks.close(); blocks = null; }
        if (anchors != null) { anchors.close(); anchors = null; }
        if (!shapesTried && opaqueAtoms >= 2) {
            shapesTried = true;
            shapes = new RefillWordShapes(reversed, budget, Math.min(2048, left / 2));
            phase = Phase.SHAPES; return false;
        }
        if (!normalTried && opaqueAtoms > 0) {
            normalTried = true;
            normalForms = new RefillWordNormalForms(groupingWord(), budget, Math.min(2048, left / 2));
            phase = Phase.NORMAL_FORMS; return false;
        }
        // Expose equal spans individually without moving the intervening work.
        // The child may drop occurrences only after exact backward requirements
        // and whole-plan verification; recognition alone changes no execution.
        spansTried = true;
        spans = new RefillWordSpans(groupingWord(), budget, Math.min(2048, left / 2));
        phase = Phase.SPANS; return false;
    }
    private boolean startAnchors() {
        return startAnchors(false);
    }
    private boolean startAnchors(boolean longerOnly) {
        long left = allowance - (budget.nodes() - started);
        // Two periods beyond the old 16-atom horizon need >=34 atoms.
        if (left < 1024 || groupingWord().size() < 34) return finishGrouping();
        anchors = new RefillWordAnchors(groupingWord(), budget, Math.min(2048, left / 2),
                longerOnly ? RefillWordBlocks.MAX_PERIOD + 1 : 2);
        phase = Phase.ANCHORS; return false;
    }
    private boolean pruneBlocks(PlanStep word) {
        regrouped = GraphPlan.ownedRefill(result, word, result.recipes(), result.initialExact(), result.missingExact(),
                result.result(), budget.nodes());
        // Exact grouping preserves every batch boundary and its order. The
        // owning verifier stays charged until this child closes.
        var known = verification != null && result == candidate ? verification.summary() : null;
        // When the original word is unchanged, its external verifier still
        // owns the borrowed fold throughout this continuation. Exact grouping
        // changes no execution or binding. Reuse depends on that ownership,
        // not how much work the preceding traversal happened to consume.
        boolean scheduledFloor = budget.nodes() - started >= 2048;
        if (known == null && result == original) known = verifiedOriginal;
        blockPruning = new RefillWordPruning<>(regrouped, stock, external, forceCraft, ignoreTargetStock, budget,
                allowance - (budget.nodes() - started), known).startupCatalog(startupRecipes, startupExcluded);
        // This is the same quality pass, not a fresh work-scaled preview. The
        // parent's reached floor permits its child to omit empty handoffs too.
        blockPruning.compactCursors = compactCursors || scheduledFloor;
        // This parent already checked every recipe for batch-sensitive inputs
        // and admitted this unchanged execution trace. Only grouping differs;
        // a repeated binding/feedback scan adds no information. The child still
        // verifies all resulting supplies, force credit, delivery and seeds.
        blockPruning.inheritedStructure = result == original;
        // A newly exposed pair shares its immutable body. Retain just its last
        // computed fold across intervening flat steps, in this binding/pass.
        // It replaces the normal unit lease, has no preparation quota, and is
        // evicted before folding a different body. Existing passes stay first.
        blockPruning.reuseUnit = normalTried || spansTried;
        // Only opaque grouping can hide a smaller dispatch period. A strict
        // rounding residual in every active count bound admits a follow-up;
        // integral requirements and ordinary flat groups keep their old path.
        blockPruning.trackRounding = opaqueAtoms >= 2 && !normalTried;
        blockPruning.allowBlocks = false; phase = Phase.BLOCK_PRUNE; return false;
    }
    private boolean finish() {
        phase = Phase.DONE;
        budget.note("refill_word", (result != original ? "pruned" : "retained_original") + "; work=" + (budget.nodes() - started));
        return true;
    }
    GraphPlan<K> result() { return result; }
    private void discardHint() {
        if (shortHint != null) { shortHint.close(); shortHint = null; }
    }
    private void reserve(long bytes) {
        // Never let optional structure information displace the primary pass.
        if (shortHint != null && budget.availableBytes() < bytes) discardHint();
        budget.reserve(bytes); memory += bytes;
    }
    private void release(long bytes) { budget.release(bytes); memory -= bytes; }
    @Override public void close() {
        if (startup != null) { startup.close(); startup = null; }
        if (periodPruning != null) { periodPruning.close(); periodPruning = null; }
        if (periods != null) { periods.close(); periods = null; }
        if (blockPruning != null) { blockPruning.close(); blockPruning = null; }
        if (blocks != null) { blocks.close(); blocks = null; }
        discardHint();
        if (anchors != null) { anchors.close(); anchors = null; }
        if (shapes != null) { shapes.close(); shapes = null; }
        if (normalForms != null) { normalForms.close(); normalForms = null; }
        if (spans != null) { spans.close(); spans = null; }
        groupedAtoms = null;
        lastUnit = null; lastUnitSummary = null; lastUnitBytes = 0;
        if (verification != null) { verification.close(); verification = null; }
        if (computation != null) { computation.close(); computation = null; }
        entries = null; seeds = null; recipes = null;
        structure = null; consumed = null; ports = null; inspecting = null; unitRecipe = null;
        budget.release(memory); memory = 0;
    }
}
