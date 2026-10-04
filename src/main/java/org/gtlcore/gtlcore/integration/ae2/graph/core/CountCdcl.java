package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Finite Boolean-domain CDCL with lazy explanations of exact weighted rows.
 * The algorithmic components follow the SAT/LCG architecture in OR-Tools and
 * Chuffed: two watched literals, first-UIP learning, phase saving, decaying
 * activity, Luby restarts and retention of useful low-LBD clauses.
 * Domain offsets and weighted propagation use mathematical integers.
 */
final class CountCdcl implements AutoCloseable {

    enum Branching {
        ACTIVITY,
        LEARNING_RATE
    }

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private static final class Clause {

        final int[] literals;
        final boolean learned;
        int lbd;
        long used;
        boolean deleted;

        Clause(int[] literals, boolean learned, int lbd) {
            this.literals = literals;
            this.learned = learned;
            this.lbd = lbd;
        }
    }

    private static final class Row {

        final int[] literals;
        final BigInteger[] weights;
        final BigInteger capacity;
        final ExactLinearProgram.Constraint source;
        final int proofIndex;
        BigInteger spent = BigInteger.ZERO;
        int propagated;

        Row(int[] literals, BigInteger[] weights, BigInteger capacity, ExactLinearProgram.Constraint source, int proofIndex) {
            this.literals = literals;
            this.weights = weights;
            this.capacity = capacity;
            this.source = source;
            this.proofIndex = proofIndex;
        }
    }

    private record Occurrence(int row, int term) {}

    /** Only assignments before this prefix may explain a propagated literal. */
    private record LazyReason(Row row, int forced, int prefix) {}

    private final PlanningBudget budget;
    private final List<ExactLinearProgram.Constraint> original;
    private final BigInteger[] lower, upper;
    private final int[] values, levels, phase;
    private final int[][] reasons;
    private final LazyReason[] lazyReasons;
    private final int[] positions;
    private final double[] activity, polarity;
    private final Branching branching;
    private final double[] learningRate;
    private final int[] assignedAt, participated, lastParticipation;
    // Binary edges are two-int arrays, sharing the watch order with longer
    // clauses. Processing a separate binary queue first changes the conflict
    // frontier of mixed PB/order encodings and can cause severe regressions.
    private final List<List<Object>> watches = new ArrayList<>();
    private final List<List<Occurrence>> affected = new ArrayList<>();
    private final List<Clause> clauses = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<Integer> trail = new ArrayList<>();
    private final Deque<Integer> pending = new ArrayDeque<>();
    private final BitSet queued = new BitSet();
    private final List<CountConflict> learned = new ArrayList<>(), proofSteps = new ArrayList<>();
    private final List<CountProof.Combination> weightedSteps = new ArrayList<>();
    private final List<ExactLinearProgram.Constraint> weightedRows = new ArrayList<>();
    private final Set<ExactLinearProgram.Constraint> knownRows = new HashSet<>();
    private Row weightedConflict;
    private long weightedWork;
    private int repairedReasons;
    private long allowance;
    private List<ExactLinearProgram.Constraint> proofScope;
    private int[] conflict;
    private BigInteger[] counts;
    private int cursor, head, level, decisions, conflicts, restarts, nextRestart = 32, removed, minimized;
    private int recursivelyMinimized;
    private long minimizationWork;
    private double increment = 1.0;
    private long memory, work;
    private long activityUpdates, materializedReasons;
    private boolean complete, infeasible, memoryLimit;
    private boolean retaining, paused;

    CountCdcl(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
              PlanningBudget budget, long maxWork) {
        this(rows, lower, upper, budget, maxWork, Branching.ACTIVITY);
    }

    CountCdcl(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
              PlanningBudget budget, long maxWork, Branching branching) {
        this.original = rows;
        this.lower = lower.clone();
        this.upper = upper.clone();
        this.budget = budget;
        this.branching = branching;
        allowance = Math.min(maxWork, budget.remainingWork() / 8);
        values = new int[lower.length];
        levels = new int[lower.length];
        phase = new int[lower.length];
        reasons = new int[lower.length][];
        lazyReasons = new LazyReason[lower.length];
        positions = new int[lower.length];
        activity = new double[lower.length];
        polarity = new double[lower.length];
        learningRate = new double[lower.length];
        assignedAt = new int[lower.length];
        participated = new int[lower.length];
        lastParticipation = new int[lower.length];
        Arrays.fill(values, -1);
        Arrays.fill(phase, -1);
        long terms = rows.stream().mapToLong(r -> r.terms().size()).sum();
        if (lower.length > 1024 || rows.size() > 4096 || terms > 65536 || allowance < 1024) {
            complete = true;
            return;
        }
        for (int i = 0; i < lower.length; i++) if (!lower[i].equals(upper[i]) &&
                (upper[i] == null || !upper[i].subtract(lower[i]).equals(BigInteger.ONE))) {
                    complete = true;
                    return;
                }
        // Includes all live lazy explanations, each containing at most n literals.
        long bytes = 2048 + 8L * lower.length * lower.length + 416L * lower.length + 160L * terms + 192L * rows.size();
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        for (int i = 0; i < lower.length; i++) {
            affected.add(new ArrayList<>());
            watches.add(new ArrayList<>());
            watches.add(new ArrayList<>());
        }
        if (budget.proofJournal() != null) {
            proofScope = new ArrayList<>(rows);
            for (int i = 0; i < lower.length; i++) {
                proofScope.add(bound(i, lower[i], true));
                proofScope.add(bound(i, upper[i], false));
            }
        }
    }

    /** Preserve queues and learned clauses across cooperative portfolio slices. */
    CountCdcl retained() {
        retaining = true;
        return this;
    }

    boolean paused() {
        return paused;
    }

    void resume(long quantum) {
        if (!retaining || !paused || quantum <= 0) throw new IllegalStateException("Boolean search is not paused");
        if (budget.remainingWork() == 0) budget.check();
        allowance = work + Math.min(quantum, budget.remainingWork());
        paused = false;
    }

    long progress() {
        return conflicts + 4L * weightedRows.size();
    }

    boolean step() {
        if (complete || paused) return true;
        // Local handoffs happen only between complete propagation/analysis
        // operations. Stopping inside a watched-list update would lose work
        // and make the retained continuation unsound.
        if (retaining && work >= allowance) {
            paused = true;
            return true;
        }
        try {
            charge();
            if (conflict != null) {
                analyze();
                return complete;
            }
            if (cursor < original.size()) {
                compile(original.get(cursor), cursor++);
                return false;
            }
            if (head < trail.size()) {
                propagateWatches(trail.get(head++) ^ 1);
                return false;
            }
            if (!pending.isEmpty()) {
                int row = pending.removeFirst();
                queued.clear(row);
                propagate(rows.get(row));
                return false;
            }
            if (conflicts >= nextRestart && level > 0) {
                backtrack(0);
                restarts++;
                nextRestart = conflicts + 32 * luby(restarts + 1);
                return false;
            }
            int best = -1;
            for (int i = 0; i < values.length; i++) {
                charge();
                if (!lower[i].equals(upper[i]) && values[i] < 0 && (best < 0 || score(i) > score(best))) best = i;
            }
            if (best >= 0) {
                level++;
                decisions++;
                assign(2 * best + (phase[best] >= 0 ? phase[best] : polarity[best] >= 0 ? 1 : 0), null);
                return false;
            }
            counts = lower.clone();
            for (int i = 0; i < counts.length; i++) if (values[i] == 1) counts[i] = counts[i].add(BigInteger.ONE);
            for (var row : original) {
                BigInteger value = BigInteger.ZERO;
                for (var term : row.terms().entrySet()) {
                    charge();
                    value = value.add(term.getValue().multiply(counts[term.getKey()]));
                }
                if (value.compareTo(row.upper()) > 0) throw new IllegalStateException("CDCL witness violates original row");
            }
            return finish("verified_witness");
        } catch (Stop stop) {
            counts = null;
            return finish(memoryLimit ? "memory_limit" : "work_limit");
        }
    }

    private void compile(ExactLinearProgram.Constraint input, int proofIndex) {
        knownRows.add(input);
        List<Integer> literals = new ArrayList<>();
        List<BigInteger> weights = new ArrayList<>();
        BigInteger capacity = input.upper(), sum = BigInteger.ZERO, smallest = null;
        for (var term : input.terms().entrySet()) {
            charge();
            int id = term.getKey();
            BigInteger coefficient = term.getValue();
            capacity = capacity.subtract(coefficient.multiply(lower[id]));
            if (lower[id].equals(upper[id]) || coefficient.signum() == 0) continue;
            if (coefficient.signum() < 0) capacity = capacity.subtract(coefficient);
            BigInteger weight = coefficient.abs();
            literals.add(2 * id + (coefficient.signum() > 0 ? 1 : 0));
            weights.add(weight);
            sum = sum.add(weight);
            smallest = smallest == null ? weight : smallest.min(weight);
            activity[id] += 1.0 / input.terms().size();
            polarity[id] -= coefficient.signum() / (double) input.terms().size();
        }
        if (capacity.signum() < 0) {
            conflict = new int[0];
            return;
        }
        if (sum.compareTo(capacity) <= 0) return;
        int[] ids = literals.stream().mapToInt(Integer::intValue).toArray();
        if (sum.subtract(smallest).compareTo(capacity) <= 0) {
            for (int i = 0; i < ids.length; i++) ids[i] ^= 1;
            attach(new Clause(ids, false, 0), true);
            return;
        }
        // Decreasing weights let propagation stop as soon as no remaining
        // unassigned term can exceed slack. Activities are updated by column.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) order.add(i);
        order.sort(Comparator.<Integer, BigInteger>comparing(weights::get).reversed());
        int[] sorted = new int[ids.length];
        BigInteger[] coefficients = new BigInteger[ids.length];
        int r = rows.size();
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = ids[order.get(i)];
            coefficients[i] = weights.get(order.get(i));
            affected.get(sorted[i] / 2).add(new Occurrence(r, i));
        }
        Row row = new Row(sorted, coefficients, capacity, input, proofIndex);
        for (int i = 0; i < sorted.length; i++) if (value(sorted[i]) == 1) row.spent = row.spent.add(coefficients[i]);
        rows.add(row);
        enqueue(r);
    }

    private int value(int literal) {
        int v = values[literal / 2];
        return v < 0 ? -1 : v == (literal & 1) ? 1 : 0;
    }

    private double score(int id) {
        return branching == Branching.ACTIVITY ? activity[id] : learningRate[id] + 1e-8 * Math.min(activity[id], 1000);
    }

    private void assign(int literal, int[] reason) {
        int id = literal / 2;
        if (values[id] >= 0) {
            if (value(literal) == 0) conflict = reason;
            return;
        }
        values[id] = literal & 1;
        assignedAt[id] = conflicts;
        participated[id] = 0;
        phase[id] = values[id];
        levels[id] = level;
        reasons[id] = reason;
        positions[id] = trail.size();
        trail.add(literal);
        for (var occurrence : affected.get(id)) {
            charge();
            Row row = rows.get(occurrence.row);
            if (value(row.literals[occurrence.term]) == 1) {
                row.spent = row.spent.add(row.weights[occurrence.term]);
                activityUpdates++;
                enqueue(occurrence.row);
            }
        }
    }

    private void attach(Clause clause, boolean initialize) {
        if (clause.literals.length != 2) clauses.add(clause);
        int live = 0;
        for (int i = 0; i < clause.literals.length; i++) if (value(clause.literals[i]) != 0) {
            int v = clause.literals[live];
            clause.literals[live++] = clause.literals[i];
            clause.literals[i] = v;
        }
        if (clause.literals.length == 2) {
            watches.get(clause.literals[0]).add(clause.literals);
            watches.get(clause.literals[1]).add(clause.literals);
        } else if (clause.literals.length > 2) {
            watches.get(clause.literals[0]).add(clause);
            watches.get(clause.literals[1]).add(clause);
        }
        if (initialize) {
            if (live == 0) conflict = clause.literals;
            else if (live == 1) assign(clause.literals[0], clause.literals);
        }
    }

    private void propagateWatches(int falseLiteral) {
        List<Object> list = watches.get(falseLiteral);
        for (int at = 0; at < list.size();) {
            charge();
            Object entry = list.get(at);
            if (entry instanceof int[] pair) {
                if (pair[0] == falseLiteral) {
                    pair[0] = pair[1];
                    pair[1] = falseLiteral;
                }
                at++;
                if (value(pair[0]) == 0) {
                    conflict = pair;
                    return;
                }
                if (value(pair[0]) < 0) assign(pair[0], pair);
                continue;
            }
            Clause clause = (Clause) entry;
            int[] a = clause.literals;
            if (a[0] == falseLiteral) {
                a[0] = a[1];
                a[1] = falseLiteral;
            }
            if (value(a[0]) == 1) {
                at++;
                continue;
            }
            int replacement = 2;
            while (replacement < a.length && value(a[replacement]) == 0) {
                charge();
                replacement++;
            }
            if (replacement < a.length) {
                a[1] = a[replacement];
                a[replacement] = falseLiteral;
                list.set(at, list.get(list.size() - 1));
                list.remove(list.size() - 1);
                watches.get(a[1]).add(clause);
            } else {
                at++;
                clause.used = conflicts;
                if (value(a[0]) == 0) {
                    conflict = a;
                    return;
                }
                assign(a[0], a);
            }
        }
    }

    private void propagate(Row row) {
        if (row.spent.compareTo(row.capacity) > 0) {
            weightedConflict = row;
            conflict = explanation(row, -1, trail.size());
            return;
        }
        BigInteger slack = row.capacity.subtract(row.spent);
        // With no undo, slack only decreases and the already-visited prefix
        // contains assigned literals. Resume at its frontier instead of
        // inspecting those same large coefficients on every wake-up.
        for (int i = row.propagated; i < row.literals.length; i++) {
            charge();
            if (row.weights[i].compareTo(slack) <= 0) break;
            if (value(row.literals[i]) < 0) {
                int literal = row.literals[i] ^ 1;
                lazyReasons[literal / 2] = new LazyReason(row, i, trail.size());
                assign(literal, null);
            }
            row.propagated = i + 1;
        }
    }

    private int[] reason(int id) {
        LazyReason why = lazyReasons[id];
        if (reasons[id] == null && why != null) {
            reasons[id] = explanation(why.row, why.forced, why.prefix);
            materializedReasons++;
        }
        return reasons[id];
    }

    private int[] explanation(Row row, int forced, int prefix) {
        List<Integer> reason = new ArrayList<>();
        BigInteger total = forced < 0 ? BigInteger.ZERO : row.weights[forced];
        if (forced >= 0) reason.add(row.literals[forced] ^ 1);
        for (int i = 0; i < row.literals.length; i++) {
            charge();
            if (total.compareTo(row.capacity) > 0) break;
            if (value(row.literals[i]) != 1 || positions[row.literals[i] / 2] >= prefix) continue;
            total = total.add(row.weights[i]);
            reason.add(row.literals[i] ^ 1);
        }
        if (total.compareTo(row.capacity) <= 0) throw new IllegalStateException("Incomplete weighted reason");
        return reason.stream().mapToInt(Integer::intValue).toArray();
    }

    private void analyze() {
        conflicts++;
        int derived = resolveWeightedConflict();
        int highest = 0;
        for (int literal : conflict) highest = Math.max(highest, levels[literal / 2]);
        if (highest == 0) {
            remember(new int[0]);
            infeasible = true;
            finish("proven_infeasible");
            return;
        }
        if (highest < level) backtrack(highest);
        BitSet seen = new BitSet();
        List<Integer> learnedClause = new ArrayList<>();
        int open = 0, position = trail.size() - 1, pivot = -1;
        int[] reason = conflict;
        do {
            for (int literal : reason) {
                charge();
                int id = literal / 2;
                if (id == pivot / 2 && pivot >= 0 || seen.get(id) || levels[id] == 0) continue;
                seen.set(id);
                activity[id] += increment;
                if (lastParticipation[id] != conflicts) {
                    lastParticipation[id] = conflicts;
                    participated[id]++;
                }
                if (levels[id] == level) open++;
                else learnedClause.add(literal);
            }
            while (position >= 0 && !seen.get(trail.get(position) / 2)) {
                charge();
                position--;
            }
            if (position < 0) throw new IllegalStateException("CDCL reason lost its decision frontier");
            pivot = trail.get(position--);
            seen.clear(pivot / 2);
            open--;
            reason = reason(pivot / 2);
            if (open > 0 && reason == null) throw new IllegalStateException("CDCL unresolved decision");
        } while (open > 0);
        learnedClause.add(0, pivot ^ 1);
        BitSet retained = new BitSet();
        learnedClause.forEach(literal -> retained.set(literal / 2));
        long recursiveAllowance = Math.min(16384, allowance / 32) - minimizationWork;
        // Remove a literal only when its antecedents are already represented.
        for (int i = learnedClause.size() - 1; i > 0; i--) {
            int id = learnedClause.get(i) / 2;
            int[] why = reason(id);
            if (why == null) continue;
            boolean redundant = true;
            for (int literal : why) {
                charge();
                if (literal / 2 != id && levels[literal / 2] > 0 && !retained.get(literal / 2)) {
                    redundant = false;
                    break;
                }
            }
            if (!redundant && recursiveAllowance > 0) {
                long before = work;
                redundant = recursiveRedundant(id, retained, work + Math.min(512, recursiveAllowance));
                long used = work - before;
                minimizationWork += used;
                recursiveAllowance -= used;
                if (redundant) recursivelyMinimized++;
            }
            if (redundant) {
                retained.clear(id);
                learnedClause.remove(i);
                minimized++;
            }
        }
        BitSet distinctLevels = new BitSet();
        int back = 0;
        for (int i = 0; i < learnedClause.size(); i++) {
            int at = levels[learnedClause.get(i) / 2];
            distinctLevels.set(at);
            if (i > 0) back = Math.max(back, at);
        }
        int[] literals = learnedClause.stream().mapToInt(Integer::intValue).toArray();
        remember(literals);
        reserve(96L + 4L * literals.length);
        Clause clause = new Clause(literals, true, distinctLevels.cardinality());
        backtrack(back);
        conflict = null;
        weightedConflict = null;
        attach(clause, true);
        if (derived >= 0) compile(weightedRows.get(derived), weightedBase() + derived);
        increment /= 0.95;
        if (increment > 1e80) {
            for (int i = 0; i < activity.length; i++) activity[i] *= 1e-80;
            increment *= 1e-80;
        }
        if (conflicts % 128 == 0) reduceDatabase();
    }

    /**
     * Resolve through intermediate reasons, stopping at the retained frontier.
     * The walk is iterative and local: an unresolved decision or a work cutoff
     * keeps the literal. No current-branch assumption becomes a root fact.
     */
    private boolean recursiveRedundant(int candidate, BitSet retained, long until) {
        int[] pending = new int[values.length];
        BitSet visited = new BitSet();
        int size = 0;
        pending[size++] = candidate;
        visited.set(candidate);
        while (size > 0) {
            if (work >= until) return false;
            charge();
            int id = pending[--size];
            int[] antecedents = reason(id);
            if (antecedents == null) return false;
            for (int literal : antecedents) {
                if (work >= until) return false;
                charge();
                int other = literal / 2;
                if (other == id || levels[other] == 0 || retained.get(other) && other != candidate) continue;
                // Reasons must precede the implied value on the trail. Keep
                // the original clause if an unexpected dependency is present.
                if (positions[other] >= positions[id]) return false;
                if (!visited.get(other)) {
                    visited.set(other);
                    pending[size++] = other;
                }
            }
        }
        return true;
    }

    /**
     * Eliminate propagated variables between weighted reasons, retaining their
     * coefficients. Only a still-conflicting resolvent is used. A difficult PB
     * resolvent falls back to the independently sound first-UIP clause path.
     */
    private int resolveWeightedConflict() {
        long available = Math.min(32768, allowance / 32) - weightedWork;
        if (weightedConflict == null || weightedRows.size() >= 128 ||
                weightedConflict.source.terms().size() > 256 || available <= 0)
            return -1;
        long started = work;
        try {
            return resolveWeightedConflict(available, started);
        } finally {
            weightedWork += work - started;
        }
    }

    private int resolveWeightedConflict(long available, long started) {
        var current = weightedConflict.source;
        int parent = weightedConflict.proofIndex, result = -1;
        for (int at = trail.size() - 1, rounds = 0; at >= 0 && rounds < 8 && weightedRows.size() < 128 && work - started < available; at--) {
            charge();
            int id = trail.get(at) / 2;
            LazyReason why = lazyReasons[id];
            BigInteger a = current.terms().get(id);
            if (why == null || a == null) continue;
            BigInteger b = why.row.source.terms().get(id);
            if (b == null || a.signum() == b.signum()) continue;
            BigInteger common = a.gcd(b), left = b.abs().divide(common), right = a.abs().divide(common);
            Map<Integer, BigInteger> terms = new TreeMap<>();
            for (var term : current.terms().entrySet()) {
                charge();
                terms.put(term.getKey(), term.getValue().multiply(left));
            }
            for (var term : why.row.source.terms().entrySet()) {
                charge();
                terms.merge(term.getKey(), term.getValue().multiply(right), BigInteger::add);
            }
            terms.values().removeIf(value -> value.signum() == 0);
            if (terms.size() > 256 || terms.values().stream().anyMatch(value -> value.bitLength() > 512)) continue;
            BigInteger divisor = BigInteger.ZERO;
            for (BigInteger value : terms.values()) divisor = divisor.gcd(value);
            if (divisor.signum() == 0) divisor = BigInteger.ONE;
            BigInteger bound = current.upper().multiply(left).add(why.row.source.upper().multiply(right));
            BigInteger[] qr = bound.divideAndRemainder(divisor);
            bound = qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
            BigInteger gcd = divisor;
            terms.replaceAll((key, value) -> value.divide(gcd));
            var candidate = new ExactLinearProgram.Constraint(terms, bound);
            if (knownRows.contains(candidate)) continue;
            BigInteger minimum = BigInteger.ZERO;
            for (var term : terms.entrySet()) {
                charge();
                int variable = term.getKey();
                BigInteger endpoint = values[variable] < 0 ? (term.getValue().signum() > 0 ? lower[variable] : upper[variable]) :
                        lower[variable].add(BigInteger.valueOf(values[variable]));
                minimum = minimum.add(term.getValue().multiply(endpoint));
            }
            if (minimum.compareTo(bound) <= 0) {
                // A raw cancellation can lose the conflict. Weaken to a
                // multiple of the reason's pivot using only declared domain
                // bounds, then divide. This reduces slack without inventing
                // assumptions from the current assignment.
                if (weightedRows.size() > 125 || work - started >= available) continue;
                var repaired = repairReason(why.row, b.abs());
                if (repaired == null) continue;
                var resolved = cancel(current, parent, repaired.consequence(), weightedBase() + weightedRows.size(), id);
                if (resolved == null || !conflicting(resolved.consequence()) || knownRows.contains(resolved.consequence())) continue;
                appendWeighted(repaired);
                result = appendWeighted(resolved);
                repairedReasons++;
                parent = weightedBase() + result;
                current = resolved.consequence();
                rounds++;
                continue;
            }
            var step = new CountProof.Combination(Map.of(parent, left, why.row.proofIndex, right), divisor, CountProof.row(candidate));
            reserve(512L + 288L * terms.size());
            weightedSteps.add(step);
            result = weightedRows.size();
            weightedRows.add(candidate);
            knownRows.add(candidate);
            parent = weightedBase() + result;
            current = candidate;
            rounds++;
        }
        return result;
    }

    private record Weighted(ExactLinearProgram.Constraint consequence, CountProof.Combination proof) {}

    private int weightedBase() {
        return original.size() + 2 * values.length;
    }

    private Weighted repairReason(Row reason, BigInteger divisor) {
        if (divisor.compareTo(BigInteger.ONE) <= 0) return null;
        Map<Integer, BigInteger> terms = new TreeMap<>(), parents = new TreeMap<>();
        parents.put(reason.proofIndex, BigInteger.ONE);
        BigInteger bound = reason.source.upper();
        for (var term : reason.source.terms().entrySet()) {
            charge();
            int id = term.getKey();
            BigInteger coefficient = term.getValue(), remainder = coefficient.abs().remainder(divisor);
            if (remainder.signum() > 0) {
                boolean positive = coefficient.signum() > 0;
                parents.merge(original.size() + 2 * id + (positive ? 0 : 1), remainder, BigInteger::add);
                bound = bound.add(remainder.multiply(positive ? lower[id].negate() : upper[id]));
                coefficient = positive ? coefficient.subtract(remainder) : coefficient.add(remainder);
            }
            if (coefficient.signum() != 0) terms.put(id, coefficient.divide(divisor));
        }
        BigInteger[] qr = bound.divideAndRemainder(divisor);
        var result = new ExactLinearProgram.Constraint(terms, qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0]);
        return new Weighted(result, new CountProof.Combination(parents, divisor, CountProof.row(result)));
    }

    private Weighted cancel(ExactLinearProgram.Constraint a, int ai, ExactLinearProgram.Constraint b, int bi, int pivot) {
        BigInteger x = a.terms().get(pivot), y = b.terms().get(pivot);
        if (x == null || y == null || x.signum() == y.signum()) return null;
        BigInteger common = x.gcd(y), left = y.abs().divide(common), right = x.abs().divide(common);
        Map<Integer, BigInteger> terms = new TreeMap<>();
        for (var term : a.terms().entrySet()) {
            charge();
            terms.put(term.getKey(), term.getValue().multiply(left));
        }
        for (var term : b.terms().entrySet()) {
            charge();
            terms.merge(term.getKey(), term.getValue().multiply(right), BigInteger::add);
        }
        terms.values().removeIf(v -> v.signum() == 0);
        if (terms.size() > 256 || terms.values().stream().anyMatch(v -> v.bitLength() > 512)) return null;
        BigInteger divisor = BigInteger.ZERO;
        for (var value : terms.values()) divisor = divisor.gcd(value);
        if (divisor.signum() == 0) divisor = BigInteger.ONE;
        BigInteger[] qr = a.upper().multiply(left).add(b.upper().multiply(right)).divideAndRemainder(divisor);
        BigInteger gcd = divisor;
        terms.replaceAll((id, value) -> value.divide(gcd));
        var result = new ExactLinearProgram.Constraint(terms, qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0]);
        return new Weighted(result, new CountProof.Combination(Map.of(ai, left, bi, right), divisor, CountProof.row(result)));
    }

    private boolean conflicting(ExactLinearProgram.Constraint row) {
        BigInteger minimum = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) {
            charge();
            int id = term.getKey();
            BigInteger value = values[id] < 0 ? (term.getValue().signum() > 0 ? lower[id] : upper[id]) : lower[id].add(BigInteger.valueOf(values[id]));
            minimum = minimum.add(term.getValue().multiply(value));
        }
        return minimum.compareTo(row.upper()) > 0;
    }

    private int appendWeighted(Weighted value) {
        reserve(512L + 288L * value.consequence().terms().size());
        weightedSteps.add(value.proof());
        int result = weightedRows.size();
        weightedRows.add(value.consequence());
        knownRows.add(value.consequence());
        return result;
    }

    private void backtrack(int to) {
        while (!trail.isEmpty() && levels[trail.get(trail.size() - 1) / 2] > to) {
            charge();
            int id = trail.remove(trail.size() - 1) / 2;
            if (branching == Branching.LEARNING_RATE) {
                double alpha = Math.max(0.06, 0.4 - 1e-6 * conflicts);
                double reward = participated[id] / (double) Math.max(1, conflicts - assignedAt[id]);
                learningRate[id] = (1 - alpha) * learningRate[id] + alpha * reward;
            }
            for (var occurrence : affected.get(id)) {
                charge();
                Row row = rows.get(occurrence.row);
                if (value(row.literals[occurrence.term]) == 1) {
                    row.spent = row.spent.subtract(row.weights[occurrence.term]);
                    activityUpdates++;
                }
                // A previously visited literal becomes unassigned again.
                // Invalidate even when its contribution to spent was zero.
                row.propagated = 0;
                enqueue(occurrence.row);
            }
            values[id] = -1;
            levels[id] = 0;
            reasons[id] = null;
            lazyReasons[id] = null;
        }
        level = to;
        head = Math.min(head, trail.size());
    }

    private void reduceDatabase() {
        Set<int[]> locked = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int[] reason : reasons) if (reason != null) locked.add(reason);
        List<Clause> candidates = new ArrayList<>();
        for (Clause clause : clauses) {
            charge();
            if (clause.learned && clause.literals.length > 2 && clause.lbd > 2 && !locked.contains(clause.literals)) candidates.add(clause);
        }
        if (candidates.size() < 256) return;
        candidates.sort(Comparator.comparingInt((Clause c) -> -c.lbd).thenComparingLong(c -> c.used));
        for (int i = 0; i < candidates.size() / 2; i++) {
            Clause clause = candidates.get(i);
            clause.deleted = true;
            long bytes = 96L + 4L * clause.literals.length;
            memory -= bytes;
            budget.release(bytes);
            removed++;
        }
        clauses.removeIf(c -> c.deleted);
        for (var list : watches) list.removeIf(entry -> entry instanceof Clause c && c.deleted);
    }

    private void remember(int[] literals) {
        if (learned.size() >= 128 && proofScope == null) return;
        List<ExactLinearProgram.Constraint> assumptions = new ArrayList<>();
        for (int literal : literals) assumptions.add(bound(literal / 2,
                lower[literal / 2].add((literal & 1) == 0 ? BigInteger.ONE : BigInteger.ZERO), (literal & 1) == 0));
        reserve(128L + 144L * assumptions.size());
        var clause = new CountConflict(assumptions);
        if (learned.size() < 128) learned.add(clause);
        if (proofScope != null) proofSteps.add(clause);
    }

    private static ExactLinearProgram.Constraint bound(int id, BigInteger value, boolean minimum) {
        return new ExactLinearProgram.Constraint(Map.of(id, minimum ? BigInteger.ONE.negate() : BigInteger.ONE), minimum ? value.negate() : value);
    }

    private void enqueue(int row) {
        if (!queued.get(row)) {
            queued.set(row);
            pending.addLast(row);
        }
    }

    private static int luby(int index) {
        int size = 1, power = 1;
        while (size < index) {
            size = size * 2 + 1;
            power *= 2;
        }
        while (size != index) {
            size = (size - 1) / 2;
            power /= 2;
            index = (index - 1) % size + 1;
        }
        return power;
    }

    private void reserve(long bytes) {
        if (!budget.tryReserve(bytes)) {
            memoryLimit = true;
            throw new Stop();
        }
        memory += bytes;
    }

    private void charge() {
        budget.check();
        if (++work > allowance && !retaining) throw new Stop();
    }

    private boolean finish(String detail) {
        if (!complete && proofScope != null) {
            if (!weightedSteps.isEmpty()) {
                var axioms = new ArrayList<>(original.stream().map(CountProof::row).toList());
                for (int i = 0; i < values.length; i++) {
                    axioms.add(CountProof.row(bound(i, lower[i], true)));
                    axioms.add(CountProof.row(bound(i, upper[i], false)));
                }
                budget.proofJournal().add(new CountProof.Derivation("cdcl:weighted_resolution", values.length, axioms, weightedSteps));
                proofScope.addAll(weightedRows);
            }
            budget.proofJournal().add(CountProof.certificate("cdcl:" + detail, values.length, proofScope, proofSteps, null, infeasible));
        }
        complete = true;
        budget.note("count_cdcl", detail + "; variables=" + values.length + "; decisions=" + decisions + "; conflicts=" + conflicts +
                "; restarts=" + restarts + "; minimized_literals=" + minimized + "; recursive_minimized=" + recursivelyMinimized + "; minimization_work=" + minimizationWork + "; deleted_clauses=" + removed +
                "; activity_updates=" + activityUpdates + "; explanations=" + materializedReasons + "; weighted_resolvents=" + weightedRows.size() + "; repaired_reasons=" + repairedReasons + "; branching=" + branching + "; pb_work=" + weightedWork + "; work=" + work);
        return true;
    }

    BigInteger[] counts() {
        return counts == null ? null : counts.clone();
    }

    boolean infeasible() {
        return infeasible;
    }

    List<CountConflict> learnedConflicts() {
        return List.copyOf(learned);
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
