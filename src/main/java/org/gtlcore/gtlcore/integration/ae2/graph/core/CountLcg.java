package org.gtlcore.gtlcore.integration.ae2.graph.core;

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

    private record Change(Literal literal, BigInteger old, int level, List<Literal> reason, long bytes) {}

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private static final class Nogood {

        final List<Literal> terms;
        final int lbd;
        long used;

        Nogood(List<Literal> terms, int lbd, long used) {
            this.terms = terms;
            this.lbd = lbd;
            this.used = used;
        }
    }

    private final PlanningBudget budget;
    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] rootLow, rootHigh, low, high;
    private final BigInteger[] levelZeroLow, levelZeroHigh;
    private final double[] activity;
    private final List<List<Integer>> incident = new ArrayList<>();
    private final List<List<Integer>> boundTrail = new ArrayList<>();
    private final Deque<Integer> queue = new ArrayDeque<>();
    private final BitSet queued = new BitSet();
    private final List<Change> trail = new ArrayList<>();
    private final List<Nogood> clauses = new ArrayList<>();
    private final List<CountConflict> learned = new ArrayList<>(), proofSteps = new ArrayList<>();
    private long allowance;
    private List<Literal> conflict;
    private BigInteger[] counts;
    private int level, scan, decisions, conflicts, jumps, restarts, nextRestart = 64;
    private long work, workTicks, memory, rootProgress, learnedProgress, relaxedReasons;
    private double increment = 1;
    private boolean complete, infeasible, rescan, paused, differenceChecked;
    private final boolean retainProof;
    private CountProof.Certificate certificate;
    private CountProof.Certificate differenceProof;

    CountLcg(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
             PlanningBudget budget, long maximumWork) {
        this(rows, lower, upper, budget, maximumWork, false);
    }

    CountLcg(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
             PlanningBudget budget, long maximumWork, boolean retainProof) {
        this.rows = rows;
        this.budget = budget;
        this.retainProof = retainProof || budget.proofJournal() != null;
        rootLow = lower.clone();
        rootHigh = upper.clone();
        low = lower.clone();
        high = upper.clone();
        levelZeroLow = lower.clone();
        levelZeroHigh = upper.clone();
        activity = new double[lower.length];
        allowance = Math.min(maximumWork, budget.remainingWork() / 8);
        long terms = rows.stream().mapToLong(r -> r.terms().size()).sum();
        if (lower.length > 512 || rows.size() > 2048 || terms > 32768 || allowance < 1024) {
            complete = true;
            return;
        }
        long bytes = 2048 + 544L * lower.length + 96L * terms + 64L * rows.size();
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        for (int i = 0; i < lower.length; i++) {
            incident.add(new ArrayList<>());
            incident.add(new ArrayList<>());
            boundTrail.add(new ArrayList<>());
            boundTrail.add(new ArrayList<>());
            if (upper[i] != null && lower[i].compareTo(upper[i]) > 0) conflict = List.of();
        }
        for (int r = 0; r < rows.size(); r++) {
            for (var term : rows.get(r).terms().entrySet()) {
                if (term.getValue().signum() == 0) continue;
                int id = term.getKey();
                incident.get(boundKey(id, term.getValue().signum() > 0)).add(r);
                activity[id] += 1.0 / rows.get(r).terms().size();
            }
            enqueue(r);
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
                differenceProof = CountDifference.contradiction(rows, rootLow, rootHigh, budget, allowance - work);
                workTicks += (budget.threadWork() - before) * PlanningBudget.WORK_SCALE;
                work = PlanningBudget.units(workTicks);
                if (differenceProof != null) {
                    infeasible = true;
                    return finish("difference_cycle");
                }
            }
            if (conflict != null) {
                analyze();
                return complete;
            }
            if (!queue.isEmpty()) {
                int id = queue.removeFirst();
                queued.clear(id);
                propagate(rows.get(id));
                return false;
            }
            if (rescan) {
                scan = 0;
                rescan = false;
            }
            if (scan < clauses.size()) {
                propagate(clauses.get(scan++));
                return false;
            }
            if (conflicts >= nextRestart && level > 0) {
                backtrack(0);
                restarts++;
                nextRestart = conflicts + Math.min(2048, 64 << Math.min(5, Integer.numberOfTrailingZeros(restarts + 1)));
                return false;
            }
            if (satisfies(low)) {
                counts = low.clone();
                return finish("verified_witness");
            }
            int best = -1;
            for (int i = 0; i < low.length; i++) {
                charge();
                if (!low[i].equals(high[i]) && (best < 0 || activity[i] > activity[best])) best = i;
            }
            if (best < 0) throw new IllegalStateException("Fixed integer assignment was not propagated");
            BigInteger middle = high[best] == null ? low[best].add(low[best].abs().max(BigInteger.ONE)) :
                    low[best].add(high[best].subtract(low[best]).shiftRight(1));
            level++;
            decisions++;
            tighten(new Literal(best, false, middle), null);
            return false;
        } catch (Stop stopped) {
            return finish("workspace_limit");
        }
    }

    private void propagate(ExactLinearProgram.Constraint row) {
        BigInteger sum = BigInteger.ZERO;
        int infinite = -1, infinities = 0;
        for (var term : row.terms().entrySet()) {
            charge();
            if (term.getValue().signum() == 0) continue;
            BigInteger endpoint = term.getValue().signum() > 0 ? low[term.getKey()] : high[term.getKey()];
            if (endpoint == null) {
                infinite = term.getKey();
                infinities++;
            } else {
                integerCost(term.getValue(), endpoint);
                sum = sum.add(term.getValue().multiply(endpoint));
            }
        }
        if (infinities == 0 && sum.compareTo(row.upper()) > 0) {
            conflict = antecedents(row, -1, sum.subtract(row.upper()).subtract(BigInteger.ONE));
            return;
        }
        if (infinities > 1) return;
        // Propagate using a frozen set of bounds; new implications are placed
        // on the queue, so none of their explanations can refer to themselves.
        var candidates = new ArrayList<Literal>();
        var explanations = new ArrayList<List<Literal>>();
        for (var term : row.terms().entrySet()) {
            charge();
            int id = term.getKey();
            BigInteger a = term.getValue();
            if (a.signum() == 0 || infinities == 1 && id != infinite) continue;
            BigInteger endpoint = a.signum() > 0 ? low[id] : high[id];
            BigInteger other = endpoint == null ? sum : sum.subtract(a.multiply(endpoint));
            Literal next = a.signum() > 0 ? new Literal(id, false, floor(row.upper().subtract(other), a)) :
                    new Literal(id, true, floor(row.upper().subtract(other), a.negate()).negate());
            if (truth(next) != 1) {
                candidates.add(next);
                // The opposite bound must violate this integer row by at
                // least one. Spend only the surplus on relaxing its reason.
                BigInteger slack = other.add(a.multiply(next.opposite().value)).subtract(row.upper()).subtract(BigInteger.ONE);
                explanations.add(antecedents(row, id, slack));
            }
        }
        for (int i = 0; i < candidates.size() && conflict == null; i++) tighten(candidates.get(i), explanations.get(i));
    }

    private List<Literal> antecedents(ExactLinearProgram.Constraint row, int except, BigInteger slack) {
        var result = new ArrayList<Literal>();
        for (var term : row.terms().entrySet()) {
            charge();
            int id = term.getKey();
            if (id == except || term.getValue().signum() == 0) continue;
            boolean minimum = term.getValue().signum() > 0;
            var literal = new Literal(id, minimum, minimum ? low[id] : high[id]);
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

    private void propagate(Nogood clause) {
        Literal remaining = null;
        for (Literal literal : clause.terms) {
            charge();
            int truth = truth(literal);
            if (truth == 0) return;
            if (truth < 0) {
                if (remaining != null) return;
                remaining = literal;
            }
        }
        clause.used = conflicts;
        if (remaining == null) {
            conflict = clause.terms;
            return;
        }
        var why = new ArrayList<>(clause.terms);
        why.remove(remaining);
        tighten(remaining.opposite(), why);
    }

    private void tighten(Literal literal, List<Literal> reason) {
        charge();
        if (truth(literal) == 1) return;
        if (truth(literal) == 0) {
            if (reason == null) throw new IllegalStateException("Contradictory integer split");
            var why = new ArrayList<>(reason);
            why.add(literal.opposite());
            conflict = why;
            return;
        }
        long bytes = 192L + 96L * (reason == null ? 0 : reason.size());
        reserve(bytes);
        BigInteger old = literal.minimum ? low[literal.variable] : high[literal.variable];
        trail.add(new Change(literal, old, level, reason == null ? null : List.copyOf(reason), bytes));
        boundTrail.get(boundKey(literal.variable, literal.minimum)).add(trail.size() - 1);
        if (level == 0) {
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
        incident.get(boundKey(literal.variable, literal.minimum)).forEach(this::enqueue);
        rescan = true;
    }

    private void analyze() {
        conflicts++;
        List<Literal> frontier = normalize(conflict);
        int highest;
        while (true) {
            highest = 0;
            int number = 0, last = -1;
            for (Literal literal : frontier) {
                int index = source(literal);
                int at = index < 0 ? 0 : trail.get(index).level;
                if (at > highest) {
                    highest = at;
                    number = 1;
                    last = index;
                } else if (at == highest && at > 0) {
                    number++;
                    last = Math.max(last, index);
                }
            }
            if (highest == 0 || number <= 1) break;
            Change change = trail.get(last);
            if (change.reason == null) throw new IllegalStateException("Integer conflict has multiple unresolved decisions");
            int position = last;
            frontier.removeIf(literal -> source(literal) == position);
            frontier.addAll(change.reason);
            frontier = normalize(frontier);
        }
        if (highest == 0) {
            remember(List.of());
            infeasible = true;
            finish("proven_infeasible");
            return;
        }
        int back = 0;
        BitSet levels = new BitSet();
        for (Literal literal : frontier) {
            int at = trail.get(source(literal)).level;
            levels.set(at);
            if (at != highest) back = Math.max(back, at);
            activity[literal.variable] += increment;
        }
        remember(frontier);
        reserve(128L + 96L * frontier.size());
        clauses.add(new Nogood(List.copyOf(frontier), levels.cardinality(), conflicts));
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
                long bytes = 128L + 96L * clause.terms.size();
                memory -= bytes;
                budget.release(bytes);
            }
        }
    }

    private List<Literal> normalize(Collection<Literal> literals) {
        Map<Integer, Literal> result = new LinkedHashMap<>();
        for (Literal literal : literals) {
            charge();
            if (rootTrue(literal)) continue;
            int key = literal.variable * 2 + (literal.minimum ? 1 : 0);
            Literal old = result.get(key);
            if (old == null || (literal.minimum ? literal.value.compareTo(old.value) > 0 : literal.value.compareTo(old.value) < 0)) result.put(key, literal);
        }
        return new ArrayList<>(result.values());
    }

    private int source(Literal literal) {
        if (rootTrue(literal)) return -1;
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
            var indices = boundTrail.get(boundKey(change.literal.variable, change.literal.minimum));
            indices.remove(indices.size() - 1);
            if (change.literal.minimum) low[change.literal.variable] = change.old;
            else high[change.literal.variable] = change.old;
            incident.get(boundKey(change.literal.variable, change.literal.minimum)).forEach(this::enqueue);
            memory -= change.bytes;
            budget.release(change.bytes);
        }
        level = to;
        rescan = true;
        scan = 0;
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
        if (!queued.get(row)) {
            queued.set(row);
            queue.addLast(row);
        }
    }

    private static BigInteger floor(BigInteger n, BigInteger d) {
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
        allowance = work + Math.min(quantum, budget.remainingWork());
        paused = false;
        complete = false;
        certificate = null;
    }

    /** Search feedback, never a proof or a reason to exclude a model. */
    long progress() {
        return rootProgress + learnedProgress;
    }

    private void reserve(long bytes) {
        if (!budget.tryReserve(bytes)) throw new Stop();
        memory += bytes;
    }

    private boolean finish(String detail) {
        if (!complete && retainProof) {
            var scope = new ArrayList<>(rows);
            for (int i = 0; i < low.length; i++) {
                scope.add(new Literal(i, true, rootLow[i]).row());
                if (rootHigh[i] != null) scope.add(new Literal(i, false, rootHigh[i]).row());
            }
            certificate = differenceProof != null ? differenceProof : CountProof.certificate("lcg:" + detail, low.length, scope, proofSteps, null, infeasible);
            if (budget.proofJournal() != null) budget.proofJournal().add(certificate);
        }
        complete = true;
        budget.note("count_lcg", detail + "; decisions=" + decisions + "; conflicts=" + conflicts + "; backjumps=" + jumps +
                "; restarts=" + restarts + "; relaxed_reasons=" + relaxedReasons + "; work=" + work);
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

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
