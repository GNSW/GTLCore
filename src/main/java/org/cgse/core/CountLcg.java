// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Lazy bound-literal generation for mathematical integer domains. Bounds are
 * created by propagation or a split, never by enumerating a long domain. Each
 * implication records its antecedents at that trail prefix; first-UIP analysis
 * resolves those explanations into a reusable forbidden conjunction.
 */
final class CountLcg implements AutoCloseable {

    private record Literal(int variable, boolean minimum, BigInteger value) {

        Literal opposite() {
            return new Literal(variable, !minimum, value.add(minimum ? BigInteger.ONE.negate() : BigInteger.ONE));
        }

        ExactLinearProgram.Constraint row() {
            return new ExactLinearProgram.Constraint(Map.of(variable, minimum ? BigInteger.ONE.negate() : BigInteger.ONE),
                    minimum ? value.negate() : value);
        }
    }

    /** Stable row identity and coefficient on a variable propagation edge. */
    private record Incidence(int row, BigInteger coefficient) {}

    private record LinearReason(ExactLinearProgram.Constraint row, int except, BigInteger slack, int prefix, int rowId) {}

    private static final class ExplainedLiteral {

        final Literal literal;
        final int source;
        int heapIndex;

        ExplainedLiteral(Literal literal, int source, int heapIndex) {
            this.literal = literal;
            this.source = source;
            this.heapIndex = heapIndex;
        }
    }

    /** Sources are stable during one analysis; no trail change crosses this cache. */
    private final class ConflictFrontier {

        final Map<Integer, ExplainedLiteral> terms = new LinkedHashMap<>();
        final List<ExplainedLiteral> latest = new ArrayList<>();

        void addAll(Collection<Literal> literals) {
            for (Literal literal : literals) {
                charge();
                if (rootTrue(literal)) continue;
                int key = boundKey(literal.variable, literal.minimum);
                ExplainedLiteral old = terms.get(key);
                if (old != null && (literal.minimum ? literal.value.compareTo(old.literal.value) <= 0 :
                        literal.value.compareTo(old.literal.value) >= 0)) continue;
                var explained = new ExplainedLiteral(literal, source(literal), old == null ? latest.size() : old.heapIndex);
                terms.put(key, explained);
                if (old == null) latest.add(explained);
                else latest.set(old.heapIndex, explained);
                // A stronger bound can only move its earliest implication later
                // on the unchanged trail. Preserve insertion order separately
                // for the learned clause and its subsequent watched literals.
                siftUp(explained);
            }
        }

        private void siftUp(ExplainedLiteral value) {
            int position = value.heapIndex;
            while (position > 0) {
                charge();
                int parent = (position - 1) >>> 1;
                ExplainedLiteral before = latest.get(parent);
                if (before.source >= value.source) break;
                latest.set(position, before);
                before.heapIndex = position;
                position = parent;
            }
            latest.set(position, value);
            value.heapIndex = position;
        }

        int highestLevel() {
            charge();
            return latest.isEmpty() ? 0 : trail.get(latest.get(0).source).level;
        }

        boolean asserting() {
            charge();
            if (latest.size() <= 1) return true;
            int second = latest.size() > 2 && latest.get(2).source > latest.get(1).source ? 2 : 1;
            return trail.get(latest.get(second).source).level < trail.get(latest.get(0).source).level;
        }

        int removeLatest() {
            charge();
            ExplainedLiteral removed = latest.get(0);
            terms.remove(boundKey(removed.literal.variable, removed.literal.minimum));
            ExplainedLiteral value = latest.remove(latest.size() - 1);
            if (latest.isEmpty()) return removed.source;
            int position = 0;
            while (2 * position + 1 < latest.size()) {
                charge();
                int child = 2 * position + 1;
                if (child + 1 < latest.size() && latest.get(child + 1).source > latest.get(child).source) child++;
                ExplainedLiteral after = latest.get(child);
                if (value.source >= after.source) break;
                latest.set(position, after);
                after.heapIndex = position;
                position = child;
            }
            latest.set(position, value);
            value.heapIndex = position;
            return removed.source;
        }

        List<Literal> literals() {
            return terms.values().stream().map(value -> value.literal).toList();
        }
    }

    private static final class Change {

        final Literal literal;
        final BigInteger old;
        final int level;
        final LinearReason linear;
        List<Literal> reason;
        long bytes;

        Change(Literal literal, BigInteger old, int level, List<Literal> reason, LinearReason linear, long bytes) {
            this.literal = literal;
            this.old = old;
            this.level = level;
            this.reason = reason;
            this.linear = linear;
            this.bytes = bytes;
        }
    }

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private static final class Nogood {

        final List<Literal> terms;
        final int lbd;
        long used;
        final Watch first = new Watch(this), second = new Watch(this);
        Nogood pendingPrevious, pendingNext;
        boolean pending;

        Nogood(List<Literal> terms, int lbd, long used) {
            this.terms = terms;
            this.lbd = lbd;
            this.used = used;
        }
    }

    /** Intrusive subscriptions avoid allocating a new list entry when a watch moves. */
    private static final class Watch {

        final Nogood clause;
        int term = -1;
        Watch previous, next;

        Watch(Nogood clause) { this.clause = clause; }
    }

    private static final class RowActivity {

        boolean valid;

        BigInteger minimum = BigInteger.ZERO;
        BigInteger maximumChange = BigInteger.ZERO;
        int infinities, infiniteXor;
        int[] booleanOrder;
    }

    private final PlanningBudget budget;
    private List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] rootLow, rootHigh, low, high;
    private final BigInteger[] levelZeroLow, levelZeroHigh;
    private long rootVersion;
    private final double[] activity;
    private RowActivity[] rowActivity;
    private final List<List<Incidence>> incident = new ArrayList<>();
    private final List<List<Integer>> boundTrail = new ArrayList<>();
    private final Deque<Integer> queue = new ArrayDeque<>();
    private final BitSet queued = new BitSet();
    private final List<Change> trail = new ArrayList<>();
    private final List<Nogood> clauses = new ArrayList<>();
    private final Watch[] watches;
    private Nogood pendingClause, lastPendingClause;
    private final List<CountConflict> learned = new ArrayList<>(), proofSteps = new ArrayList<>();
    private final Set<CountConflict> imported = new LinkedHashSet<>();
    private final Set<ExactLinearProgram.Constraint> importedRows = new LinkedHashSet<>();
    private final List<CountLpLearning.Cut> sharedRows = new ArrayList<>();
    private boolean shareRows;
    private long allowance;
    private List<Literal> conflict;
    private BigInteger[] counts;
    private int level, decisions, conflicts, jumps, restarts, nextRestart = 64;
    private long work, workTicks, memory, rootProgress, learnedProgress, relaxedReasons, lazyReasons, explainedReasons;
    private double increment = 1;
    private boolean complete, infeasible, paused, differenceChecked;
    private final boolean retainProof;
    private CountProof.Certificate certificate;
    private CountProof.Certificate differenceProof;
    private boolean lockBranching;
    private BigInteger[] savedValues;
    private boolean finiteHintTried;
    private long finiteHintDeferredAllowance = -1, finiteHintPreparationWork;
    private double[] finiteHint;
    private int fixedPeak;
    private boolean learnedRelaxation;
    private int originalRows, relaxationDecisions = -1, relaxationConflicts = -1;
    private long relaxationCalls, relaxationCuts, relaxationMisses, relaxationNumericalWork;
    private double[] relaxationPoint;
    private Set<ExactLinearProgram.Constraint> relaxationKnown;
    private final List<CountProof.Combination> derivedRows = new ArrayList<>();
    private final Set<ExactLinearProgram.Constraint> cycleRows = new HashSet<>();
    private int cycleDecisions = -1, cycleConflicts = -1;
    private long cycleNext, cycleWork;
    private CountLpLearning.Session lpSession;
    private CountLcgReliability reliability;
    private CountLcgRowPool rowPool;
    private int pendingVariable = -1, pendingLevel, pendingTrail;
    private boolean pendingUp;
    private double pendingPoint;

    private void observeDecision(boolean cutoff) {
        if (reliability == null || pendingVariable < 0) return;
        if (pendingLevel == level) reliability.observe(pendingVariable, pendingUp, pendingPoint,
                Math.max(0, trail.size() - pendingTrail - 1), cutoff);
        pendingVariable = -1;
    }

    private void wakeRow(int id) {
        invalidateRow(id);
        enqueue(id);
    }

    private void invalidateRow(int id) {
        if (rowActivity[id] != null) rowActivity[id].valid = false;
    }

    /** Opt-in Boolean LP explanations; ordinary lazy-bound searches retain their old path. */
    CountLcg learnedRelaxation() {
        try {
            return enableLearnedRelaxation();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private CountLcg enableLearnedRelaxation() {
        if (learnedRelaxation || !importedRows.isEmpty() || !cycleRows.isEmpty() || complete || low.length < 2 || low.length > 128 || rows.size() > 512) return this;
        for (int i = 0; i < low.length; i++) {
            charge();
            if (low[i].signum() < 0 || high[i] == null || high[i].compareTo(BigInteger.ONE) > 0) return this;
        }
        long perCut = 256L + 128L * low.length;
        int capacity = (int) Math.min(2048, Math.min(budget.availableBytes() / 16 / perCut,
                budget.remainingWork() / Math.max(1, 8L * low.length)));
        if (capacity < 16) return this;
        long bytes = 256L + 16L * low.length + 80L * rows.size() + 8L * capacity;
        if (!budget.tryReserve(bytes)) return this;
        memory += bytes;
        rows = new ArrayList<>(rows);
        relaxationKnown = new HashSet<>(rows);
        originalRows = rows.size();
        rowActivity = Arrays.copyOf(rowActivity, rows.size() + capacity);
        learnedRelaxation = true;
        lpSession = new CountLpLearning.Session();
        reliability = new CountLcgReliability(low.length, budget, this::charge, this::integerCost);
        rowPool = new CountLcgRowPool(originalRows, rowActivity.length, low.length, budget, this::charge);
        return this;
    }

    boolean learnedRelaxationEnabled() {
        return learnedRelaxation;
    }

    void shareRows() {
        shareRows = true;
    }

    List<CountLpLearning.Cut> sharedRows() {
        return List.copyOf(sharedRows);
    }

    /** An independent rounding arm; the default search keeps its old ordering. */
    CountLcg lockBranching() {
        if (lockBranching || complete && !paused) return this;
        long bytes = 128L + 32L * low.length;
        try {
            if (!budget.tryReserve(bytes)) return this;
            memory += bytes;
            savedValues = new BigInteger[low.length];
            // Locks already describe the active row structure. Do not count
            // the constructor's static row degrees again: redundant bounds
            // would then change this arm's initial ordering.
            Arrays.fill(activity, 0);
            lockBranching = true;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
        return this;
    }

    CountLcg(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
             PlanningBudget budget, long maximumWork) {
        this(rows, lower, upper, budget, maximumWork, false);
    }

    CountLcg(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
             PlanningBudget budget, long maximumWork, boolean retainProof) {
        this.rows = rows;
        originalRows = rows.size();
        this.budget = budget;
        this.retainProof = retainProof || budget.proofJournal() != null;
        allowance = Math.min(maximumWork, budget.remainingWork() / 8);
        long terms = rows.stream().mapToLong(r -> r.terms().size()).sum();
        boolean admitted = allowance >= 1024 && CountModelViews.admissible(lower.length, rows.size(), terms, budget);
        long bytes = admitted ? 2112 + 552L * lower.length + 112L * terms + 256L * rows.size() : 0;
        admitted = admitted && budget.tryReserve(bytes);
        memory = admitted ? bytes : 0;
        try {
            rootLow = admitted ? lower.clone() : new BigInteger[0];
            rootHigh = admitted ? upper.clone() : new BigInteger[0];
            low = rootLow.clone();
            high = rootHigh.clone();
            levelZeroLow = rootLow.clone();
            levelZeroHigh = rootHigh.clone();
            activity = new double[low.length];
            rowActivity = new RowActivity[admitted ? rows.size() : 0];
            watches = new Watch[low.length];
            if (!admitted) {
                complete = true;
                return;
            }
            for (int i = 0; i < lower.length; i++) {
                charge();
                incident.add(new ArrayList<>());
                incident.add(new ArrayList<>());
                boundTrail.add(new ArrayList<>());
                boundTrail.add(new ArrayList<>());
                if (upper[i] != null && lower[i].compareTo(upper[i]) > 0) conflict = List.of();
            }
            for (int r = 0; r < rows.size(); r++) {
                charge();
                for (var term : rows.get(r).terms().entrySet()) {
                    charge();
                    if (term.getValue().signum() == 0) continue;
                    int id = term.getKey();
                    incident.get(boundKey(id, term.getValue().signum() > 0)).add(new Incidence(r, term.getValue()));
                    activity[id] += 1.0 / rows.get(r).terms().size();
                }
                enqueue(r);
            }
        } catch (RuntimeException | Error failure) {
            budget.release(memory);
            memory = 0;
            throw failure;
        }
    }

    boolean step() {
        if (complete) return true;
        // Yield only between atomic propagation/analysis operations. Throwing
        // from charge() used to lose a dequeued row or half of an explanation.
        // A suspended search retains its queue, trail, activities and clauses.
        if (work >= allowance) {
            paused = true;
            return finish("local_pause");
        }
        try {
            charge();
            if (!differenceChecked) {
                differenceChecked = true;
                long before = budget.threadWork();
                differenceProof = CountDifference.contradiction(retainProof ? rows.subList(0, originalRows) : rows, rootLow, rootHigh, budget, allowance - work);
                workTicks += (budget.threadWork() - before) * PlanningBudget.WORK_SCALE;
                work = PlanningBudget.units(workTicks);
                if (differenceProof != null) {
                    infeasible = true;
                    return finish("difference_cycle");
                }
            }
            if (conflict != null) {
                observeDecision(true);
                analyze();
                return complete;
            }
            if (!queue.isEmpty()) {
                if (cutPropagationCycle()) return false;
                int id = queue.removeFirst();
                queued.clear(id);
                if (rowPool == null || rowPool.active(id)) {
                    long before = work;
                    int beforeTrail = trail.size();
                    propagate(id);
                    if (rowPool != null) rowPool.observed(id, trail.size() - beforeTrail + (conflict == null ? 0 : low.length), work - before, decisions);
                }
                return false;
            }
            if (pendingClause != null) {
                Nogood clause = pendingClause;
                unschedule(clause);
                propagate(clause);
                return false;
            }
            observeDecision(false);
            if (conflicts >= nextRestart && level > 0) {
                backtrack(0);
                if (rowPool != null) rowPool.restart(decisions, this::wakeRow);
                restarts++;
                nextRestart = conflicts + Math.min(2048, 64 << Math.min(5, Integer.numberOfTrailingZeros(restarts + 1)));
                return false;
            }
            if (rowPool != null) {
                rowPool.maintain(decisions, this::invalidateRow, this::wakeRow);
                if (!queue.isEmpty()) return false;
            }
            if (satisfies(low)) {
                counts = low.clone();
                return finish("verified_witness");
            }
            if (learnedRelaxation && (relaxationDecisions != decisions || relaxationConflicts != conflicts) && relax())
                return counts != null ? finish("verified_lp_rounding") : complete;
            finiteHint();
            CountLockBranch.Decision preferred = null;
            if (lockBranching) {
                // A rounding dive can approach a witness without conflicts.
                // Report only its best completed propagation depth; revisiting
                // the same domains after a restart earns no repeated reward.
                int fixed = 0;
                for (int i = 0; i < low.length; i++) {
                    charge();
                    if (low[i].equals(high[i])) fixed++;
                }
                fixedPeak = Math.max(fixedPeak, fixed);
                long before = budget.threadWork();
                try {
                    preferred = CountLockBranch.choose(rows, low, high, activity, budget);
                } finally {
                    workTicks += (budget.threadWork() - before) * PlanningBudget.WORK_SCALE;
                    work = PlanningBudget.units(workTicks);
                }
            }
            int best = preferred == null ? -1 : preferred.variable();
            if (best < 0) for (int i = 0; i < low.length; i++) {
                charge();
                if (!low[i].equals(high[i]) && (best < 0 || activity[i] > activity[best])) best = i;
            }
            if (relaxationPoint != null) {
                int candidate = -1;
                double score = -1;
                for (int i = 0; i < low.length; i++) if (!low[i].equals(high[i])) {
                    charge();
                    double value = relaxationPoint[i];
                    double fraction = Math.min(Math.abs(value - Math.floor(value)), Math.abs(Math.ceil(value) - value));
                    double merit = fraction * (1 + activity[i]);
                    if (fraction > 1e-6 && merit > score) {
                        candidate = i;
                        score = merit;
                    }
                }
                if (candidate >= 0) best = candidate;
                if (candidate >= 0 && reliability != null) {
                    reliability.probe(candidate, relaxationPoint[candidate], decisions, work,
                            rows.subList(0, originalRows), low, high);
                    int baseline = candidate;
                    double adjusted = score;
                    for (int i = 0; i < low.length; i++) if (!low[i].equals(high[i])) {
                        charge();
                        double value = relaxationPoint[i];
                        double fraction = Math.min(Math.abs(value - Math.floor(value)), Math.abs(Math.ceil(value) - value));
                        double merit = fraction * (1 + activity[i]) * reliability.factor(i, baseline, relaxationPoint);
                        if (fraction > 1e-6 && merit > adjusted) {
                            best = i;
                            adjusted = merit;
                        }
                    }
                    reliability.chosen(best, baseline);
                }
            }
            if (best < 0) throw new IllegalStateException("Fixed integer assignment was not propagated");
            BigInteger middle = high[best] == null ? low[best].add(low[best].abs().max(BigInteger.ONE)) :
                    low[best].add(high[best].subtract(low[best]).shiftRight(1));
            level++;
            decisions++;
            boolean up = preferred != null && preferred.up();
            if (finiteHint != null) {
                charge();
                if (Double.isFinite(finiteHint[best]) && finiteHint[best] >= low[best].doubleValue() &&
                        (high[best] == null || finiteHint[best] <= high[best].doubleValue()))
                    up = finiteHint[best] > middle.doubleValue();
            }
            if (savedValues != null && savedValues[best] != null) up = savedValues[best].compareTo(middle) > 0;
            if (relaxationPoint != null) up = relaxationPoint[best] >= 0.5;
            if (reliability != null) {
                pendingVariable = best;
                pendingLevel = level;
                pendingTrail = trail.size();
                pendingUp = up;
                pendingPoint = relaxationPoint == null ? 0.5 : relaxationPoint[best];
            }
            tighten(up ? new Literal(best, true, middle.add(BigInteger.ONE)) : new Literal(best, false, middle), null);
            return false;
        } catch (Stop stopped) {
            return finish("workspace_limit");
        }
    }

    /**
     * Bound propagation can walk forever around an unbounded integer cycle.
     * Combine recently active original rows instead of spending the remaining
     * budget increasing those bounds one batch at a time. Current domains only
     * select a useful consequence; every admitted row is an unconditional,
     * exactly replayable positive combination of original model rows.
     */
    private boolean cutPropagationCycle() {
        if (cycleDecisions != decisions || cycleConflicts != conflicts) {
            cycleDecisions = decisions;
            cycleConflicts = conflicts;
            cycleNext = lazyReasons + 128;
        }
        if (lazyReasons < cycleNext || cycleRows.size() >= 64 || cycleWork >= 32768 ||
                learnedRelaxation && rows.size() >= rowActivity.length)
            return false;
        cycleNext = lazyReasons + 256;
        long scratch = 32768;
        if (scratch > budget.availableBytes() / 8 || !budget.tryReserve(scratch)) return false;
        long started = work;
        try {
            var recent = new ArrayList<Integer>();
            for (int i = trail.size() - 1, end = Math.max(0, trail.size() - 64); i >= end && recent.size() < 8; i--) {
                charge();
                LinearReason reason = trail.get(i).linear;
                if (reason == null || reason.rowId >= originalRows || recent.contains(reason.rowId)) continue;
                var row = rows.get(reason.rowId);
                if (row.terms().size() >= 2 && row.terms().size() <= 16 && row.upper().bitLength() <= 2048 &&
                        row.terms().values().stream().allMatch(v -> v.bitLength() <= 2048))
                    recent.add(reason.rowId);
            }
            for (int a = 0; a < recent.size(); a++) for (int b = a + 1; b < recent.size(); b++) {
                int first = recent.get(a), second = recent.get(b);
                var left = rows.get(first);
                var right = rows.get(second);
                for (var pivot : left.terms().entrySet()) {
                    charge();
                    if (work - started >= 4096) return false;
                    BigInteger other = right.terms().get(pivot.getKey());
                    if (other == null || pivot.getValue().signum() * other.signum() >= 0) continue;
                    BigInteger gcd = pivot.getValue().gcd(other);
                    BigInteger lm = other.abs().divide(gcd), rm = pivot.getValue().abs().divide(gcd);
                    var terms = new TreeMap<Integer, BigInteger>();
                    for (var term : left.terms().entrySet()) {
                        charge();
                        integerCost(term.getValue(), lm);
                        terms.put(term.getKey(), term.getValue().multiply(lm));
                    }
                    for (var term : right.terms().entrySet()) {
                        charge();
                        integerCost(term.getValue(), rm);
                        terms.merge(term.getKey(), term.getValue().multiply(rm), BigInteger::add);
                    }
                    terms.values().removeIf(value -> value.signum() == 0);
                    if (terms.size() >= Math.max(left.terms().size(), right.terms().size())) continue;
                    BigInteger upper = left.upper().multiply(lm).add(right.upper().multiply(rm));
                    BigInteger divisor = BigInteger.ZERO;
                    boolean admitted = upper.bitLength() <= 2048;
                    for (var value : terms.values()) {
                        charge();
                        admitted &= value.bitLength() <= 2048;
                        divisor = divisor.gcd(value);
                    }
                    if (!admitted) continue;
                    if (divisor.signum() == 0) divisor = BigInteger.ONE;
                    BigInteger scale = divisor;
                    terms.replaceAll((id, value) -> value.divide(scale));
                    upper = floor(upper, divisor);
                    BigInteger minimum = BigInteger.ZERO;
                    for (var term : terms.entrySet()) {
                        charge();
                        BigInteger bound = term.getValue().signum() > 0 ? low[term.getKey()] : high[term.getKey()];
                        if (bound == null) {
                            admitted = false;
                            break;
                        }
                        integerCost(term.getValue(), bound);
                        minimum = minimum.add(term.getValue().multiply(bound));
                    }
                    if (!admitted || minimum.compareTo(upper) <= 0) continue;
                    var row = new ExactLinearProgram.Constraint(terms, upper);
                    if (cycleRows.contains(row)) continue;
                    var cut = new CountLpLearning.Cut(row, Map.of(first, lm, second, rm), divisor);
                    return admitCycleCut(cut);
                }
            }
            return false;
        } finally {
            cycleWork += work - started;
            budget.release(scratch);
        }
    }

    private boolean admitCycleCut(CountLpLearning.Cut cut) {
        var row = cut.row();
        boolean copy = cycleRows.isEmpty() && importedRows.isEmpty() && !learnedRelaxation;
        int capacity = rows.size() >= rowActivity.length ? rows.size() + 16 : rowActivity.length;
        long bytes = 2048L + 1024L * row.terms().size() + (copy ? 16L * rows.size() : 0) +
                8L * (capacity - rowActivity.length);
        if (bytes > budget.availableBytes() / 8 || !budget.tryReserve(bytes)) return false;
        memory += bytes;
        if (copy) rows = new ArrayList<>(rows);
        if (capacity != rowActivity.length) rowActivity = Arrays.copyOf(rowActivity, capacity);
        int id = rows.size();
        rows.add(row);
        cycleRows.add(row);
        if (relaxationKnown != null) relaxationKnown.add(row);
        if (rowPool != null) rowPool.added(id, decisions);
        for (var term : row.terms().entrySet()) {
            charge();
            incident.get(boundKey(term.getKey(), term.getValue().signum() > 0)).add(new Incidence(id, term.getValue()));
        }
        if (retainProof) derivedRows.add(new CountProof.Combination(cut.parents(), cut.divisor(), CountProof.row(row)));
        if (shareRows && sharedRows.size() < 64) sharedRows.add(cut);
        enqueue(id);
        return true;
    }

    private void finiteHint() {
        if (finiteHintTried || learnedRelaxation || lockBranching || allowance <= finiteHintDeferredAllowance) return;
        long remaining = 262144 - finiteHintPreparationWork;
        long maximumWork = Math.min(Math.max(0, allowance - work), Math.min(remaining, budget.remainingWork() / 16));
        if (maximumWork < 1024) {
            // A pause must not permanently disable an unattempted heuristic.
            // Retry only after resume changes the local allowance; exhausted
            // lifetime/global budgets cannot improve on a later decision.
            finiteHintTried = remaining < 1024 || budget.remainingWork() / 16 < 1024;
            finiteHintDeferredAllowance = allowance;
            return;
        }
        long preparationStarted = budget.threadWork();
        finiteHintTried = true;
        if (low.length < 2 || low.length > 256 || rows.size() > 1024) return;
        if (low.length <= 128) {
            boolean beyondBoolean = false;
            for (var endpoint : high) {
                charge();
                if (endpoint == null || endpoint.compareTo(BigInteger.ONE) > 0) {
                    beyondBoolean = true;
                    break;
                }
            }
            // The existing Boolean LP arm already covers this domain/size.
            if (!beyondBoolean) return;
        }
        long before = budget.threadWork(), oldTicks = workTicks;
        var attempt = new CountLpHint.Attempt();
        try (var result = CountLpHint.solve(rows, low, high, budget,
                Math.min(Math.max(0, allowance - work), Math.min(remaining - (before - preparationStarted), budget.remainingWork() / 16)), attempt)) {
            if (attempt.outcome == CountLpHint.Outcome.WORK_LIMIT) {
                finiteHintTried = false;
                finiteHintDeferredAllowance = allowance;
            }
            budget.note("finite_hint", "n=" + low.length + "; rows=" + rows.size() + "; point=" + (result != null && result.point != null) + "; work=" + (budget.threadWork() - before));
            if (result != null && result.point != null) {
                long bytes = 64L + 8L * low.length;
                if (budget.tryReserve(bytes)) {
                    memory += bytes;
                    finiteHint = result.point.clone();
                }
            }
        } finally {
            long spent = budget.threadWork() - before;
            finiteHintPreparationWork += budget.threadWork() - preparationStarted;
            workTicks = oldTicks + spent * PlanningBudget.WORK_SCALE;
            work = PlanningBudget.units(workTicks);
        }
    }

    /** A numerical result can only propose an exact row combination or a checked integer point. */
    private boolean relax() {
        relaxationDecisions = decisions;
        relaxationConflicts = conflicts;
        long before = budget.threadWork(), oldTicks = workTicks;
        try (var result = lpSession.solve(rows.subList(0, originalRows), low, high, budget,
                Math.min(200000, budget.remainingWork() / 4))) {
            relaxationCalls++;
            if (result == null) {
                relaxationMisses++;
                return false;
            }
            relaxationNumericalWork += result.numericalWork;
            relaxationPoint = result.point;
            if (result.numericalInfeasible && result.cut == null) relaxationMisses++;
            if (result.cut != null && rows.size() < rowActivity.length && !relaxationKnown.contains(result.cut.row())) {
                var cut = result.cut.row();
                long coefficientBytes = cut.terms().values().stream().mapToLong(value -> 32L + (value.bitLength() + 7L) / 8).sum();
                long bytes = 384L + 208L * cut.terms().size() + coefficientBytes + (cut.upper().bitLength() + 7L) / 8;
                if (retainProof) bytes += 256L + 192L * result.cut.parents().size() + 192L * cut.terms().size() + coefficientBytes +
                        result.cut.parents().values().stream().mapToLong(value -> 32L + (value.bitLength() + 7L) / 8).sum();
                reserve(bytes);
                int id = rows.size();
                rows.add(cut);
                if (rowPool != null) rowPool.added(id, decisions);
                relaxationKnown.add(cut);
                relaxationCuts++;
                for (var term : cut.terms().entrySet()) {
                    charge();
                    incident.get(boundKey(term.getKey(), term.getValue().signum() > 0)).add(new Incidence(id, term.getValue()));
                }
                if (retainProof) derivedRows.add(new CountProof.Combination(result.cut.parents(), result.cut.divisor(), CountProof.row(cut)));
                if (shareRows && sharedRows.size() < 64) {
                    long retained = 128L + 128L * result.cut.parents().size() +
                            result.cut.parents().values().stream().mapToLong(value -> 32L + (value.bitLength() + 7L) / 8).sum();
                    if (retained <= budget.availableBytes() / 8 && budget.tryReserve(retained)) {
                        memory += retained;
                        sharedRows.add(result.cut);
                    }
                }
                enqueue(id);
                return true;
            }
            if (relaxationPoint != null) {
                var rounded = new BigInteger[low.length];
                boolean valid = true;
                for (int i = 0; i < rounded.length; i++) {
                    charge();
                    if (!Double.isFinite(relaxationPoint[i])) valid = false;
                    rounded[i] = Math.round(relaxationPoint[i]) <= 0 ? BigInteger.ZERO : BigInteger.ONE;
                    if (rounded[i].compareTo(rootLow[i]) < 0 || rootHigh[i] != null && rounded[i].compareTo(rootHigh[i]) > 0) valid = false;
                }
                if (valid && satisfies(rounded)) {
                    counts = rounded;
                    return true;
                }
            }
            return false;
        } finally {
            // Calls back into charge() already update workTicks; use the total
            // actual delta once rather than charging those nested calls twice.
            workTicks = oldTicks + (budget.threadWork() - before) * PlanningBudget.WORK_SCALE;
            work = PlanningBudget.units(workTicks);
        }
    }

    private RowActivity activity(int rowId) {
        RowActivity cached = rowActivity[rowId];
        if (cached != null && cached.valid) return cached;
        boolean first = cached == null;
        if (first) cached = new RowActivity();
        cached.minimum = BigInteger.ZERO;
        cached.infinities = cached.infiniteXor = 0;
        var row = rows.get(rowId);
        for (var term : row.terms().entrySet()) {
            charge();
            BigInteger a = term.getValue();
            if (a.signum() == 0) continue;
            int id = term.getKey();
            BigInteger endpoint = a.signum() > 0 ? low[id] : high[id];
            if (endpoint == null) {
                cached.infiniteXor ^= id;
                cached.infinities++;
            } else {
                integerCost(a, endpoint);
                cached.minimum = cached.minimum.add(a.multiply(endpoint));
            }
            // The original domain bounds every later domain, including after
            // a restart. Coefficients and root domains are immutable: reuse
            // this maximum when a sleeping row's current activity is rebuilt.
            if (first && rootHigh[id] == null) cached.maximumChange = null;
            else if (first && cached.maximumChange != null) {
                BigInteger width = rootHigh[id].subtract(rootLow[id]);
                integerCost(a, width);
                cached.maximumChange = cached.maximumChange.max(a.abs().multiply(width));
            }
        }
        if (learnedRelaxation && rowId >= originalRows && cached.booleanOrder == null) {
            reserve(64L + 4L * row.terms().size());
            cached.booleanOrder = row.terms().keySet().stream().sorted((a, b) -> {
                charge();
                return row.terms().get(b).abs().compareTo(row.terms().get(a).abs());
            }).mapToInt(Integer::intValue).toArray();
        }
        cached.valid = true;
        rowActivity[rowId] = cached;
        return cached;
    }

    private void updateEndpoint(int variable, boolean minimum, BigInteger old, BigInteger next) {
        BigInteger delta = null;
        for (var occurrence : incident.get(boundKey(variable, minimum))) {
            int rowId = occurrence.row;
            charge();
            if (rowPool != null && !rowPool.active(rowId)) continue;
            RowActivity cached = rowActivity[rowId];
            if (cached != null && cached.valid) {
                BigInteger a = occurrence.coefficient;
                // The endpoint change is shared by every incident row. Build
                // it lazily, since sleeping or uninitialized rows need no sum.
                if (delta == null) delta = old == null ? next : next == null ? old.negate() : next.subtract(old);
                if (old == null || next == null) {
                    cached.infiniteXor ^= variable;
                    cached.infinities += next == null ? 1 : -1;
                }
                integerCost(a, delta);
                cached.minimum = cached.minimum.add(a.multiply(delta));
            }
            enqueue(rowId);
        }
    }

    private void propagate(int rowId) {
        var row = rows.get(rowId);
        RowActivity cached = activity(rowId);
        BigInteger sum = cached.minimum;
        int infinities = cached.infinities, infinite = cached.infiniteXor;
        if (infinities == 0 && sum.compareTo(row.upper()) > 0) {
            conflict = antecedents(row, -1, sum.subtract(row.upper()).subtract(BigInteger.ONE), trail.size());
            return;
        }
        if (infinities > 1) return;
        BigInteger residual = row.upper().subtract(sum);
        // No variable can cross its opposite domain endpoint while the row
        // has at least this much slack. In particular, wide sparse rows need
        // not scan all their terms after every unrelated integer split.
        if (infinities == 0 && cached.maximumChange != null &&
                residual.compareTo(cached.maximumChange) >= 0)
            return;
        if (infinities == 0 && cached.booleanOrder != null) {
            BigInteger maximumChange = BigInteger.ZERO;
            for (int id : cached.booleanOrder) {
                charge();
                if (!low[id].equals(high[id])) {
                    maximumChange = row.terms().get(id).abs();
                    break;
                }
            }
            // Every unfixed domain in this mode is [0,1]. Re-read it after
            // backtracking: a bound can tighten only if its exact one-step
            // contribution exceeds the row's remaining slack.
            if (residual.compareTo(maximumChange) >= 0) return;
        }
        // Propagate using a frozen set of bounds; new implications are placed
        // on the queue, so none of their explanations can refer to themselves.
        int prefix = trail.size();
        var candidates = new ArrayList<Literal>();
        var explanations = new ArrayList<LinearReason>();
        for (var term : row.terms().entrySet()) {
            charge();
            int id = term.getKey();
            BigInteger a = term.getValue();
            if (a.signum() == 0 || infinities == 1 && id != infinite) continue;
            BigInteger magnitude = a.abs();
            // A finite row's residual is nonnegative. If this variable's
            // entire domain fits, its implied bound is already true. Keep
            // the same scan charge, but do not divide or allocate a literal.
            if (infinities == 0 && high[id] != null &&
                    (low[id].equals(high[id]) || residual.compareTo(magnitude.multiply(high[id].subtract(low[id]))) >= 0))
                continue;
            BigInteger endpoint = a.signum() > 0 ? low[id] : high[id];
            // floor((b - (sum - a*x))/|a|) is x +/- floor(residual/|a|).
            // Reuse the frozen residual, even when x is far beyond 64 bits.
            BigInteger distance = floor(residual, magnitude);
            Literal next = a.signum() > 0 ? new Literal(id, false, endpoint.add(distance)) :
                    new Literal(id, true, endpoint == null ? distance.negate() : endpoint.subtract(distance));
            if (truth(next) != 1) {
                candidates.add(next);
                // The opposite bound must violate this integer row by at
                // least one. Spend only the surplus on relaxing its reason.
                BigInteger slack = magnitude.multiply(distance.add(BigInteger.ONE)).subtract(residual).subtract(BigInteger.ONE);
                explanations.add(new LinearReason(row, id, slack, prefix, rowId));
            }
        }
        for (int i = 0; i < candidates.size() && conflict == null; i++) tighten(candidates.get(i), null, explanations.get(i));
    }

    private List<Literal> antecedents(ExactLinearProgram.Constraint row, int except, BigInteger slack, int prefix) {
        var result = new ArrayList<Literal>();
        for (var term : row.terms().entrySet()) {
            charge();
            int id = term.getKey();
            if (id == except || term.getValue().signum() == 0) continue;
            boolean minimum = term.getValue().signum() > 0;
            var literal = new Literal(id, minimum, endpoint(id, minimum, prefix));
            if (literal.value == null) throw new IllegalStateException("Infinite implication endpoint");
            if (rootTrue(literal)) continue;
            if (slack.signum() > 0) {
                BigInteger magnitude = term.getValue().abs();
                BigInteger distance = slack.divide(magnitude);
                BigInteger root = minimum ? levelZeroLow[id] : levelZeroHigh[id];
                if (root != null) distance = distance.min(literal.value.subtract(root).abs());
                if (distance.signum() > 0) {
                    integerCost(distance, magnitude);
                    slack = slack.subtract(distance.multiply(magnitude));
                    literal = new Literal(id, minimum, minimum ? literal.value.subtract(distance) : literal.value.add(distance));
                    relaxedReasons++;
                }
            }
            if (!rootTrue(literal)) result.add(literal);
        }
        return result;
    }

    private BigInteger endpoint(int variable, boolean minimum, int prefix) {
        // A row's implications all use the same frozen trail prefix. Reading
        // live bounds during conflict analysis could introduce a later bound,
        // including the very implication whose reason is being explained.
        List<Integer> indices = boundTrail.get(boundKey(variable, minimum));
        int size = indices.size();
        if (size == 0) return minimum ? rootLow[variable] : rootHigh[variable];
        if (indices.get(size - 1) < prefix) return trail.get(indices.get(size - 1)).literal.value;
        int left = 0, right = size;
        while (left < right) {
            charge();
            int middle = (left + right) >>> 1;
            if (indices.get(middle) < prefix) left = middle + 1;
            else right = middle;
        }
        return left == 0 ? (minimum ? rootLow[variable] : rootHigh[variable]) : trail.get(indices.get(left - 1)).literal.value;
    }

    private List<Literal> explain(LinearReason linear) {
        explainedReasons++;
        return antecedents(linear.row, linear.except, linear.slack, linear.prefix);
    }

    private List<Literal> reason(Change change) {
        if (change.reason == null && change.linear != null) {
            List<Literal> reason = explain(change.linear);
            long bytes = 96L * reason.size();
            reserve(bytes);
            change.bytes += bytes;
            change.reason = List.copyOf(reason);
        }
        return change.reason;
    }

    private void propagate(Nogood clause) {
        // A forbidden conjunction is inactive while two terms are not true.
        // Test its existing watches first; unrelated bounds never enqueue it.
        int first = -1, second = -1;
        if (clause.first.term >= 0) {
            charge();
            if (truth(clause.terms.get(clause.first.term)) != 1) first = clause.first.term;
        }
        if (clause.second.term >= 0) {
            charge();
            if (truth(clause.terms.get(clause.second.term)) != 1) {
                if (first < 0) first = clause.second.term;
                else second = clause.second.term;
            }
        }
        if (second >= 0) return;
        for (int i = 0; i < clause.terms.size() && second < 0; i++) {
            if (i == clause.first.term || i == clause.second.term) continue;
            charge();
            if (truth(clause.terms.get(i)) != 1) {
                if (first < 0) first = i;
                else second = i;
            }
        }
        // With fewer than two non-true terms, retain a true anchor as the
        // second watch. Undo events also wake watches: general integer bounds
        // can change a false term into an unknown one on backtrack.
        watch(clause.first, first >= 0 ? first : clause.terms.isEmpty() ? -1 : 0);
        watch(clause.second, second >= 0 ? second : clause.terms.size() < 2 ? -1 : first <= 0 ? 1 : 0);
        if (second >= 0) return;
        clause.used = conflicts;
        if (first < 0) {
            conflict = clause.terms;
            return;
        }
        Literal remaining = clause.terms.get(first);
        if (truth(remaining) == 0) return;
        var why = new ArrayList<>(clause.terms);
        why.remove(first);
        tighten(remaining.opposite(), why);
    }

    private void watch(Watch watch, int term) {
        if (watch.term == term) return;
        charge();
        if (watch.term >= 0) {
            int variable = watch.clause.terms.get(watch.term).variable;
            if (watch.previous == null) watches[variable] = watch.next;
            else watch.previous.next = watch.next;
            if (watch.next != null) watch.next.previous = watch.previous;
        }
        watch.term = term;
        watch.previous = watch.next = null;
        if (term >= 0) {
            int variable = watch.clause.terms.get(term).variable;
            watch.next = watches[variable];
            if (watch.next != null) watch.next.previous = watch;
            watches[variable] = watch;
        }
    }

    private void changed(int variable) {
        for (Watch watch = watches[variable]; watch != null; watch = watch.next) {
            charge();
            schedule(watch.clause);
        }
    }

    private void schedule(Nogood clause) {
        if (clause.pending) return;
        clause.pending = true;
        clause.pendingPrevious = lastPendingClause;
        if (lastPendingClause == null) pendingClause = clause;
        else lastPendingClause.pendingNext = clause;
        lastPendingClause = clause;
    }

    private void unschedule(Nogood clause) {
        if (!clause.pending) return;
        if (clause.pendingPrevious == null) pendingClause = clause.pendingNext;
        else clause.pendingPrevious.pendingNext = clause.pendingNext;
        if (clause.pendingNext == null) lastPendingClause = clause.pendingPrevious;
        else clause.pendingNext.pendingPrevious = clause.pendingPrevious;
        clause.pending = false;
        clause.pendingPrevious = clause.pendingNext = null;
    }

    private void addClause(List<Literal> terms, int lbd) {
        Nogood clause = new Nogood(List.copyOf(terms), lbd, conflicts);
        clauses.add(clause);
        schedule(clause);
    }

    private void tighten(Literal literal, List<Literal> reason) {
        tighten(literal, reason, null);
    }

    private void tighten(Literal literal, List<Literal> reason, LinearReason linear) {
        charge();
        if (truth(literal) == 1) return;
        if (truth(literal) == 0) {
            if (linear != null) reason = explain(linear);
            if (reason == null) throw new IllegalStateException("Contradictory integer split");
            var why = new ArrayList<>(reason);
            why.add(literal.opposite());
            conflict = why;
            return;
        }
        long bytes = 192L + 96L * (reason == null ? 0 : reason.size()) + (linear == null ? 0 : 128);
        reserve(bytes);
        BigInteger old = literal.minimum ? low[literal.variable] : high[literal.variable];
        trail.add(new Change(literal, old, level, reason == null ? null : List.copyOf(reason), linear, bytes));
        if (linear != null) {
            lazyReasons++;
            if (rowPool != null) rowPool.pin(linear.rowId);
        }
        boundTrail.get(boundKey(literal.variable, literal.minimum)).add(trail.size() - 1);
        if (level == 0) {
            rootVersion++;
            BigInteger beforeLow = levelZeroLow[literal.variable], beforeHigh = levelZeroHigh[literal.variable];
            if (literal.minimum) levelZeroLow[literal.variable] = literal.value;
            else levelZeroHigh[literal.variable] = literal.value;
            BigInteger afterLow = levelZeroLow[literal.variable], afterHigh = levelZeroHigh[literal.variable];
            // Count multiplicative domain shrinkage, not every +1 in a huge
            // feedback domain. Otherwise a stalled propagation loop looks
            // permanently productive to the portfolio scheduler.
            if (beforeHigh != null) rootProgress += Math.max(0,
                    beforeHigh.subtract(beforeLow).bitLength() - afterHigh.subtract(afterLow).bitLength());
            else if (afterHigh != null) rootProgress++;
            else rootProgress += Math.max(0, afterLow.abs().bitLength() - beforeLow.abs().bitLength());
        }
        if (literal.minimum) low[literal.variable] = literal.value;
        else high[literal.variable] = literal.value;
        // Only the endpoint contributing to a row's minimum can strengthen
        // propagation. Waking both directions used to rescan unrelated rows.
        updateEndpoint(literal.variable, literal.minimum, old, literal.value);
        changed(literal.variable);
    }

    private void analyze() {
        // At most one strongest literal per variable and bound direction. The
        // cache is request-private and never consulted after a backjump/restart.
        long bytes = 256L + 384L * low.length;
        reserve(bytes);
        try {
            analyze(new ConflictFrontier());
        } finally {
            memory -= bytes;
            budget.release(bytes);
        }
    }

    private void analyze(ConflictFrontier frontier) {
        conflicts++;
        frontier.addAll(conflict);
        int highest;
        while (true) {
            highest = frontier.highestLevel();
            if (highest == 0 || frontier.asserting()) break;
            // Trail levels are monotone and each source changes one bound.
            // With one strongest literal per direction, only this one frontier
            // entry can originate at the latest implication being resolved.
            int last = frontier.removeLatest();
            Change change = trail.get(last);
            List<Literal> reason = reason(change);
            if (reason == null) throw new IllegalStateException("Integer conflict has multiple unresolved decisions");
            frontier.addAll(reason);
        }
        if (highest == 0) {
            remember(List.of());
            infeasible = true;
            finish("proven_infeasible");
            return;
        }
        int back = 0;
        BitSet levels = new BitSet();
        for (ExplainedLiteral explained : frontier.terms.values()) {
            charge();
            Literal literal = explained.literal;
            int at = trail.get(explained.source).level;
            levels.set(at);
            if (at != highest) back = Math.max(back, at);
            activity[literal.variable] += increment;
        }
        List<Literal> literals = frontier.literals();
        remember(literals);
        reserve(320L + 96L * literals.size());
        addClause(literals, levels.cardinality());
        learnedProgress++;
        if (back + 1 < level) jumps++;
        backtrack(back);
        conflict = null;
        increment /= 0.95;
        if (increment > 1e80) {
            for (int i = 0; i < activity.length; i++) activity[i] *= 1e-80;
            increment *= 1e-80;
        }
        if (clauses.size() > 512 && conflicts % 64 == 0) {
            // Reasons are immutable antecedent lists, not pointers into this pool.
            var discard = clauses.stream().filter(c -> c.lbd > 2).sorted(Comparator.comparingLong(c -> c.used)).limit(clauses.size() / 4).toList();
            for (Nogood clause : discard) {
                clauses.remove(clause);
                watch(clause.first, -1);
                watch(clause.second, -1);
                unschedule(clause);
                long bytes = 320L + 96L * clause.terms.size();
                memory -= bytes;
                budget.release(bytes);
            }
        }
    }

    private int source(Literal literal) {
        // Bounds tighten monotonically along each live trail. Find the first
        // implication that entailed even a relaxed literal, not the latest one
        // (which could create a circular explanation).
        List<Integer> indices = boundTrail.get(boundKey(literal.variable, literal.minimum));
        int left = 0, right = indices.size();
        while (left < right) {
            charge();
            int middle = (left + right) >>> 1;
            Literal candidate = trail.get(indices.get(middle)).literal;
            boolean implies = literal.minimum ? candidate.value.compareTo(literal.value) >= 0 : candidate.value.compareTo(literal.value) <= 0;
            if (implies) right = middle;
            else left = middle + 1;
        }
        if (left < indices.size()) return indices.get(left);
        throw new IllegalStateException("Lost integer implication antecedent");
    }

    private static int boundKey(int variable, boolean minimum) {
        return variable * 2 + (minimum ? 1 : 0);
    }

    private void backtrack(int to) {
        while (!trail.isEmpty() && trail.get(trail.size() - 1).level > to) {
            charge();
            Change change = trail.remove(trail.size() - 1);
            if (rowPool != null && change.linear != null) rowPool.unpin(change.linear.rowId);
            if (savedValues != null && low[change.literal.variable].equals(high[change.literal.variable])) savedValues[change.literal.variable] = low[change.literal.variable];
            var indices = boundTrail.get(boundKey(change.literal.variable, change.literal.minimum));
            indices.remove(indices.size() - 1);
            if (change.literal.minimum) low[change.literal.variable] = change.old;
            else high[change.literal.variable] = change.old;
            updateEndpoint(change.literal.variable, change.literal.minimum, change.literal.value, change.old);
            changed(change.literal.variable);
            memory -= change.bytes;
            budget.release(change.bytes);
        }
        level = to;
    }

    private boolean rootTrue(Literal literal) {
        // Level-zero consequences survive every backtrack. Do not copy their
        // literals into each subsequent reason. Certificates still use the
        // ORIGINAL domains and independently reconstruct these consequences.
        return literal.minimum ? levelZeroLow[literal.variable].compareTo(literal.value) >= 0 :
                levelZeroHigh[literal.variable] != null && levelZeroHigh[literal.variable].compareTo(literal.value) <= 0;
    }

    private int truth(Literal literal) {
        int id = literal.variable;
        if (literal.minimum) {
            if (low[id].compareTo(literal.value) >= 0) return 1;
            if (high[id] != null && high[id].compareTo(literal.value) < 0) return 0;
        } else {
            if (high[id] != null && high[id].compareTo(literal.value) <= 0) return 1;
            if (low[id].compareTo(literal.value) > 0) return 0;
        }
        return -1;
    }

    private boolean satisfies(BigInteger[] value) {
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                charge();
                integerCost(term.getValue(), value[term.getKey()]);
                sum = sum.add(term.getValue().multiply(value[term.getKey()]));
            }
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        // Covering relaxations may admit assignments that original-model
        // nogoods exclude. Checking rows alone would accept such a low point
        // before its unresolved bound literals had forced a decision.
        for (var clause : imported) {
            boolean forbidden = true;
            for (var row : clause.assumptions()) {
                charge();
                var term = row.terms().entrySet().iterator().next();
                if (term.getValue().multiply(value[term.getKey()]).compareTo(row.upper()) > 0) {
                    forbidden = false;
                    break;
                }
            }
            if (forbidden) return false;
        }
        return true;
    }

    private void remember(List<Literal> literals) {
        if (learned.size() >= 128 && !retainProof) return;
        reserve(128L + 144L * literals.size());
        var clause = new CountConflict(literals.stream().map(Literal::row).toList());
        if (learned.size() < 128) learned.add(clause);
        if (retainProof) proofSteps.add(clause);
    }

    private void enqueue(int row) {
        if (rowPool != null && !rowPool.active(row)) return;
        if (!queued.get(row)) {
            queued.set(row);
            queue.addLast(row);
        }
    }

    private static BigInteger floor(BigInteger n, BigInteger d) {
        if (d.equals(BigInteger.ONE)) return n;
        if (n.bitLength() <= 63 && d.bitLength() <= 63)
            return BigInteger.valueOf(Math.floorDiv(n.longValue(), d.longValue()));
        BigInteger[] qr = n.divideAndRemainder(d);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }

    private void charge() {
        workTicks += budget.operation(PlanningBudget.Operation.SCAN, 0);
        work = PlanningBudget.units(workTicks);
    }

    private void integerCost(BigInteger a, BigInteger b) {
        workTicks += budget.operation(PlanningBudget.Operation.INTEGER, Math.max(a.bitLength(), b.bitLength()));
        work = PlanningBudget.units(workTicks);
    }

    boolean paused() {
        return paused;
    }

    void resume(long quantum) {
        if (!paused || quantum <= 0) throw new IllegalStateException("Integer search is not paused");
        if (budget.remainingWork() == 0) budget.check();
        allowance = work + Math.min(quantum, budget.remainingWork());
        paused = false;
        complete = false;
        certificate = null;
    }

    /** Search feedback, never a proof or a reason to exclude a model. */
    long progress() {
        return rootProgress + learnedProgress + fixedPeak;
    }

    long rootVersion() {
        return rootVersion;
    }

    BigInteger[] rootLower() {
        return levelZeroLow.clone();
    }

    BigInteger[] rootUpper() {
        return levelZeroHigh.clone();
    }

    private void reserve(long bytes) {
        if (!budget.tryReserve(bytes)) throw new Stop();
        memory += bytes;
    }

    private boolean finish(String detail) {
        if (!complete && retainProof) {
            var scope = new ArrayList<>(rows.subList(0, originalRows));
            for (int i = 0; i < low.length; i++) {
                scope.add(new Literal(i, true, rootLow[i]).row());
                if (rootHigh[i] != null) scope.add(new Literal(i, false, rootHigh[i]).row());
            }
            certificate = differenceProof != null ? differenceProof : CountProof.certificate("lcg:" + detail, low.length, scope, proofSteps, null, infeasible);
            if (differenceProof == null && !derivedRows.isEmpty()) certificate = new CountProof.Certificate(
                    certificate.scope(), certificate.variables(), certificate.axioms(), certificate.forbidden(),
                    certificate.farkas(), certificate.closed(), derivedRows);
            if (budget.proofJournal() != null) budget.proofJournal().add(certificate);
        }
        complete = true;
        budget.note("count_lcg", detail + "; decisions=" + decisions + "; conflicts=" + conflicts + "; backjumps=" + jumps +
                "; restarts=" + restarts + "; relaxed_reasons=" + relaxedReasons + "; lazy_reasons=" + lazyReasons +
                "; explained_reasons=" + explainedReasons + "; work=" + work);
        if (learnedRelaxation) budget.note("count_lp_learning", "calls=" + relaxationCalls + "; cuts=" + relaxationCuts +
                "; proposal_misses=" + relaxationMisses + "; numerical_work=" + relaxationNumericalWork);
        if (!cycleRows.isEmpty()) budget.note("count_lcg_cycles", "cuts=" + cycleRows.size() + "; work=" + cycleWork);
        if (reliability != null) budget.note("count_lcg_reliability", reliability.diagnostic());
        if (rowPool != null) budget.note("count_lcg_pool", rowPool.diagnostic());
        return true;
    }

    BigInteger[] counts() {
        return counts == null ? null : counts.clone();
    }

    boolean infeasible() {
        return infeasible;
    }

    CountProof.Certificate certificate() {
        return certificate;
    }

    List<CountConflict> learnedConflicts() {
        return List.copyOf(learned);
    }

    /** Import only a proved, scoped conjunction. Decision bounds must remain literal guards. */
    boolean learn(CountConflict value) {
        if (complete && !paused || imported.size() >= 64 || imported.contains(value) || value.assumptions().size() > 16) return false;
        List<Literal> literals = new ArrayList<>();
        for (var row : value.assumptions()) {
            charge();
            if (row.terms().size() != 1) return false;
            var term = row.terms().entrySet().iterator().next();
            int id = term.getKey();
            if (id < 0 || id >= low.length || !term.getValue().abs().equals(BigInteger.ONE)) return false;
            boolean minimum = term.getValue().signum() < 0;
            var literal = new Literal(id, minimum, minimum ? row.upper().negate() : row.upper());
            // Only permanent level-zero facts may simplify an imported
            // clause; current decisions can disappear on the next backjump.
            if (rootTrue(literal.opposite())) return false;
            if (!rootTrue(literal)) literals.add(literal);
        }
        if (retainProof && !checkImportedClause(value.assumptions().stream().map(CountProof::row).toList())) return false;
        long bytes = 448L + 512L * value.assumptions().size();
        if (bytes > budget.availableBytes() / 8 || !budget.tryReserve(bytes)) return false;
        memory += bytes;
        imported.add(value);
        if (retainProof) proofSteps.add(value);
        addClause(literals, 0);
        return true;
    }

    /** Imported explanations are independently replayed from the original axioms, never trusted as new axioms. */
    private boolean checkImportedClause(List<CountProof.Row> clause) {
        if (Arrays.stream(rootLow).anyMatch(value -> value.signum() < 0)) return false;
        var scope = new ArrayList<>(rows.subList(0, originalRows).stream().map(CountProof::row).toList());
        for (int i = 0; i < rootLow.length; i++) {
            scope.add(CountProof.row(new Literal(i, true, rootLow[i]).row()));
            if (rootHigh[i] != null) scope.add(CountProof.row(new Literal(i, false, rootHigh[i]).row()));
        }
        long before = budget.threadWork();
        var proof = CountAffineConflictProof.clause(rootLow.length, scope, clause);
        CountProof.Verdict verdict = CountProof.verify(proof, Math.min(16384, budget.remainingWork() / 32), budget::charge);
        workTicks += (budget.threadWork() - before) * PlanningBudget.WORK_SCALE;
        work = PlanningBudget.units(workTicks);
        return verdict == CountProof.Verdict.VERIFIED;
    }

    /** Only the owner's certified, unconditional original-model consequences may enter here. */
    boolean learn(ExactLinearProgram.Constraint row) {
        if (complete && !paused || importedRows.size() >= 64 || row.terms().size() > 64 ||
                row.upper().bitLength() > 2048 || importedRows.contains(row) || rows.contains(row))
            return false;
        if (learnedRelaxation && rows.size() >= rowActivity.length) return false;
        CountConflict explanation = null;
        if (retainProof) {
            var negative = CountAffineProof.opposite(CountProof.row(row));
            if (!checkImportedClause(List.of(negative))) return false;
            explanation = new CountConflict(List.of(new ExactLinearProgram.Constraint(negative.terms(), negative.upper())));
        }
        for (var term : row.terms().entrySet()) {
            charge();
            if (term.getKey() < 0 || term.getKey() >= low.length || term.getValue().bitLength() > 2048) return false;
        }
        long bytes = 512L + 512L * row.terms().size();
        if (importedRows.isEmpty() && !learnedRelaxation) bytes += 16L * rows.size();
        int capacity = rows.size() >= rowActivity.length ? rows.size() + 16 : rowActivity.length;
        bytes += 32L + 8L * (capacity - rowActivity.length);
        if (bytes > budget.availableBytes() / 8 || !budget.tryReserve(bytes)) return false;
        memory += bytes;
        if (importedRows.isEmpty() && !learnedRelaxation) rows = new ArrayList<>(rows);
        int id = rows.size();
        if (capacity != rowActivity.length) rowActivity = Arrays.copyOf(rowActivity, capacity);
        rows.add(row);
        importedRows.add(row);
        if (explanation != null) proofSteps.add(explanation);
        if (relaxationKnown != null) relaxationKnown.add(row);
        if (rowPool != null) rowPool.added(id, decisions);
        for (var term : row.terms().entrySet()) {
            charge();
            if (term.getValue().signum() != 0)
                incident.get(boundKey(term.getKey(), term.getValue().signum() > 0)).add(new Incidence(id, term.getValue()));
        }
        enqueue(id);
        return true;
    }

    @Override
    public void close() {
        finiteHint = null;
        if (lpSession != null) lpSession.close();
        if (reliability != null) reliability.close();
        if (rowPool != null) rowPool.close();
        budget.release(memory);
        memory = 0;
    }
}
