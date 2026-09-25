package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Builds an executable prefix before repeating it. This bounded allocation
 * heuristic can represent nested returns; its result still needs verification.
 */
final class DemandExpansion<K> {

    private final Map<String, GraphRecipe<K>> recipes;
    private final Map<K, List<GraphRecipe<K>>> producers = new HashMap<>();
    private final Map<K, BigInteger> held;
    private final Map<K, BigInteger> goals;
    private final PlanningBudget budget;
    private final Iterator<GraphRecipe<K>> indexing;
    private final Deque<Frame> frames = new ArrayDeque<>();
    private final Map<K, Frame> active = new HashMap<>();
    private final List<Change<K>> undo = new ArrayList<>();
    private final List<PlanStep> path = new ArrayList<>();
    private final Map<PlanStep, SequenceSummary<K>> summaries = new IdentityHashMap<>();
    private final long allowance;
    private long memory, expansionWork, summaryWork;
    private int phase, rootPasses;
    private boolean failed;
    private PlanStep result;
    private Frame satisfiedAncestor;
    private final boolean preview;

    DemandExpansion(Map<String, GraphRecipe<K>> recipes, Map<K, BigInteger> held, Map<K, BigInteger> goals, PlanningBudget budget) {
        this(recipes, held, goals, budget, false);
    }

    DemandExpansion(Map<String, GraphRecipe<K>> recipes, Map<K, BigInteger> held, Map<K, BigInteger> goals, PlanningBudget budget, boolean preview) {
        this.recipes = recipes;
        this.held = new HashMap<>(held);
        this.goals = goals;
        this.budget = budget;
        this.preview = preview;
        indexing = recipes.values().iterator();
        allowance = Math.min(1_000_000L, 32_768L + 256L * recipes.size());
        reserve(256L + 96L * (held.size() + goals.size()));
    }

    boolean step() {
        long before = budget.nodes(), summariesBefore = summaryWork;
        try {
            return advance();
        } finally {
            expansionWork += budget.nodes() - before - (summaryWork - summariesBefore);
        }
    }

    private boolean advance() {
        budget.check();
        // A local strategy allowance counts its own continuation steps. Using
        // the shared budget delta also counted scheduler/parent steps, making
        // a plan fail depending on slice boundaries or the worker count.
        // All arithmetic and summary work still charge the global budget.
        if (failed || expansionWork > allowance) return true;
        if (phase == 0) {
            if (indexing.hasNext()) {
                GraphRecipe<K> recipe = indexing.next();
                for (K key : recipe.executionOutputs().keySet()) {
                    budget.check();
                    producers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(recipe);
                    reserve(96);
                }
                return false;
            }
            phase = 1;
        }
        if (satisfiedAncestor != null) {
            Frame satisfied = satisfiedAncestor;
            satisfiedAncestor = null;
            // Making a selected input may already supply an ancestor's actual
            // goal as a coproduct. Keep this executable prefix and discard the
            // now-unnecessary intermediate request, not its produced material.
            if (active.get(satisfied.key) == satisfied && amount(satisfied.key).compareTo(satisfied.wanted) >= 0) {
                while (frames.peek() != satisfied) {
                    budget.check();
                    pop(true);
                }
            }
        }
        if (frames.isEmpty()) {
            for (var goal : goals.entrySet()) {
                budget.check();
                if (amount(goal.getKey()).compareTo(goal.getValue()) < 0) {
                    if (++rootPasses > 64) return true;
                    push(goal.getKey(), goal.getValue());
                    return false;
                }
            }
            result = new PlanStep.Sequence(path);
            return true;
        }
        Frame frame = frames.peek();
        if (frame.summary != null) {
            long before = budget.nodes();
            boolean complete = frame.summary.step();
            summaryWork += budget.nodes() - before;
            if (!complete) return false;
            SequenceSummary<K> summary = frame.summary.result();
            frame.summary = null;
            if (summaries.putIfAbsent(frame.body, summary) == null)
                reserve(128L + 96L * (summary.required().size() + summary.delta().size() + summary.peak().size()));
            BigInteger gain = summary.delta(frame.key);
            if (gain.signum() <= 0) {
                if (frame.prefixSummary || frame.reentry || frame.peeledPrefix != null) {
                    frame.prefixSummary = false;
                    frame.reentry = false;
                    frame.peeledPrefix = null;
                    frame.body = null;
                    frame.unitStarted = false;
                } else reject(frame);
                return false;
            }
            BigInteger gap = frame.wanted.subtract(amount(frame.key));
            BigInteger repeats = gap.signum() <= 0 ? BigInteger.ZERO : CheckedAmounts.ceilDiv(gap, gain);
            for (K key : summary.keys()) {
                budget.check();
                BigInteger current = amount(key), need = summary.required(key), change = summary.delta(key);
                if (supplementable(key, frame)) continue;
                if (current.compareTo(need) < 0) {
                    repeats = BigInteger.ZERO;
                    break;
                }
                if (change.signum() < 0)
                    repeats = repeats.min(current.subtract(need).divide(change.negate()).add(BigInteger.ONE));
                Frame ancestor = active.get(key);
                if (change.signum() > 0 && ancestor != null && ancestor != frame) {
                    // Stop at the first useful coproduct breakpoint. Fully
                    // expanding the selected intermediate can manufacture far
                    // more than needed, or consume another branch's seed.
                    BigInteger ancestorGap = ancestor.wanted.subtract(current);
                    if (ancestorGap.signum() > 0)
                        repeats = repeats.min(CheckedAmounts.ceilDiv(ancestorGap, change));
                }
            }
            // One iteration has already run. Replace its trace by a compressed
            // body, avoiding duplicate subtrees at every nesting level.
            BigInteger extra = repeats;
            if (extra.signum() > 0) {
                for (K key : summary.keys()) if (supplementable(key, frame)) {
                    BigInteger needed = summary.required(key).add(summary.delta(key).negate().max(BigInteger.ZERO).multiply(extra.subtract(BigInteger.ONE)));
                    if (amount(key).compareTo(needed) < 0) set(key, needed);
                }
                for (var change : summary.delta().entrySet()) {
                    budget.check();
                    set(change.getKey(), amount(change.getKey()).add(change.getValue().multiply(extra)));
                }
                if (frame.reentry) append(PlanStep.repeat(frame.body, extra));
                else {
                    truncate(frame.prefixSummary ? frame.originalPath : frame.unitPath);
                    if (frame.peeledPrefix != null) frame.peeledPrefix.forEach(this::append);
                    if (frame.body instanceof PlanStep.Batch batch)
                    append(PlanStep.batch(batch.recipe(), BigInteger.valueOf(batch.runs()).multiply(extra.add(BigInteger.ONE))));
                    else append(PlanStep.repeat(frame.body, extra.add(BigInteger.ONE)));
                }
            }
            if (extra.signum() == 0 && !frame.prefixSummary && !frame.reentry &&
                    amount(frame.key).compareTo(frame.wanted) < 0 && frame.body instanceof PlanStep.Sequence sequence &&
                    sequence.children().size() > 1 && sequence.children().get(0) instanceof PlanStep.Batch setup &&
                    !recipes.get(setup.recipe()).inputs().containsKey(frame.key) &&
                    !recipes.get(setup.recipe()).outputs().containsKey(frame.key)) {
                // Factor one-time seed setup out of an otherwise repeatable
                // inner process. The suffix has already run once; its complete
                // prefix requirements must be funded before adding any repeats.
                if (frame.peeledPrefix == null) frame.peeledPrefix = new ArrayList<>();
                frame.peeledPrefix.add(setup);
                int offset = 1;
                while (offset + 1 < sequence.children().size() && sequence.children().get(offset) instanceof PlanStep.Batch next &&
                        !recipes.get(next.recipe()).inputs().containsKey(frame.key) &&
                        !recipes.get(next.recipe()).outputs().containsKey(frame.key) && !enabled(next)) {
                    // A suffix whose very first batch is unfunded cannot run in
                    // this state. Skip that setup directly instead of repeatedly
                    // summarizing all the remaining nested processes.
                    frame.peeledPrefix.add(next);
                    offset++;
                }
                List<PlanStep> suffix = sequence.children().subList(offset, sequence.children().size());
                frame.body = suffix.size() == 1 ? suffix.get(0) : new PlanStep.Sequence(suffix);
                reserve(80L + 8L * suffix.size());
                frame.summary = new SummaryComputation<>(frame.body, recipes, budget, summaries);
                return false;
            }
            if (!frame.prefixSummary && !frame.reentry && amount(frame.key).compareTo(frame.wanted) < 0 &&
                    frame.peeledPrefix == null &&
                    frame.unitPath > 0 && path.get(frame.unitPath - 1) instanceof PlanStep.Batch loan && loan.runs() == 1 &&
                    reopens(loan, summary)) {
                // A parent may already have lent a catalyst before this demand
                // starts. Its first body returns that catalyst, so the body alone
                // cannot repeat. Match loan + body as a new cycle, independently
                // summarize it, and append only its future executions.
                frame.reentry = true;
                frame.body = new PlanStep.Sequence(List.of(loan, frame.body));
                reserve(80);
                frame.summary = new SummaryComputation<>(frame.body, recipes, budget, summaries);
                return false;
            }
            frame.reentry = false;
            frame.peeledPrefix = null;
            frame.body = null;
            frame.unitStarted = false;
            if (!frame.prefixSummary && frames.size() == 1 && frame.iterations > 1 &&
                    amount(frame.key).compareTo(frame.wanted) < 0) {
                // A shared cycle may alternate several different input routes:
                // each individual iteration drains a finite intermediate, while
                // their composition restores it. Compile that actual prefix as
                // one macro and apply the same exact requirement/delta check.
                // Never extrapolate from net target output alone.
                frame.prefixSummary = true;
                List<PlanStep> prefix = path.subList(frame.originalPath, path.size());
                frame.body = new PlanStep.Sequence(prefix);
                reserve(64L + 8L * prefix.size());
                frame.summary = new SummaryComputation<>(frame.body, recipes, budget, summaries);
            } else frame.prefixSummary = false;
            return false;
        }
        if (amount(frame.key).compareTo(frame.wanted) >= 0) {
            pop(true);
            return false;
        }
        if (frame.recipe == null) {
            if (frame.producer >= frame.alternatives.size()) {
                // Only after the stocked alternatives have been tried may a
                // proven-infeasible order construct a hypothetical raw refill.
                // Real runtime inventory is never changed by this preview.
                if (preview && !frame.supplementing && !frame.alternatives.isEmpty()) {
                    frame.supplementing = true;
                    frame.producer = frame.alternatives.size() - 1;
                    return false;
                }
                if (supplementable(frame.key, frame)) {
                    set(frame.key, frame.wanted);
                    pop(true);
                    return false;
                }
                pop(false);
                return false;
            }
            frame.recipe = frame.alternatives.get(frame.producer++);
        }
        if (!frame.unitStarted) {
            if (++frame.iterations > 256) {
                pop(false);
                return false;
            }
            frame.unitStarted = true;
            frame.unitUndo = undo.size();
            frame.unitPath = path.size();
        }
        // Recheck all inputs after a child completes: producing a later input
        // may temporarily consume and return an earlier catalyst.
        for (var input : frame.recipe.inputs().entrySet()) {
            budget.check();
            if (amount(input.getKey()).compareTo(BigInteger.valueOf(input.getValue())) >= 0) continue;
            if (active.containsKey(input.getKey()) || frames.size() >= 4096) reject(frame);
            else push(input.getKey(), BigInteger.valueOf(input.getValue()));
            return false;
        }
        for (var input : frame.recipe.inputs().entrySet()) {
            budget.check();
            set(input.getKey(), amount(input.getKey()).subtract(BigInteger.valueOf(input.getValue())));
        }
        for (var output : frame.recipe.outputs().entrySet()) {
            budget.check();
            set(output.getKey(), amount(output.getKey()).add(BigInteger.valueOf(output.getValue())));
        }
        append(new PlanStep.Batch(frame.recipe.id(), 1));
        List<PlanStep> unit = path.subList(frame.unitPath, path.size());
        frame.body = unit.size() == 1 ? unit.get(0) : new PlanStep.Sequence(unit);
        reserve(64L + 8L * unit.size());
        frame.summary = new SummaryComputation<>(frame.body, recipes, budget, summaries);
        return false;
    }

    private BigInteger amount(K key) {
        return held.getOrDefault(key, BigInteger.ZERO);
    }

    private boolean supplementable(K key, Frame frame) {
        if (!preview || producers.containsKey(key)) return false;
        if (frame.supplementing) return true;
        return frames.stream().anyMatch(parent -> parent.supplementing);
    }

    private boolean reopens(PlanStep.Batch loan, SequenceSummary<K> summary) {
        GraphRecipe<K> recipe = recipes.get(loan.recipe());
        for (var input : summary.required().entrySet()) {
            budget.check();
            K key = input.getKey();
            if (amount(key).compareTo(input.getValue()) < 0 &&
                    recipe.outputs().getOrDefault(key, 0L) > recipe.inputs().getOrDefault(key, 0L)) return true;
        }
        return false;
    }

    private boolean enabled(PlanStep.Batch batch) {
        for (var input : recipes.get(batch.recipe()).inputs().entrySet()) {
            budget.check();
            if (amount(input.getKey()).compareTo(BigInteger.valueOf(input.getValue())) < 0) return false;
        }
        return true;
    }

    private void push(K key, BigInteger wanted) {
        reserve(256);
        Frame frame = new Frame(key, wanted);
        frames.push(frame);
        active.put(key, frame);
    }

    private void pop(boolean success) {
        Frame frame = frames.pop();
        active.remove(frame.key);
        release(256);
        if (!success) {
            rollback(frame.originalUndo);
            truncate(frame.originalPath);
            if (frames.isEmpty()) failed = true;
            else reject(frames.peek());
        }
    }

    private void reject(Frame frame) {
        rollback(frame.unitUndo);
        truncate(frame.unitPath);
        frame.recipe = null;
        frame.unitStarted = false;
        frame.summary = null;
        frame.body = null;
        frame.prefixSummary = false;
        frame.reentry = false;
        frame.peeledPrefix = null;
    }

    private void set(K key, BigInteger value) {
        if (value.signum() < 0) throw new IllegalStateException("Unfunded demand expansion");
        BigInteger previous = amount(key);
        if (previous.equals(value)) return;
        reserve(128);
        undo.add(new Change<>(key, previous));
        if (value.signum() == 0) held.remove(key);
        else held.put(key, value);
        Frame ancestor = active.get(key);
        if (ancestor != null && ancestor != frames.peek() && value.compareTo(ancestor.wanted) >= 0)
            satisfiedAncestor = ancestor;
    }

    private void rollback(int mark) {
        while (undo.size() > mark) {
            budget.check();
            Change<K> change = undo.remove(undo.size() - 1);
            if (change.previous().signum() == 0) held.remove(change.key());
            else held.put(change.key(), change.previous());
            release(128);
        }
    }

    private void append(PlanStep step) {
        reserve(64);
        path.add(step);
    }

    private void truncate(int mark) {
        // Removed nodes may still be referenced by a Repeat body. Keep their
        // conservative reservation until this whole speculative pass is closed.
        while (path.size() > mark) {
            budget.check();
            path.remove(path.size() - 1);
        }
    }

    PlanStep result() {
        return result;
    }

    void close() {
        budget.release(memory);
        memory = 0;
    }

    private void reserve(long bytes) {
        budget.reserve(bytes);
        memory += bytes;
    }

    private void release(long bytes) {
        budget.release(bytes);
        memory -= bytes;
    }

    private record Change<K>(K key, BigInteger previous) {}

    private record Producer<K>(GraphRecipe<K> recipe, int blocked, int missing, BigInteger fundedOutput) {}

    private final class Frame {

        final K key;
        final BigInteger wanted;
        final List<GraphRecipe<K>> alternatives;
        final int originalUndo = undo.size(), originalPath = path.size();
        int producer, unitUndo, unitPath, iterations;
        boolean unitStarted, prefixSummary, reentry, supplementing;
        GraphRecipe<K> recipe;
        PlanStep body;
        List<PlanStep> peeledPrefix;
        SummaryComputation<K> summary;

        Frame(K key, BigInteger wanted) {
            this.key = key;
            this.wanted = wanted;
            List<GraphRecipe<K>> candidates = producers.getOrDefault(key, List.of());
            if (candidates.size() <= 1) {
                alternatives = candidates;
            } else {
                // Discovery order is not a startup strategy. A catalyst's return
                // recipe may be indexed before the route that lends it in the
                // first place. Prefer funded inputs and avoid active demands;
                // keep every alternative, including partially funded sources.
                reserve(64L + 48L * candidates.size());
                List<Producer<K>> ranked = new ArrayList<>(candidates.size());
                for (GraphRecipe<K> recipe : candidates) {
                    int blocked = 0, missing = 0;
                    BigInteger funded = null;
                    for (var input : recipe.inputs().entrySet()) {
                        budget.check();
                        if (preview) {
                            BigInteger runs = amount(input.getKey()).divide(BigInteger.valueOf(input.getValue()));
                            funded = funded == null ? runs : funded.min(runs);
                        }
                        if (amount(input.getKey()).compareTo(BigInteger.valueOf(input.getValue())) >= 0) continue;
                        missing++;
                        if (input.getKey().equals(key) || active.containsKey(input.getKey())) blocked++;
                    }
                    ranked.add(new Producer<>(recipe, blocked, missing,
                            funded == null ? wanted : funded.multiply(BigInteger.valueOf(recipe.outputs().get(key)))));
                }
                Comparator<Producer<K>> ordering = Comparator.comparingInt(Producer<K>::blocked).thenComparingInt(Producer<K>::missing);
                // Diagnostic refills should first try a route that can cover the
                // request with the supplied stock, retaining every other source.
                if (preview) ordering = ordering.thenComparing(Producer<K>::fundedOutput, Comparator.reverseOrder());
                ranked.sort(ordering);
                alternatives = ranked.stream().map(Producer::recipe).toList();
            }
        }
    }
}
