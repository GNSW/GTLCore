// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Exact bucket elimination over bounded separators. Tables preserve every
 * extendible interface assignment, not just one local choice. Complexity is
 * admitted by induced domain product; large domains never expand per unit.
 */
final class CountSeparator implements CountContinuation {

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private static final class Bucket {

        final int variable;
        final int[] separator;
        final List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
        final List<Bucket> children = new ArrayList<>();
        final int[] support;
        int cursor;

        Bucket(int variable, int[] separator, int states) {
            this.variable = variable;
            this.separator = separator;
            support = new int[states];
            Arrays.fill(support, -1);
        }
    }

    private final List<ExactLinearProgram.Constraint> original;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private final List<Bucket> buckets = new ArrayList<>();
    private final List<CountConflict> proofSteps = new ArrayList<>();
    private int[] domains, assignment;
    private BigInteger[] counts;
    private long memory, work, states, completedTuples;
    private long allowance;
    private int cursor, maxSeparator;
    private boolean prepared, complete, infeasible;
    private CountInterfaceSearch interfaceSearch;

    CountSeparator(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget) {
        this(rows, lower, upper, budget, 262144);
    }

    CountSeparator(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget, long maxWork) {
        this.original = rows;
        this.lower = lower.clone();
        this.upper = upper.clone();
        this.budget = budget;
        allowance = Math.min(maxWork, budget.remainingWork() / 16);
        long terms = rows.stream().mapToLong(row -> row.terms().size()).sum();
        long bytes = 2048 + 384L * lower.length + 192L * terms + 128L * rows.size() + 8L * lower.length * lower.length;
        if (lower.length > 256 || rows.size() > 2048 || allowance < 1024 || !budget.tryReserve(bytes)) complete = true;
        else memory = bytes;
    }

    public boolean step() {
        if (complete || paused) return true;
        budget.checkpoint();
        if (retaining && work >= allowance) return paused = true;
        try {
            charge();
            if (interfaceSearch != null) {
                long before = budget.threadSearchWork();
                boolean done;
                try { done = interfaceSearch.step(); }
                finally { work += budget.threadSearchWork() - before; }
                if (!done) return false;
                if (interfaceSearch.paused()) return paused = true;
                counts = interfaceSearch.counts();
                interfaceSearch.close();
                interfaceSearch = null;
                return finish(false, counts == null ? "interface_declined" : "interface_witness");
            }
            if (!prepared) {
                prepare();
                return complete;
            }
            if (cursor == buckets.size()) {
                counts = lower.clone();
                for (int i = buckets.size() - 1; i >= 0; i--) {
                    var bucket = buckets.get(i);
                    int value = bucket.support[key(bucket)];
                    if (value < 0) throw new IllegalStateException("Lost separator reconstruction");
                    assignment[bucket.variable] = value;
                    counts[bucket.variable] = lower[bucket.variable].add(BigInteger.valueOf(value));
                }
                for (var row : original) {
                    BigInteger sum = BigInteger.ZERO;
                    for (var term : row.terms().entrySet()) {
                        charge();
                        sum = sum.add(term.getValue().multiply(counts[term.getKey()]));
                    }
                    if (sum.compareTo(row.upper()) > 0) throw new IllegalStateException("Invalid separator witness");
                }
                return finish(false, "verified_witness");
            }
            var bucket = buckets.get(cursor);
            if (bucket.cursor == bucket.support.length) {
                if (bucket.separator.length == 0 && bucket.support[0] < 0) return finish(true, "exhaustive_infeasible");
                cursor++;
                return false;
            }
            int code = bucket.cursor;
            for (int id : bucket.separator) {
                assignment[id] = code % domains[id];
                code /= domains[id];
            }
            for (int value = 0; value < domains[bucket.variable]; value++) {
                charge();
                assignment[bucket.variable] = value;
                boolean legal = true;
                for (var child : bucket.children) if (child.support[key(child)] < 0) {
                    legal = false;
                    break;
                }
                if (legal) for (var row : bucket.rows) {
                    BigInteger total = BigInteger.ZERO;
                    for (var term : row.terms().entrySet()) {
                        charge();
                        total = total.add(term.getValue().multiply(BigInteger.valueOf(assignment[term.getKey()])));
                    }
                    if (total.compareTo(row.upper()) > 0) {
                        legal = false;
                        break;
                    }
                }
                if (legal) {
                    bucket.support[bucket.cursor] = value;
                    break;
                }
                record(bucket, true);
            }
            if (bucket.support[bucket.cursor] < 0) record(bucket, false);
            bucket.cursor++;
            completedTuples++;
            return false;
        } catch (Stop stopped) {
            counts = null;
            return finish(false, "work_limit");
        }
    }

    private int preparing, preparationCursor;
    private BitSet live;
    private BitSet[] graph;
    private int[] rank;
    private final List<ExactLinearProgram.Constraint> shifted = new ArrayList<>();

    private void prepare() {
        if (preparing == 0) {
            boolean wide = false;
            for (int i = 0; i < lower.length; i++) {
                charge();
                if (upper[i] != null && upper[i].compareTo(lower[i]) < 0) {
                    finish(true, "empty_domain");
                    return;
                }
                wide |= upper[i] == null || upper[i].subtract(lower[i]).compareTo(BigInteger.valueOf(31)) > 0;
            }
            if (wide) {
                interfaceSearch = new CountInterfaceSearch(original, lower, upper, budget,
                        retaining ? Math.max(4096, allowance - work) : allowance - work);
                if (retaining) interfaceSearch.retained();
                prepared = true;
                return;
            }
            domains = new int[lower.length];
            assignment = new int[lower.length];
            live = new BitSet();
            graph = new BitSet[lower.length];
            for (int i = 0; i < lower.length; i++) {
                charge();
                graph[i] = new BitSet();
                if (upper[i] == null) {
                    finish(false, "unbounded_domain");
                    return;
                }
                BigInteger width = upper[i].subtract(lower[i]);
                if (width.signum() < 0) {
                    finish(true, "empty_domain");
                    return;
                }
                if (width.compareTo(BigInteger.valueOf(31)) > 0) {
                    finish(false, "wide_domain");
                    return;
                }
                domains[i] = width.intValueExact() + 1;
                if (domains[i] > 1) live.set(i);
            }
            rank = new int[lower.length];
            Arrays.fill(rank, -1);
            preparing = 1;
            return;
        }
        if (preparing == 1) {
            if (preparationCursor == original.size()) {
                preparing = 2;
                return;
            }
            var row = original.get(preparationCursor++);
            Map<Integer, BigInteger> terms = new LinkedHashMap<>();
            BigInteger bound = row.upper();
            for (var term : row.terms().entrySet()) {
                charge();
                int id = term.getKey();
                bound = bound.subtract(term.getValue().multiply(lower[id]));
                if (domains[id] > 1 && term.getValue().signum() != 0) terms.put(id, term.getValue());
            }
            if (terms.isEmpty()) {
                if (bound.signum() < 0) {
                    finish(true, "fixed_infeasible");
                    return;
                }
                return;
            }
            for (int a : terms.keySet()) for (int b : terms.keySet()) {
                charge();
                if (a != b) graph[a].set(b);
            }
            shifted.add(new ExactLinearProgram.Constraint(terms, bound));
            return;
        }
        if (!live.isEmpty()) {
            int best = -1;
            long least = Long.MAX_VALUE;
            for (int id = live.nextSetBit(0); id >= 0; id = live.nextSetBit(id + 1)) {
                charge();
                long size = domains[id];
                for (int next = graph[id].nextSetBit(0); next >= 0 && size <= 65536; next = graph[id].nextSetBit(next + 1)) {
                    charge();
                    size *= domains[next];
                }
                if (size < least) {
                    least = size;
                    best = id;
                }
            }
            if (least > 65536 || states + least > 131072) {
                finish(false, "separator_cost_limit");
                return;
            }
            states += least;
            int[] separator = graph[best].stream().toArray();
            if (separator.length > 6) {
                finish(false, "separator_width_limit");
                return;
            }
            maxSeparator = Math.max(maxSeparator, separator.length);
            long bytes = 256L + 4L * (least / domains[best]) + 32L * separator.length;
            if (!budget.tryReserve(bytes)) {
                finish(false, "table_memory_limit");
                return;
            }
            memory += bytes;
            rank[best] = buckets.size();
            buckets.add(new Bucket(best, separator, (int) (least / domains[best])));
            live.clear(best);
            for (int a : separator) {
                graph[a].clear(best);
                for (int b : separator) {
                    charge();
                    if (a != b) graph[a].set(b);
                }
            }
            return;
        }
        for (var row : shifted) {
            int first = row.terms().keySet().stream().mapToInt(id -> rank[id]).min().orElseThrow();
            buckets.get(first).rows.add(row);
        }
        for (var bucket : buckets) if (bucket.separator.length > 0) {
            int first = Arrays.stream(bucket.separator).map(id -> rank[id]).min().orElseThrow();
            buckets.get(first).children.add(bucket);
        }
        long predictedWork = work;
        for (var bucket : buckets) {
            long perTuple = 4L + bucket.rows.stream().mapToLong(row -> row.terms().size()).sum() +
                    bucket.children.stream().mapToLong(child -> child.separator.length + 1L).sum();
            predictedWork += (long) bucket.support.length * domains[bucket.variable] * perTuple;
        }
        if (!retaining && predictedWork > allowance) {
            finish(false, "separator_work_estimate");
            return;
        }
        prepared = true;
        live = null;
        graph = null;
        rank = null;
        shifted.clear();
        budget.note("count_separator", "admitted; variables=" + buckets.size() + "; width=" + maxSeparator + "; projected_states=" + states);
    }

    private int key(Bucket bucket) {
        int code = 0, scale = 1;
        for (int id : bucket.separator) {
            charge();
            code += assignment[id] * scale;
            scale *= domains[id];
        }
        return code;
    }

    private void record(Bucket bucket, boolean includeVariable) {
        if (budget.proofJournal() == null) return;
        List<ExactLinearProgram.Constraint> fixed = new ArrayList<>();
        for (int id : bucket.separator) fix(fixed, id);
        if (includeVariable) fix(fixed, bucket.variable);
        long bytes = 128L + 192L * fixed.size();
        if (!budget.tryReserve(bytes)) throw new Stop();
        memory += bytes;
        proofSteps.add(new CountConflict(fixed));
    }

    private void fix(List<ExactLinearProgram.Constraint> rows, int id) {
        BigInteger value = lower[id].add(BigInteger.valueOf(assignment[id]));
        rows.add(new ExactLinearProgram.Constraint(Map.of(id, BigInteger.ONE), value));
        rows.add(new ExactLinearProgram.Constraint(Map.of(id, BigInteger.ONE.negate()), value.negate()));
    }

    private boolean finish(boolean impossible, String reason) {
        complete = true;
        infeasible = impossible;
        if (budget.proofJournal() != null) {
            var scope = new ArrayList<>(original);
            for (int i = 0; i < lower.length; i++) {
                scope.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE.negate()), lower[i].negate()));
                if (upper[i] != null) scope.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), upper[i]));
            }
            budget.proofJournal().add(CountProof.certificate("separator:" + reason, lower.length, scope, proofSteps, null, impossible));
        }
        budget.note("count_separator", reason + "; width=" + maxSeparator + "; projected_states=" + states + "; work=" + work);
        return true;
    }

    private void charge() {
        budget.check();
        if (++work > allowance && !retaining) throw new Stop();
    }

    public BigInteger[] counts() {
        return counts == null ? null : counts.clone();
    }

    boolean infeasible() {
        return infeasible;
    }

    private boolean retaining, paused;

    CountSeparator retained() { retaining = true; return this; }
    public boolean paused() { return paused; }
    public long work() { return work; }
    public long progress() { return completedTuples; }
    public boolean matches(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {
        return original.equals(rows) && Arrays.equals(lower, low) && Arrays.equals(upper, high);
    }
    public void resume(long quantum) {
        if (!paused || complete) throw new IllegalStateException("CountSeparator is not paused");
        allowance = CountContinuation.deadline(work, quantum, budget);
        if (interfaceSearch != null && interfaceSearch.paused()) interfaceSearch.resume(quantum);
        paused = false;
    }

    @Override
    public void close() {
        complete = true;
        paused = false;
        if (interfaceSearch != null) interfaceSearch.close();
        interfaceSearch = null;
        budget.release(memory);
        memory = 0;
    }
}
