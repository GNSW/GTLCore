// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Condition a small cutset, then solve the independent, possibly unbounded
 * integer blocks with retained LCG and integer-kernel repair. Only count witnesses leave this neighborhood;
 * local failures never become global conflicts or execution-order proofs.
 * Component memo entries belong to this exact model and their interface values.
 */
final class CountInterfaceSearch implements AutoCloseable {

    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }

    private static final class Block {
        final int[] variables;
        final List<ExactLinearProgram.Constraint> rows = new ArrayList<>();
        int[] interfaceVariables;
        final Map<List<BigInteger>, Local> answers = new HashMap<>();

        Block(int[] variables) { this.variables = variables; }
    }

    /** An unfinished local search is distinct from a proved local contradiction. */
    private static final class Local {
        CountConditionalSearch search;
        BigInteger[] values;
        boolean impossible, retryable;
        int round = -1;
        long quantum;
    }

    private final List<ExactLinearProgram.Constraint> original;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private long allowance;
    private boolean retaining, paused;
    private final List<Block> blocks = new ArrayList<>();
    private final List<ExactLinearProgram.Constraint> interfaceRows = new ArrayList<>();
    private final Deque<Local> suspended = new ArrayDeque<>();
    private final BitSet pending = new BitSet(), retry = new BitSet();
    private BigInteger[] assignment, counts;
    private int[] cutset, domains;
    private Local solving;
    private int states = 1, state, cursor, round;
    private long memory, work, stepStarted, hits, calls, unknown, rejected, resumed, evicted;
    private long retentionFloor;
    private boolean prepared, tupleStarted, complete, retryVisit;

    CountInterfaceSearch(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                         PlanningBudget budget, long maximumWork) {
        original = rows;
        this.lower = lower.clone();
        this.upper = upper.clone();
        this.budget = budget;
        allowance = Math.min(maximumWork, budget.remainingWork() / 16);
        long terms = rows.stream().mapToLong(row -> row.terms().size()).sum();
        long bytes = 2048L + 448L * lower.length + 192L * terms + 128L * rows.size() + 8L * lower.length * lower.length;
        if (lower.length > 256 || rows.size() > 2048 || allowance < 4096 || !budget.tryReserve(bytes)) complete = true;
        else memory = bytes;
    }

    boolean step() {
        if (complete || paused) return true;
        budget.checkpoint();
        if (retaining && work >= allowance) return paused = true;
        stepStarted = budget.threadSearchWork();
        try {
            charge();
            if (!prepared) {
                prepared = true;
                prepare();
                return complete;
            }
            if (solving != null) {
                if (!solving.search.step()) return false;
                var local = solving;
                var values = local.search.counts();
                local.impossible = local.search.infeasible();
                local.retryable = local.search.paused();
                solving = null;
                var block = blocks.get(cursor);
                if (local.retryable) {
                    // Retain the trail, clauses and propagation queue. A pause
                    // does not eliminate this tuple or any equivalent local key.
                    suspended.addLast(local);
                    trimSuspended();
                    retry.set(state);
                } else {
                    local.search.close();
                    local.search = null;
                    if (values != null) remember(local, values);
                }
                if (values == null) {
                    if (local.impossible) rejected++; else unknown++;
                    nextTuple();
                } else {
                    apply(block, values);
                    cursor++;
                }
                return false;
            }
            if (state < 0) {
                if (retry.isEmpty()) return finish(unknown == 0 ? "interfaces_exhausted" : "interfaces_incomplete");
                pending.or(retry);
                retry.clear();
                round++;
                retryVisit = false;
                state = pending.nextSetBit(0);
                return false;
            }
            if (!tupleStarted) {
                assignment = lower.clone();
                int code = state;
                for (int i = 0; i < cutset.length; i++) {
                    charge();
                    assignment[cutset[i]] = lower[cutset[i]].add(BigInteger.valueOf(code % domains[i]));
                    code /= domains[i];
                }
                tupleStarted = true;
                if (!valid(interfaceRows, assignment)) {
                    rejected++;
                    nextTuple();
                    return false;
                }
            }
            if (cursor == blocks.size()) {
                if (!valid(original, assignment)) throw new IllegalStateException("Invalid interface composition");
                counts = assignment.clone();
                return finish("witness");
            }
            var block = blocks.get(cursor);
            var key = Arrays.stream(block.interfaceVariables).mapToObj(i -> assignment[i]).toList();
            var answer = block.answers.get(key);
            if (answer != null && (answer.values != null || answer.impossible)) {
                hits++;
                if (answer.impossible) { rejected++; nextTuple(); }
                else { apply(block, answer.values); cursor++; }
                return false;
            }
            if (answer != null && (!answer.retryable || answer.round == round)) {
                // Each distinct local condition receives at most one visit per
                // round, regardless of how many global tuples share that key.
                if (answer.retryable) retry.set(state);
                nextTuple();
                return false;
            }
            if (answer == null) {
                long bytes = 192L + 64L * key.size();
                for (var value : key) bytes += (value.bitLength() + 7L) / 8;
                if (!budget.tryReserve(bytes)) throw new Stop();
                memory += bytes;
                answer = new Local();
                block.answers.put(key, answer);
            }
            begin(block, answer);
            return false;
        } catch (Stop stopped) {
            return finish("local_limit");
        } finally {
            work += budget.threadSearchWork() - stepStarted;
        }
    }

    private void prepare() {
        BitSet live = new BitSet();
        BitSet[] graph = new BitSet[lower.length];
        for (int i = 0; i < lower.length; i++) {
            charge();
            graph[i] = new BitSet();
            if (upper[i] != null && upper[i].compareTo(lower[i]) < 0) { finish("empty_domain"); return; }
            if (!lower[i].equals(upper[i])) live.set(i);
        }
        for (var row : original) {
            var support = new BitSet();
            for (var term : row.terms().entrySet()) {
                charge();
                if (live.get(term.getKey()) && term.getValue().signum() != 0) support.set(term.getKey());
            }
            for (int id = support.nextSetBit(0); id >= 0; id = support.nextSetBit(id + 1)) {
                charge();
                graph[id].or(support);
                graph[id].clear(id);
            }
        }
        int initialSize = live.cardinality();
        var removed = new ArrayList<Integer>();
        var parts = components(graph, live);
        while (!useful(parts, initialSize)) {
            if (removed.size() == 3) { finish("no_small_interface"); return; }
            int[] eligible = new int[lower.length];
            int candidates = 0;
            long edges = 0;
            for (int id = live.nextSetBit(0); id >= 0; id = live.nextSetBit(id + 1)) {
                charge();
                edges += graph[id].cardinality();
                if (upper[id] == null) continue;
                var width = upper[id].subtract(lower[id]);
                if (width.signum() < 0 || width.compareTo(BigInteger.valueOf(31)) > 0) continue;
                int domain = width.intValueExact() + 1;
                if (domain > 256 / states) continue;
                eligible[id] = domain;
                candidates++;
            }
            if (candidates == 0) { finish("no_small_interface"); return; }
            // Score all deletions with low links when that avoids repeated
            // traversals. For one eligible vertex or a dense graph, the old
            // bitset connectivity pass can be cheaper than visiting every edge.
            int size = live.cardinality();
            int[] largestParts = 2L * candidates * size > 3L * size + edges ?
                    CountCutset.largestParts(graph, live, this::charge) : null;
            int best = -1, bestLargest = Integer.MAX_VALUE, bestDegree = -1, bestDomain = 0;
            for (int id = live.nextSetBit(0); id >= 0; id = live.nextSetBit(id + 1)) {
                if (eligible[id] == 0) continue;
                charge();
                int largest;
                if (largestParts == null) {
                    live.clear(id);
                    largest = largest(components(graph, live));
                    live.set(id);
                } else largest = largestParts[id];
                int degree = graph[id].cardinality(), domain = eligible[id];
                if (largest < bestLargest || largest == bestLargest && (degree > bestDegree ||
                        degree == bestDegree && domain < bestDomain)) {
                    best = id;
                    bestLargest = largest;
                    bestDegree = degree;
                    bestDomain = domain;
                }
            }
            if (best < 0) { finish("no_small_interface"); return; }
            live.clear(best);
            removed.add(best);
            states *= bestDomain;
            parts = components(graph, live);
        }
        cutset = removed.stream().mapToInt(i -> i).toArray();
        domains = Arrays.stream(cutset).map(i -> upper[i].subtract(lower[i]).intValueExact() + 1).toArray();
        int[] owner = new int[lower.length];
        Arrays.fill(owner, -1);
        for (var part : parts) {
            for (int id : part) owner[id] = blocks.size();
            blocks.add(new Block(part));
        }
        for (var row : original) {
            int destination = -1;
            for (var term : row.terms().entrySet()) {
                charge();
                int next = owner[term.getKey()];
                if (term.getValue().signum() == 0 || next < 0) continue;
                if (destination >= 0 && destination != next) throw new IllegalStateException("Coupled interface blocks");
                destination = next;
            }
            if (destination < 0) interfaceRows.add(row);
            else blocks.get(destination).rows.add(row);
        }
        for (var block : blocks) {
            BitSet touched = new BitSet();
            for (var row : block.rows) for (var term : row.terms().entrySet()) {
                charge();
                if (term.getValue().signum() != 0) touched.set(term.getKey());
            }
            block.interfaceVariables = Arrays.stream(cutset).filter(touched::get).toArray();
        }
        pending.set(0, states);
        retentionFloor = budget.availableBytes() / 2;
        budget.note("count_interface", "admitted; interface=" + cutset.length + "; states=" + states +
                "; blocks=" + blocks.size() + "; largest=" + largest(parts));
    }

    private boolean useful(List<int[]> parts, int initialSize) {
        return parts.size() >= 2 && largest(parts) * 4 <= initialSize * 3;
    }

    private static int largest(List<int[]> parts) {
        return parts.stream().mapToInt(part -> part.length).max().orElse(0);
    }

    private List<int[]> components(BitSet[] graph, BitSet live) {
        var unseen = (BitSet) live.clone();
        var result = new ArrayList<int[]>();
        while (!unseen.isEmpty()) {
            BitSet part = new BitSet(), pending = new BitSet();
            pending.set(unseen.nextSetBit(0));
            while (!pending.isEmpty()) {
                charge();
                int id = pending.nextSetBit(0);
                pending.clear(id);
                if (!unseen.get(id)) continue;
                unseen.clear(id);
                part.set(id);
                pending.or(graph[id]);
                pending.and(unseen);
            }
            result.add(part.stream().toArray());
        }
        return result;
    }

    private void begin(Block block, Local local) {
        long remaining = retaining ? Math.max(1024, allowance - used()) : allowance - used();
        long fair = Math.max(4096, remaining / Math.max(1, blocks.size() + pending.cardinality()));
        long doubled = local.quantum > remaining / 2 ? remaining : local.quantum * 2;
        // A retained frontier needs another fair slice, not an exponentially
        // larger one. Only an evicted/restarted search must cover its old prefix.
        long quantum = Math.min(remaining, local.search == null ? Math.max(fair, doubled) : fair);
        if (quantum < 1024) throw new Stop();
        local.quantum = quantum;
        local.round = round;
        if (local.search != null) {
            suspended.remove(local);
            local.search.resume(quantum);
            solving = local;
            resumed++;
            return;
        }
        trimSuspended();
        int[] ids = new int[lower.length];
        Arrays.fill(ids, -1);
        BigInteger[] low = new BigInteger[block.variables.length], high = new BigInteger[low.length];
        for (int i = 0; i < low.length; i++) {
            charge();
            int id = block.variables[i];
            ids[id] = i;
            low[i] = lower[id];
            high[i] = upper[id];
        }
        var rows = new ArrayList<ExactLinearProgram.Constraint>();
        for (var row : block.rows) {
            Map<Integer, BigInteger> terms = new LinkedHashMap<>();
            BigInteger bound = row.upper();
            for (var term : row.terms().entrySet()) {
                charge();
                int id = term.getKey();
                if (term.getValue().signum() == 0) continue;
                if (ids[id] >= 0) terms.put(ids[id], term.getValue());
                else {
                    integerCost(term.getValue(), assignment[id]);
                    bound = bound.subtract(term.getValue().multiply(assignment[id]));
                }
            }
            rows.add(new ExactLinearProgram.Constraint(terms, bound));
        }
        quantum = Math.min(quantum, retaining ? Math.max(1024, allowance - used()) : allowance - used());
        if (quantum < 1024) throw new Stop();
        local.search = new CountConditionalSearch(rows, low, high, budget, quantum);
        solving = local;
        calls++;
    }

    private void trimSuspended() {
        // Optional reuse must leave room for other portfolio arms. A fixed
        // entry count evicted an entire early interface round even when its
        // small factorizations comfortably fit. Bound retained state by the
        // shared byte account; the finite interface enumeration bounds keys.
        // Eviction discards only a frontier, never proving infeasibility.
        while (!suspended.isEmpty() && budget.availableBytes() < retentionFloor) {
            var local = suspended.removeFirst();
            local.search.close();
            local.search = null;
            evicted++;
        }
    }

    private void remember(Local local, BigInteger[] values) {
        long bytes = 32L + 48L * values.length;
        for (var value : values) bytes += (value.bitLength() + 7L) / 8;
        if (!budget.tryReserve(bytes)) return;
        memory += bytes;
        local.values = values;
    }

    private void apply(Block block, BigInteger[] values) {
        for (int i = 0; i < values.length; i++) {
            charge();
            int id = block.variables[i];
            if (values[i].compareTo(lower[id]) < 0 || upper[id] != null && values[i].compareTo(upper[id]) > 0)
                throw new IllegalStateException("Invalid interface domain");
            assignment[id] = values[i];
        }
    }

    private boolean valid(List<ExactLinearProgram.Constraint> rows, BigInteger[] values) {
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                charge();
                integerCost(term.getValue(), values[term.getKey()]);
                sum = sum.add(term.getValue().multiply(values[term.getKey()]));
            }
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private void nextTuple() {
        pending.clear(state);
        // Interleave a continuation with fresh interfaces. Waiting for every
        // first visit could spend the entire local cap on factorizations and
        // never take even one move in any retained kernel. Fresh interfaces
        // keep alternate turns, so an inconclusive early tuple cannot monopolize.
        if (!retryVisit && !retry.isEmpty()) {
            state = retry.nextSetBit(0);
            retry.clear(state);
            round++;
            retryVisit = true;
        } else {
            state = pending.nextSetBit(0);
            retryVisit = false;
        }
        cursor = 0;
        tupleStarted = false;
    }
    private long used() { return work + budget.threadSearchWork() - stepStarted; }
    private void charge() {
        budget.checkpoint();
        if (!retaining && used() >= allowance) throw new Stop();
        budget.check();
    }
    private void integerCost(BigInteger a, BigInteger b) {
        budget.operation(PlanningBudget.Operation.INTEGER, Math.max(a.bitLength(), b.bitLength()));
    }
    private boolean finish(String reason) {
        complete = true;
        budget.note("count_interface", reason + "; calls=" + calls + "; hits=" + hits + "; rejected=" + rejected +
                "; unknown=" + unknown + "; resumed=" + resumed + "; evicted=" + evicted + "; rounds=" + (round + 1) +
                "; work=" + used() + "; positive_only");
        return true;
    }
    CountInterfaceSearch retained() { retaining = true; return this; }
    boolean paused() { return paused; }
    void resume(long quantum) {
        if (!paused || complete) throw new IllegalStateException("Interface search is not paused");
        allowance = CountContinuation.deadline(work, quantum, budget);
        paused = false;
    }
    BigInteger[] counts() { return counts == null ? null : counts.clone(); }
    long cacheHits() { return hits; }
    long resumptions() { return resumed; }
    long evictions() { return evicted; }
    @Override public void close() {
        complete = true;
        paused = false;
        for (var block : blocks) for (var local : block.answers.values()) if (local.search != null) local.search.close();
        solving = null;
        suspended.clear();
        blocks.clear();
        interfaceRows.clear();
        budget.release(memory);
        memory = 0;
    }
}
