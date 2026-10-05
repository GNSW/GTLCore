package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Work-queue integer bound propagation over joint material and branch constraints. */
final class CountBounds implements AutoCloseable {

    private static final int MAX_ELIMINATION_TERMS = 16;

    private final List<ExactLinearProgram.Constraint> rows;
    private final List<ExactLinearProgram.Constraint> originalRows;
    private final int assumptionStart;
    private final PlanningBudget budget;
    private final BigInteger[] lower, upper;
    private final List<List<Integer>> affected = new ArrayList<>();
    private final ArrayDeque<Integer> queue = new ArrayDeque<>();
    private final BitSet queued = new BitSet();
    private long memory, work;
    private long allowance;
    private final long continuationLimit;
    private boolean complete, blocked;
    private boolean combined;
    private final boolean explain;
    private final List<BitSet> rowReasons = new ArrayList<>();
    private final BitSet[] lowerReasons, upperReasons;
    private BitSet conflict;
    private final Set<CountConflict> learned = new LinkedHashSet<>();
    private final Set<CountConflict> usedConflicts = new LinkedHashSet<>();
    private final Set<ExactLinearProgram.Constraint> propagatedRows = new HashSet<>();
    private List<CountConflict> checking = List.of();
    private int conflictCursor;

    /** Immutable ancestor state; siblings clone arrays, never share mutable propagation. */
    static final class Seed implements AutoCloseable {

        final List<ExactLinearProgram.Constraint> globals, assumptions, rows;
        final List<List<Integer>> affected;
        final List<BitSet> rowReasons;
        final BigInteger[] lower, upper;
        final BitSet[] lowerReasons, upperReasons;
        final List<Integer> pending;
        private final AtomicInteger owners = new AtomicInteger(1);
        private final PlanningBudget budget;
        private final long bytes;

        Seed(CountBounds source, long bytes) {
            globals = List.copyOf(source.originalRows.subList(0, source.assumptionStart));
            assumptions = List.copyOf(source.originalRows.subList(source.assumptionStart, source.originalRows.size()));
            rows = List.copyOf(source.rows);
            affected = source.affected.stream().map(List::copyOf).toList();
            rowReasons = Collections.unmodifiableList(new ArrayList<>(source.rowReasons));
            lower = source.lower.clone();
            upper = source.upper.clone();
            lowerReasons = source.lowerReasons.clone();
            upperReasons = source.upperReasons.clone();
            pending = List.copyOf(source.queue);
            budget = source.budget;
            this.bytes = bytes;
        }

        boolean compatible(List<ExactLinearProgram.Constraint> next, int start) {
            return start >= 0 && next.size() - start >= assumptions.size() &&
                    next.subList(start, start + assumptions.size()).equals(assumptions) &&
                    new HashSet<>(next.subList(0, start)).containsAll(globals);
        }

        Seed retain() {
            owners.incrementAndGet();
            return this;
        }

        long retainedBytes() {
            return bytes;
        }

        @Override
        public void close() {
            if (owners.decrementAndGet() == 0) budget.release(bytes);
        }
    }

    CountBounds(int variables, List<ExactLinearProgram.Constraint> rows, PlanningBudget budget) {
        this(variables, rows, budget, -1);
    }

    CountBounds(int variables, List<ExactLinearProgram.Constraint> rows, PlanningBudget budget, int assumptionStart) {
        this(variables, rows, budget, assumptionStart, null);
    }

    CountBounds(int variables, List<ExactLinearProgram.Constraint> rows, PlanningBudget budget, int assumptionStart, Seed seed) {
        this.rows = new ArrayList<>(rows);
        originalRows = List.copyOf(rows);
        this.assumptionStart = assumptionStart;
        this.budget = budget;
        explain = assumptionStart >= 0;
        lower = new BigInteger[variables];
        upper = new BigInteger[variables];
        lowerReasons = explain ? new BitSet[variables] : null;
        upperReasons = explain ? new BitSet[variables] : null;
        Arrays.fill(lower, BigInteger.ZERO);
        long bytes = 128L + 128L * variables + 64L * rows.size();
        long incidences = 0;
        for (var row : rows) incidences += row.terms().size();
        bytes += 128L * incidences + 64L * rows.size();
        if (explain) bytes += (rows.size() + 2L * variables) * (32L + 8L * ((rows.size() - assumptionStart + 63) / 64));
        if (seed != null) bytes += 64L * seed.rows.size() + 16L * seed.affected.stream().mapToLong(List::size).sum();
        // A wide/deep DAG may need several waves of propagation. Allocate by
        // sparse model size, while retaining a local ceiling for slow cycles.
        allowance = Math.min(262_144L, Math.max(50_000L, 64L * incidences));
        continuationLimit = Math.min(2_000_000L, Math.max(allowance, 512L * incidences));
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        try {
            if (seed != null && seed.compatible(rows, assumptionStart)) {
                inherit(seed, rows);
                return;
            }
            Map<Map<Integer, BigInteger>, Integer> known = new HashMap<>();
            BitSet redundant = new BitSet();
            for (int r = 0; r < rows.size(); r++) {
                BitSet reason = null;
                if (explain && r >= assumptionStart) {
                    reason = new BitSet();
                    reason.set(r - assumptionStart);
                }
                rowReasons.add(reason);
                var row = normalized(rows.get(r));
                this.rows.set(r, row);
                Integer same = known.get(row.terms());
                if (same != null && this.rows.get(same).upper().compareTo(row.upper()) <= 0) {
                    redundant.set(r);
                    continue;
                }
                if (same != null) redundant.set(same);
                known.put(row.terms(), r);
                Map<Integer, BigInteger> opposite = new HashMap<>();
                row.terms().forEach((key, value) -> opposite.put(key, value.negate()));
                Integer other = known.get(opposite);
                if (row.terms().isEmpty() && row.upper().signum() < 0 ||
                        other != null && row.upper().add(this.rows.get(other).upper()).signum() < 0) {
                    if (explain) {
                        conflict = reason == null ? new BitSet() : (BitSet) reason.clone();
                        if (other != null) union(conflict, rowReasons.get(other));
                    }
                    finish(true);
                    return;
                }
            }
            for (int i = 0; i < variables; i++) affected.add(new ArrayList<>());
            for (int r = 0; r < this.rows.size(); r++) if (!redundant.get(r)) {
                for (int variable : this.rows.get(r).terms().keySet()) affected.get(variable).add(r);
                enqueue(r);
            }
            combinePairs(known);
            combineCapacityGroups();
            // Seed the worklist with the narrowest equations. A triangular
            // count chain can then propagate from its fixed boundary in one
            // sweep, instead of repeatedly revisiting long prefix equations.
            var initialOrder = new ArrayList<>(queue);
            initialOrder.sort(Comparator.comparingInt(id -> {
                charge();
                return this.rows.get(id).terms().size();
            }));
            queue.clear();
            queue.addAll(initialOrder);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    /** Cancel an entire two-variable form, e.g. a+b<=1 and f-a-b<=0 imply f<=1. */
    private void combinePairs(Map<Map<Integer, BigInteger>, Integer> known) {
        int originalSize = rows.size(), added = 0;
        Set<ExactLinearProgram.Constraint> distinct = new HashSet<>(rows);
        for (int r = 0; r < originalSize && added < 256 && work < allowance / 2; r++) {
            var row = rows.get(r);
            if (row.terms().size() < 3 || row.terms().size() > 4) continue;
            var terms = new ArrayList<>(row.terms().entrySet());
            for (int i = 0; i < terms.size(); i++) for (int j = i + 1; j < terms.size(); j++) {
                charge();
                var a = terms.get(i);
                var b = terms.get(j);
                BigInteger gcd = a.getValue().gcd(b.getValue());
                Map<Integer, BigInteger> opposite = Map.of(a.getKey(), a.getValue().negate().divide(gcd),
                        b.getKey(), b.getValue().negate().divide(gcd));
                Integer paired = known.get(opposite);
                if (paired == null) continue;
                Map<Integer, BigInteger> remaining = new LinkedHashMap<>(row.terms());
                remaining.remove(a.getKey());
                remaining.remove(b.getKey());
                var consequence = normalized(new ExactLinearProgram.Constraint(remaining, row.upper().add(rows.get(paired).upper().multiply(gcd))));
                if (!distinct.add(consequence)) continue;
                long bytes = 192L + 128L * remaining.size();
                if (!budget.tryReserve(bytes)) return;
                memory += bytes;
                BitSet reason = explain ? new BitSet() : null;
                if (explain) {
                    union(reason, rowReasons.get(r));
                    union(reason, rowReasons.get(paired));
                }
                append(consequence, reason);
                if (++added == 256) return;
            }
        }
    }

    /** Nonnegative resource sums and exact cancellation, with integer rounding. */
    private void combineCapacityGroups() {
        if (lower.length > 512 || rows.size() > 2048) return;
        List<Integer> capacities = new ArrayList<>();
        int[] parent = new int[lower.length];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        int initial = rows.size();
        Set<ExactLinearProgram.Constraint> known = new HashSet<>(rows);
        for (int r = 0; r < initial; r++) {
            var row = rows.get(r);
            if (row.terms().size() < 2 || row.upper().signum() < 0 || row.terms().values().stream().anyMatch(v -> v.signum() <= 0)) continue;
            capacities.add(r);
            int first = row.terms().keySet().iterator().next();
            for (int id : row.terms().keySet()) {
                charge();
                int a = root(parent, first), b = root(parent, id);
                parent[a] = b;
            }
        }
        propagateCapacityChains(capacities, known, initial);
        Map<Integer, List<Integer>> components = new LinkedHashMap<>();
        for (int r : capacities) components.computeIfAbsent(root(parent, rows.get(r).terms().keySet().iterator().next()), unused -> new ArrayList<>()).add(r);
        for (var component : components.values()) {
            if (component.size() < 2) continue;
            Map<Integer, BigInteger> terms = new LinkedHashMap<>();
            BigInteger bound = BigInteger.ZERO;
            BitSet reason = explain ? new BitSet() : null;
            for (int r : component) {
                var row = rows.get(r);
                bound = bound.add(row.upper());
                if (explain) union(reason, rowReasons.get(r));
                for (var term : row.terms().entrySet()) {
                    charge();
                    terms.merge(term.getKey(), term.getValue(), BigInteger::add);
                }
            }
            int id = rows.size();
            if (addConsequence(new ExactLinearProgram.Constraint(terms, bound), reason, known)) capacities.add(id);
        }
        // Replace a proportional negative group by its proved upper bound.
        // This handles f-a-b-c <= 0, a+b+c <= 1 without assuming f=1.
        int added = 0;
        for (int r = 0; r < initial && work < allowance / 2; r++) {
            var source = rows.get(r);
            if (source.terms().values().stream().noneMatch(v -> v.signum() < 0)) continue;
            Map<Integer, BigInteger> terms = new LinkedHashMap<>(source.terms());
            BigInteger bound = source.upper();
            BitSet reason = explain ? new BitSet() : null;
            if (explain) union(reason, rowReasons.get(r));
            boolean changed = false;
            // Large summed capacities first expose parity/covering bounds;
            // individual private resource rows still handle multiway sources.
            for (int c = capacities.size() - 1; c >= 0 && work < allowance / 2; c--) {
                var cap = rows.get(capacities.get(c));
                BigInteger numerator = null, denominator = null;
                boolean matches = true;
                for (var term : cap.terms().entrySet()) {
                    charge();
                    BigInteger value = terms.get(term.getKey());
                    if (value == null || value.signum() >= 0) {
                        matches = false;
                        break;
                    }
                    if (numerator == null) {
                        numerator = value.negate();
                        denominator = term.getValue();
                    } else if (!value.negate().multiply(denominator).equals(term.getValue().multiply(numerator))) {
                        matches = false;
                        break;
                    }
                }
                if (!matches || numerator == null) continue;
                BigInteger gcd = numerator.gcd(denominator);
                BigInteger a = numerator.divide(gcd), b = denominator.divide(gcd);
                terms.replaceAll((key, value) -> value.multiply(b));
                cap.terms().keySet().forEach(terms::remove);
                bound = bound.multiply(b).add(cap.upper().multiply(a));
                if (explain) union(reason, rowReasons.get(capacities.get(c)));
                changed = true;
                if (terms.isEmpty()) break;
            }
            if (changed && addConsequence(new ExactLinearProgram.Constraint(terms, bound), reason, known) && ++added >= 128) break;
        }
        if (added > 0 && explain && assumptionStart == originalRows.size())
            budget.note("count_capacity", "verified_group_bounds=" + added);
    }

    /** Carry a joint raw-material capacity through private ratio conversions. */
    private void propagateCapacityChains(List<Integer> capacities, Set<ExactLinearProgram.Constraint> known, int initial) {
        if (capacities.isEmpty()) return;
        Map<Integer, Integer> transitions = new HashMap<>();
        Set<Integer> ambiguous = new HashSet<>();
        for (int r = 0; r < initial; r++) {
            var row = rows.get(r);
            if (row.terms().size() != 2 || row.upper().signum() != 0) continue;
            int negative = -1, positive = -1;
            for (var term : row.terms().entrySet()) {
                charge();
                if (term.getValue().signum() < 0) negative = term.getKey();
                else if (term.getValue().signum() > 0) positive = term.getKey();
            }
            if (negative < 0 || positive < 0) continue;
            if (transitions.putIfAbsent(negative, r) != null) ambiguous.add(negative);
        }
        ambiguous.forEach(transitions::remove);
        int added = 0, count = capacities.size();
        for (int c = 0; c < count && work < allowance / 4; c++) {
            int sourceId = capacities.get(c);
            var source = rows.get(sourceId);
            if (source.terms().size() > MAX_ELIMINATION_TERMS) continue;
            Map<Integer, BigInteger> terms = new LinkedHashMap<>(source.terms());
            BigInteger bound = source.upper();
            BitSet reason = explain ? new BitSet() : null;
            if (explain) union(reason, rowReasons.get(sourceId));
            Set<Integer> visited = new HashSet<>();
            for (int round = 0; round < 32 && work < allowance / 4; round++) {
                Integer from = null, rowId = null;
                for (int id : terms.keySet()) {
                    charge();
                    if (!visited.contains(id) && transitions.containsKey(id)) {
                        from = id;
                        rowId = transitions.get(id);
                        break;
                    }
                }
                if (from == null) break;
                visited.add(from);
                var flow = rows.get(rowId);
                BigInteger consumed = flow.terms().get(from).negate(), coefficient = terms.remove(from);
                BigInteger gcd = consumed.gcd(coefficient), a = consumed.divide(gcd), b = coefficient.divide(gcd);
                terms.replaceAll((key, value) -> value.multiply(a));
                bound = bound.multiply(a);
                for (var term : flow.terms().entrySet()) if (!term.getKey().equals(from)) {
                    charge();
                    terms.merge(term.getKey(), term.getValue().multiply(b), BigInteger::add);
                }
                if (bound.bitLength() > 2048 || terms.values().stream().anyMatch(value -> value.bitLength() > 2048)) break;
                if (explain) union(reason, rowReasons.get(rowId));
                int next = rows.size();
                if (addConsequence(new ExactLinearProgram.Constraint(terms, bound), reason == null ? null : (BitSet) reason.clone(), known)) {
                    capacities.add(next);
                    if (++added >= 128) return;
                }
                // Continue from the exact integer-normalized consequence even
                // when an equivalent row was already present.
                var normalized = normalized(new ExactLinearProgram.Constraint(terms, bound));
                terms = new LinkedHashMap<>(normalized.terms());
                bound = normalized.upper();
            }
        }
    }

    private static int root(int[] parent, int id) {
        while (parent[id] != id) {
            parent[id] = parent[parent[id]];
            id = parent[id];
        }
        return id;
    }

    private boolean addConsequence(ExactLinearProgram.Constraint row, BitSet reason, Set<ExactLinearProgram.Constraint> known) {
        var reduced = normalized(row);
        if (!known.add(reduced)) return false;
        long bytes = 192L + 128L * reduced.terms().size();
        if (!budget.tryReserve(bytes)) return false;
        memory += bytes;
        append(reduced, reason);
        return true;
    }

    private void inherit(Seed seed, List<ExactLinearProgram.Constraint> input) {
        rows.clear();
        rows.addAll(seed.rows);
        rowReasons.addAll(seed.rowReasons);
        System.arraycopy(seed.lower, 0, lower, 0, lower.length);
        System.arraycopy(seed.upper, 0, upper, 0, upper.length);
        System.arraycopy(seed.lowerReasons, 0, lowerReasons, 0, lower.length);
        System.arraycopy(seed.upperReasons, 0, upperReasons, 0, lower.length);
        for (var indices : seed.affected) affected.add(new ArrayList<>(indices));
        seed.pending.forEach(this::enqueue);
        Set<ExactLinearProgram.Constraint> globals = new HashSet<>(seed.globals);
        for (int i = 0; i < assumptionStart; i++) if (!globals.contains(input.get(i))) append(input.get(i), null);
        for (int i = assumptionStart + seed.assumptions.size(); i < input.size(); i++) {
            BitSet reason = new BitSet();
            reason.set(i - assumptionStart);
            append(input.get(i), reason);
        }
        budget.note("count_bounds_reuse", "inherited_rows=" + seed.rows.size() + "; queued=" + queue.size());
    }

    Seed snapshot() {
        if (!explain || blocked || affected.size() != lower.length || rowReasons.size() != rows.size()) return null;
        long entries = rows.stream().mapToLong(row -> row.terms().size()).sum();
        long bytes = 512 + 256L * lower.length + 128L * rows.size() + 128L * entries;
        if (!budget.tryReserve(bytes)) return null;
        return new Seed(this, bytes);
    }

    void learn(List<CountConflict> conflicts) {
        if (complete) return;
        if (learned.addAll(conflicts)) {
            checking = List.copyOf(learned);
            conflictCursor = 0;
        }
    }

    Set<CountConflict> usedConflicts() {
        return Set.copyOf(usedConflicts);
    }

    private void append(ExactLinearProgram.Constraint row, BitSet reason) {
        int id = rows.size();
        var normalized = normalized(row);
        rows.add(normalized);
        rowReasons.add(reason);
        for (int variable : normalized.terms().keySet()) affected.get(variable).add(id);
        enqueue(id);
    }

    private ExactLinearProgram.Constraint normalized(ExactLinearProgram.Constraint row) {
        for (BigInteger ignored : row.terms().values()) charge();
        return CountReduction.normalize(row);
    }

    boolean step() {
        budget.check();
        if (complete) return true;
        if (conflictCursor < checking.size()) {
            propagateConflict(checking.get(conflictCursor++));
            if (blocked) return true;
            return false;
        }
        if (queue.isEmpty()) return finish(false);
        if (work >= allowance) {
            if (!combined) {
                combined = true;
                if (combinePending()) {
                    allowance += 50_000;
                    return false;
                }
            }
            if (allowance < continuationLimit && budget.remainingWork() >= 8192) {
                // Keep the already tightened bounds and pending rows. Large
                // sparse feedback graphs can need more than the first cheap
                // wave; recreating propagation in another strategy loses that
                // progress. Used work remains charged to the shared order.
                long extra = Math.min(continuationLimit - allowance, budget.remainingWork() / 8);
                allowance += extra;
                budget.note("count_bounds_resume", "work=" + work + "; allowance=" + allowance + "; pending_rows=" + queue.size());
                return false;
            }
            budget.note("count_bounds", "work_limit; terms=" + work + "; pending_rows=" + queue.size());
            return finish(false);
        }
        int id = queue.removeFirst();
        queued.clear(id);
        var row = rows.get(id);
        BigInteger finite = BigInteger.ZERO, fixed = BigInteger.ZERO, gcd = BigInteger.ZERO;
        int unknown = 0;
        for (var term : row.terms().entrySet()) {
            charge();
            int variable = term.getKey();
            BigInteger bound = term.getValue().signum() >= 0 ? lower[variable] : upper[variable];
            if (bound == null) unknown++;
            else finite = finite.add(term.getValue().multiply(bound));
            if (lower[variable].equals(upper[variable])) fixed = fixed.add(term.getValue().multiply(lower[variable]));
            else gcd = gcd.gcd(term.getValue());
        }
        // Once counts become fixed, the remaining terms may share a new GCD.
        // Keep their integer residue instead of reintroducing fractional slack.
        BigInteger rowLimit = gcd.compareTo(BigInteger.ONE) > 0 ?
                fixed.add(floorDiv(row.upper().subtract(fixed), gcd).multiply(gcd)) : row.upper();
        if (unknown == 0 && finite.compareTo(rowLimit) > 0) return contradiction(id);
        for (var term : row.terms().entrySet()) {
            charge();
            int variable = term.getKey();
            BigInteger coefficient = term.getValue();
            if (coefficient.signum() == 0) continue;
            BigInteger old = coefficient.signum() > 0 ? lower[variable] : upper[variable];
            if (unknown - (old == null ? 1 : 0) != 0) continue;
            BigInteger others = old == null ? finite : finite.subtract(coefficient.multiply(old));
            BigInteger limit = rowLimit.subtract(others);
            if (coefficient.signum() > 0) {
                if (limit.signum() < 0) return contradiction(id);
                BigInteger next = limit.divide(coefficient);
                if (next.bitLength() > 2048) return finish(false);
                if (upper[variable] == null || next.compareTo(upper[variable]) < 0) {
                    if (explain) upperReasons[variable] = reason(id, variable);
                    upper[variable] = next;
                    conflictCursor = 0;
                    affected.get(variable).forEach(this::enqueue);
                }
            } else {
                BigInteger next = CheckedAmounts.ceilDiv(limit.negate(), coefficient.negate());
                if (next.bitLength() > 2048) return finish(false);
                if (next.compareTo(lower[variable]) > 0) {
                    if (explain) lowerReasons[variable] = reason(id, variable);
                    lower[variable] = next;
                    conflictCursor = 0;
                    affected.get(variable).forEach(this::enqueue);
                }
            }
            if (upper[variable] != null && lower[variable].compareTo(upper[variable]) > 0) {
                if (explain) {
                    conflict = new BitSet();
                    union(conflict, lowerReasons[variable]);
                    union(conflict, upperReasons[variable]);
                }
                return finish(true);
            }
        }
        return false;
    }

    private void propagateConflict(CountConflict learned) {
        var consequence = learned.propagate(lower, upper, budget);
        if (consequence == null) return;
        usedConflicts.add(learned);
        BitSet reason = explain ? new BitSet() : null;
        if (explain) for (var premise : consequence.premises()) for (var term : premise.terms().entrySet()) {
            charge();
            union(reason, term.getValue().signum() > 0 ? upperReasons[term.getKey()] : lowerReasons[term.getKey()]);
        }
        if (consequence.row() == null) {
            conflict = reason;
            finish(true);
        } else {
            if (propagatedRows.contains(consequence.row())) return;
            long bytes = 192L + 128L * consequence.row().terms().size();
            if (!budget.tryReserve(bytes)) return;
            memory += bytes;
            propagatedRows.add(consequence.row());
            append(consequence.row(), reason);
        }
    }

    private void enqueue(int row) {
        if (!queued.get(row)) {
            queued.set(row);
            queue.addLast(row);
        }
    }

    private boolean combinePending() {
        // Tightening one bound at a time can circle indefinitely around a
        // nearly balanced conversion loop. Add exact nonnegative combinations
        // of its sparse rows, eliminating intermediate recipe counts directly.
        // These are consequences of the original constraints, never learned
        // from a scheduling failure or a cutoff.
        Set<Integer> variables = new LinkedHashSet<>();
        for (int row : queue) variables.addAll(rows.get(row).terms().keySet());
        // A large surrounding DAG must not hide a small conversion cycle.
        // Bound the sparse elimination work, rather than rejecting every
        // pending component as soon as their combined variable count is large.
        long started = budget.nodes();
        Set<ExactLinearProgram.Constraint> known = new HashSet<>(rows);
        int added = 0;
        for (int round = 0; round < 3; round++) {
            int before = added;
            for (int variable : variables) {
                if (budget.nodes() - started >= 65_536) return added > 0;
                List<Integer> positive = new ArrayList<>(), negative = new ArrayList<>();
                for (int row : affected.get(variable)) {
                    budget.check();
                    var terms = rows.get(row).terms();
                    if (terms.size() < 2 || terms.size() > MAX_ELIMINATION_TERMS) continue;
                    (terms.get(variable).signum() > 0 ? positive : negative).add(row);
                }
                if ((long) positive.size() * negative.size() > 16) continue;
                for (int first : positive) for (int second : negative) {
                    budget.check();
                    var consequence = combine(rows.get(first), rows.get(second), variable);
                    if (consequence == null || !known.add(consequence)) continue;
                    long bytes = 128L + 64L * consequence.terms().size();
                    if (!budget.tryReserve(bytes)) return added > 0;
                    memory += bytes;
                    int id = rows.size();
                    rows.add(consequence);
                    if (explain) {
                        BitSet reason = new BitSet();
                        union(reason, rowReasons.get(first));
                        union(reason, rowReasons.get(second));
                        rowReasons.add(reason);
                    }
                    for (int key : consequence.terms().keySet()) affected.get(key).add(id);
                    enqueue(id);
                    if (++added >= 256) return true;
                }
            }
            if (before == added) break;
        }
        return added > 0;
    }

    private ExactLinearProgram.Constraint combine(ExactLinearProgram.Constraint first,
                                                  ExactLinearProgram.Constraint second, int variable) {
        BigInteger a = first.terms().get(variable), b = second.terms().get(variable).negate();
        BigInteger gcd = a.gcd(b);
        a = a.divide(gcd);
        b = b.divide(gcd);
        Map<Integer, BigInteger> terms = new LinkedHashMap<>();
        for (var term : first.terms().entrySet()) {
            budget.check();
            terms.put(term.getKey(), term.getValue().multiply(b));
        }
        for (var term : second.terms().entrySet()) {
            budget.check();
            terms.merge(term.getKey(), term.getValue().multiply(a), BigInteger::add);
        }
        terms.values().removeIf(value -> value.signum() == 0);
        if (terms.size() > MAX_ELIMINATION_TERMS) return null;
        BigInteger upper = first.upper().multiply(b).add(second.upper().multiply(a));
        gcd = BigInteger.ZERO;
        for (BigInteger value : terms.values()) {
            if (value.bitLength() > 2048) return null;
            gcd = gcd.gcd(value);
        }
        if (upper.bitLength() > 2048) return null;
        if (gcd.signum() > 0) {
            BigInteger divisor = gcd;
            terms.replaceAll((key, value) -> value.divide(divisor));
            BigInteger[] division = upper.divideAndRemainder(divisor);
            upper = division[0];
            if (division[1].signum() < 0) upper = upper.subtract(BigInteger.ONE);
        }
        return new ExactLinearProgram.Constraint(terms, upper);
    }

    List<ExactLinearProgram.Constraint> tightened() {
        var result = new ArrayList<ExactLinearProgram.Constraint>();
        for (int i = 0; i < lower.length; i++) {
            if (lower[i].signum() > 0) result.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), lower[i].negate()));
            if (upper[i] != null) result.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), upper[i]));
        }
        return result;
    }

    boolean blocked() {
        return blocked;
    }

    /** A candidate only; callers must check every row after a work cutoff. */
    BigInteger[] lowerBounds() {
        return lower.clone();
    }

    BigInteger[] upperBounds() {
        return upper.clone();
    }

    /** Only branch assumptions used in a proof, never an interrupted search. */
    BitSet conflictingAssumptions() {
        return blocked && conflict != null ? (BitSet) conflict.clone() : null;
    }

    private boolean contradiction(int row) {
        if (explain) conflict = reason(row, -1);
        return finish(true);
    }

    private BitSet reason(int row, int except) {
        BitSet result = new BitSet();
        union(result, rowReasons.get(row));
        for (var term : rows.get(row).terms().entrySet()) {
            charge();
            int id = term.getKey();
            if (id != except) union(result, term.getValue().signum() >= 0 ? lowerReasons[id] : upperReasons[id]);
            // Integer residue tightening also uses the equal lower/upper pair
            // of every eliminated count, even when its coefficient is positive.
            if (lower[id].equals(upper[id])) {
                union(result, lowerReasons[id]);
                union(result, upperReasons[id]);
            }
        }
        return result;
    }

    private static void union(BitSet into, BitSet from) {
        if (from != null) into.or(from);
    }

    private static BigInteger floorDiv(BigInteger value, BigInteger divisor) {
        BigInteger[] parts = value.divideAndRemainder(divisor);
        return parts[1].signum() < 0 ? parts[0].subtract(BigInteger.ONE) : parts[0];
    }

    private void charge() {
        budget.check();
        work++;
    }

    private boolean finish(boolean value) {
        complete = true;
        blocked = value;
        close();
        return true;
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
