// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Sparse pseudo-Boolean witness search after integer bounds. Propagation keeps
 * exact weighted rows; conflicts explain themselves in terms of decisions and
 * backjump over unrelated choices. Trial fixed counts and local cutoffs never
 * become infeasibility proofs for the parent integer model.
 */
final class CountBoolean implements CountContinuation {

    private record Row(int[] variables, BigInteger[] coefficients, BigInteger upper, Integer cardinality) {

        Row(int[] variables, BigInteger[] coefficients, BigInteger upper) {
            this(variables, coefficients, upper, cardinality(coefficients, upper));
        }

        private static Integer cardinality(BigInteger[] coefficients, BigInteger upper) {
            int negative = 0;
            for (BigInteger coefficient : coefficients) {
                if (!coefficient.abs().equals(BigInteger.ONE)) return null;
                if (coefficient.signum() < 0) negative++;
            }
            return upper.add(BigInteger.valueOf(negative)).max(BigInteger.ONE.negate())
                    .min(BigInteger.valueOf(coefficients.length + 1L)).intValueExact();
        }
    }

    private final List<ExactLinearProgram.Constraint> original;
    private final List<Row> rows = new ArrayList<>();
    private final List<List<Integer>> affected = new ArrayList<>();
    private final Deque<Integer> queue = new ArrayDeque<>();
    private final BitSet queued = new BitSet();
    private final List<Integer> trail = new ArrayList<>();
    private final PlanningBudget budget;
    private final BigInteger[] fixed, originalLower, originalUpper;
    private final int[] values, levels;
    private final BitSet[] reasons;
    private final double[] activity, polarity, positiveActivity, negativeActivity;
    private long allowance;
    private boolean retaining, paused;
    private long memory, work;
    private int rowIndex, level, decisions, conflicts, pinned;
    private boolean complete, infeasible;
    private BigInteger[] counts;
    private final List<CountConflict> learned = new ArrayList<>();
    private final List<CountConflict> proofSteps = new ArrayList<>();
    private List<ExactLinearProgram.Constraint> proofScope;

    /** Unit-weight Boolean rows need propagation before expensive integer factoring. */
    static boolean preferred(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                             BigInteger[] upper, PlanningBudget budget) {
        if (lower.length > 1024) return false;
        int choices = 0;
        for (int i = 0; i < lower.length; i++) {
            budget.check();
            if (lower[i].equals(upper[i])) continue;
            if (lower[i].signum() != 0 || !BigInteger.ONE.equals(upper[i])) return false;
            choices++;
        }
        if (choices < 8) return false;
        for (var row : rows) for (var entry : row.terms().entrySet()) {
            budget.check();
            int id = entry.getKey();
            if (!lower[id].equals(upper[id]) && entry.getValue().abs().compareTo(BigInteger.ONE) > 0) return false;
        }
        return true;
    }

    CountBoolean(List<ExactLinearProgram.Constraint> constraints, BigInteger[] lower,
                 BigInteger[] upper, PlanningBudget budget) {
        original = constraints;
        originalLower = lower.clone();
        originalUpper = upper.clone();
        this.budget = budget;
        fixed = lower.clone();
        values = new int[lower.length];
        levels = new int[lower.length];
        reasons = new BitSet[lower.length];
        activity = new double[lower.length];
        polarity = new double[lower.length];
        positiveActivity = new double[lower.length];
        negativeActivity = new double[lower.length];
        Arrays.fill(values, -1);
        allowance = Math.min(262_144, budget.remainingWork() / 8);
        if (lower.length > 1024 || !CountPartition.binaryChoices(lower, upper, 1) || allowance < 1024) {
            complete = true;
            return;
        }
        long entries = constraints.stream().mapToLong(row -> row.terms().size()).sum();
        long bytes = 1024 + 128L * entries + 192L * constraints.size() + 256L * lower.length;
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
        if (budget.proofJournal() != null) {
            proofScope = new ArrayList<>(constraints);
            for (int i = 0; i < lower.length; i++) {
                proofScope.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), lower[i].negate()));
                if (upper[i] != null) proofScope.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), upper[i]));
            }
        }
        for (int i = 0; i < lower.length; i++) {
            affected.add(new ArrayList<>());
            if (!lower[i].equals(upper[i]) && lower[i].signum() == 0) fixed[i] = null;
            else if (!lower[i].equals(upper[i])) pinned++;
        }
    }

    public boolean step() {
        if (complete || paused) return true;
        charge();
        if (work >= allowance) {
            if (retaining) return paused = true;
            return finish("work_limit");
        }
        if (rowIndex < original.size()) {
            var row = original.get(rowIndex++);
            var variables = new ArrayList<Integer>();
            var coefficients = new ArrayList<BigInteger>();
            BigInteger upper = row.upper();
            for (var term : row.terms().entrySet()) {
                charge();
                int id = term.getKey();
                if (fixed[id] != null) upper = upper.subtract(term.getValue().multiply(fixed[id]));
                else if (term.getValue().signum() != 0) {
                    variables.add(id);
                    coefficients.add(term.getValue());
                    // Complementary sources remain one decision after compiling
                    // them. Score its strongest literal, not their summed degree.
                    if (term.getValue().signum() > 0) positiveActivity[id] += 1.0 / Math.max(1, row.terms().size());
                    else negativeActivity[id] += 1.0 / Math.max(1, row.terms().size());
                    activity[id] = Math.max(positiveActivity[id], negativeActivity[id]);
                    polarity[id] -= term.getValue().signum() / (double) Math.max(1, row.terms().size());
                }
            }
            add(new Row(variables.stream().mapToInt(Integer::intValue).toArray(),
                    coefficients.toArray(BigInteger[]::new), upper));
            return false;
        }
        if (!queue.isEmpty()) {
            int id = queue.removeFirst();
            queued.clear(id);
            propagate(rows.get(id));
            return complete;
        }
        int next = -1;
        for (int i = 0; i < values.length; i++) {
            charge();
            if (fixed[i] == null && values[i] < 0 && (next < 0 || activity[i] > activity[next])) next = i;
        }
        int required = -1, smallest = Integer.MAX_VALUE;
        for (Row row : rows) {
            // Branch on the most constrained unmet positive clause. This is
            // also the exact-cover choice exposed by saturated resource rows.
            if (row.cardinality() == null || row.cardinality() != row.variables().length - 1 ||
                    Arrays.stream(row.coefficients()).anyMatch(v -> v.signum() >= 0))
                continue;
            int live = 0, candidate = -1;
            boolean satisfied = false;
            for (int id : row.variables()) {
                charge();
                if (values[id] == 1) {
                    satisfied = true;
                    break;
                }
                if (values[id] < 0) {
                    live++;
                    if (candidate < 0 || activity[id] > activity[candidate] || activity[id] == activity[candidate] && id < candidate) candidate = id;
                }
            }
            // Equal domain sizes still differ in how strongly they constrain
            // the rest of the model. Taking the first row here discarded the
            // conflict/activity score and made shared-resource contradictions
            // depend on the input recipe order.
            if (!satisfied && candidate >= 0 && (live < smallest || live == smallest &&
                    (required < 0 || activity[candidate] > activity[required] ||
                            activity[candidate] == activity[required] && candidate < required))) {
                smallest = live;
                required = candidate;
            }
        }
        if (required >= 0) next = required;
        if (next >= 0) {
            level++;
            decisions++;
            int value = required >= 0 || polarity[next] >= 0 ? 1 : 0;
            BitSet reason = new BitSet();
            reason.set(2 * next + value);
            assign(next, value, reason);
            return false;
        }
        BigInteger[] candidate = fixed.clone();
        for (int i = 0; i < candidate.length; i++) if (candidate[i] == null) candidate[i] = BigInteger.valueOf(values[i]);
        // Check the untouched model, including every coupled resource row.
        for (var row : original) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                charge();
                sum = sum.add(term.getValue().multiply(candidate[term.getKey()]));
            }
            if (sum.compareTo(row.upper()) > 0) throw new IllegalStateException("Boolean count witness violates original row");
        }
        counts = candidate;
        return finish("witness");
    }

    private void propagate(Row row) {
        if (row.cardinality() != null) {
            propagateCardinality(row);
            return;
        }
        BigInteger minimum = BigInteger.ZERO;
        BitSet explanation = new BitSet();
        for (int i = 0; i < row.variables().length; i++) {
            charge();
            int id = row.variables()[i], value = values[id];
            BigInteger coefficient = row.coefficients()[i];
            minimum = minimum.add(value < 0 ? coefficient.min(BigInteger.ZERO) : coefficient.multiply(BigInteger.valueOf(value)));
            // Only assignments raising the optimistic minimum need explaining.
            if (value >= 0 && value == (coefficient.signum() > 0 ? 1 : 0)) explanation.or(reasons[id]);
        }
        BigInteger slack = row.upper().subtract(minimum);
        if (slack.signum() < 0) {
            learn(conflictReason(row));
            return;
        }
        for (int i = 0; i < row.variables().length; i++) {
            charge();
            int id = row.variables()[i];
            BigInteger coefficient = row.coefficients()[i];
            if (values[id] < 0 && coefficient.abs().compareTo(slack) > 0)
                assign(id, coefficient.signum() > 0 ? 0 : 1, explanation);
        }
    }

    private void propagateCardinality(Row row) {
        int trueCount = 0;
        BitSet explanation = new BitSet();
        for (int i = 0; i < row.variables().length; i++) {
            charge();
            int id = row.variables()[i];
            if (values[id] == (row.coefficients()[i].signum() > 0 ? 1 : 0)) {
                trueCount++;
                explanation.or(reasons[id]);
            }
        }
        if (trueCount > row.cardinality()) {
            learn(conflictReason(row));
            return;
        }
        if (trueCount != row.cardinality()) return;
        for (int i = 0; i < row.variables().length; i++) {
            charge();
            int id = row.variables()[i];
            if (values[id] < 0) assign(id, row.coefficients()[i].signum() > 0 ? 0 : 1, explanation);
        }
    }

    /** A subset of expensive assignments already suffices to violate this row. */
    private BitSet conflictReason(Row row) {
        BigInteger minimum = BigInteger.ZERO;
        List<Integer> expensive = new ArrayList<>();
        for (int i = 0; i < row.variables().length; i++) {
            charge();
            BigInteger coefficient = row.coefficients()[i];
            minimum = minimum.add(coefficient.min(BigInteger.ZERO));
            if (values[row.variables()[i]] == (coefficient.signum() > 0 ? 1 : 0)) expensive.add(i);
        }
        expensive.sort(Comparator.<Integer, BigInteger>comparing(i -> row.coefficients()[i].abs()).reversed()
                .thenComparingInt(i -> reasons[row.variables()[i]].cardinality()));
        BitSet reason = new BitSet();
        for (int i : expensive) {
            if (minimum.compareTo(row.upper()) > 0) break;
            charge();
            minimum = minimum.add(row.coefficients()[i].abs());
            reason.or(reasons[row.variables()[i]]);
        }
        return reason;
    }

    private void assign(int variable, int value, BitSet explanation) {
        values[variable] = value;
        levels[variable] = level;
        reasons[variable] = (BitSet) explanation.clone();
        trail.add(variable);
        affected.get(variable).forEach(this::enqueue);
    }

    private void learn(BitSet explanation) {
        conflicts++;
        if (learned.size() < 128 || proofScope != null) {
            List<ExactLinearProgram.Constraint> premises = new ArrayList<>();
            for (int i = 0; i < fixed.length; i++) if (fixed[i] != null) {
                premises.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), fixed[i]));
                premises.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), fixed[i].negate()));
            }
            for (int bit = explanation.nextSetBit(0); bit >= 0; bit = explanation.nextSetBit(bit + 1)) {
                boolean one = (bit & 1) == 1;
                premises.add(new ExactLinearProgram.Constraint(Map.of(bit / 2, one ? BigInteger.ONE.negate() : BigInteger.ONE), one ? BigInteger.ONE.negate() : BigInteger.ZERO));
            }
            long bytes = 128L + 144L * premises.size();
            if (budget.tryReserve(bytes)) {
                memory += bytes;
                CountConflict conflict = new CountConflict(premises);
                if (learned.size() < 128) learned.add(conflict);
                if (proofScope != null) proofSteps.add(conflict);
            } else if (proofScope != null) {
                budget.proofJournal().markIncomplete();
            }
        }
        if (explanation.isEmpty()) {
            // The empty explanation closes the searched Boolean domain. Only
            // a full domain (no trial-fixed positive counts) closes this branch
            // of the original integer model as well.
            infeasible = pinned == 0;
            finish(infeasible ? "proven_infeasible" : "candidate_face_blocked");
            return;
        }
        if (conflicts > 512) {
            finish("conflict_limit");
            return;
        }
        long bytes = 192L + 128L * explanation.cardinality();
        if (!budget.tryReserve(bytes)) {
            finish("memory_limit");
            return;
        }
        memory += bytes;
        int highest = 0, previous = 0, ones = 0, index = 0;
        int[] variables = new int[explanation.cardinality()];
        BigInteger[] coefficients = new BigInteger[variables.length];
        for (int bit = explanation.nextSetBit(0); bit >= 0; bit = explanation.nextSetBit(bit + 1)) {
            charge();
            int id = bit / 2, at = levels[id];
            if (at > highest) {
                previous = highest;
                highest = at;
            } else if (at > previous) previous = at;
            variables[index] = id;
            coefficients[index++] = (bit & 1) == 1 ? BigInteger.ONE : BigInteger.ONE.negate();
            ones += bit & 1;
            activity[id] += 1;
        }
        // Explanations contain decisions only, hence at most one per level.
        // Keep unrelated earlier decisions; the learned row forces the last
        // relevant choice in the opposite direction after the backjump.
        level = previous;
        while (!trail.isEmpty() && levels[trail.get(trail.size() - 1)] > level) {
            int id = trail.remove(trail.size() - 1);
            values[id] = -1;
            levels[id] = 0;
            reasons[id] = null;
            affected.get(id).forEach(this::enqueue);
        }
        add(new Row(variables, coefficients, BigInteger.valueOf(ones - 1L)));
    }

    private void add(Row row) {
        int id = rows.size();
        rows.add(row);
        for (int variable : row.variables()) affected.get(variable).add(id);
        enqueue(id);
    }

    private void enqueue(int row) {
        if (!queued.get(row)) {
            queued.set(row);
            queue.addLast(row);
        }
    }

    private void charge() {
        budget.check();
        work++;
    }

    private boolean finish(String detail) {
        if (!complete && proofScope != null) {
            budget.proofJournal().add(CountProof.certificate("boolean_domain:" + detail, values.length, proofScope, proofSteps, null, infeasible));
        }
        complete = true;
        budget.note("count_boolean", detail + "; variables=" + values.length + "; rows=" + original.size() +
                "; cardinality_rows=" + rows.stream().filter(row -> row.cardinality() != null).count() +
                "; trial_counts=" + pinned + "; decisions=" + decisions + "; learned=" + conflicts + "; work=" + work);
        return true;
    }

    public BigInteger[] counts() {
        return counts == null ? null : counts.clone();
    }

    List<CountConflict> learnedConflicts() {
        return List.copyOf(learned);
    }

    boolean infeasible() {
        return complete && infeasible;
    }

    CountBoolean retained() { retaining = true; return this; }
    public boolean paused() { return paused; }
    public long work() { return work; }
    public long progress() { return conflicts; }
    public boolean matches(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {
        return original.equals(rows) && Arrays.equals(originalLower, low) && Arrays.equals(originalUpper, high);
    }
    public void resume(long quantum) {
        if (!paused || complete) throw new IllegalStateException("Boolean search is not paused");
        allowance = CountContinuation.deadline(work, quantum, budget);
        paused = false;
    }

    @Override
    public void close() {
        complete = true;
        paused = false;
        budget.release(memory);
        memory = 0;
    }
}
