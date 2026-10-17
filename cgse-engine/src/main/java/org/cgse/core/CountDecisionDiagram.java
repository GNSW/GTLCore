// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Reduced multi-valued decision diagram with exact residual-state subsumption. */
final class CountDecisionDiagram implements CountContinuation {

    private record Node(List<BigInteger> residual, Node parent, int value) {}

    private static final class Stop extends RuntimeException {

        Stop() {
            super(null, null, false, false);
        }
    }

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper;
    private final PlanningBudget budget;
    private long allowance;
    private final long retentionLimit;
    private int[] order, widths;
    private BigInteger[][] minimum, maximum, coefficients;
    private List<Node> frontier = new ArrayList<>();
    private final Map<List<BigInteger>, Node> next = new LinkedHashMap<>();
    private final List<List<List<BigInteger>>> proofLayers = new ArrayList<>();
    private int layer, parent, value, created, merged, subsumed;
    private long memory, work;
    private BigInteger[] counts;
    private boolean complete, infeasible;

    CountDecisionDiagram(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper, PlanningBudget budget, long maximumWork) {
        this.rows = rows;
        this.lower = lower;
        this.upper = upper;
        this.budget = budget;
        retentionLimit = budget.availableBytes() / 8;
        allowance = Math.min(maximumWork, budget.remainingWork() / 16);
        int n = lower.length, m = rows.size();
        if (n > 128 || m > 512 || allowance < 2048 || (long) n * m > 32768) {
            complete = true;
            return;
        }
        widths = new int[n];
        for (int i = 0; i < n; i++) {
            if (upper[i] == null) {
                complete = true;
                return;
            }
            BigInteger width = upper[i].subtract(lower[i]);
            if (width.signum() < 0 || width.compareTo(BigInteger.valueOf(64)) > 0) {
                complete = true;
                return;
            }
            widths[i] = width.intValueExact();
        }
        // Wide domains on many coordinates tend to have distinct residuals:
        // leave the compact arithmetic representation its search budget.
        if (n > 12 && Arrays.stream(widths).anyMatch(w -> w > 8)) {
            complete = true;
            return;
        }
        long bytes = 2048L + 256L * (n + 1) * m;
        if (!budget.tryReserve(bytes)) {
            complete = true;
            return;
        }
        memory = bytes;
    }

    private boolean prepared;

    private void prepare() {
        int n = lower.length, m = rows.size();
        try {
            var ids = new ArrayList<Integer>();
            int[] degree = new int[n];
            for (var row : rows) for (int id : row.terms().keySet()) degree[id]++;
            for (int i = 0; i < n; i++) ids.add(i);
            ids.sort(Comparator.<Integer>comparingInt(i -> -degree[i]).thenComparingInt(i -> i));
            order = ids.stream().mapToInt(Integer::intValue).toArray();
            minimum = new BigInteger[n + 1][m];
            maximum = new BigInteger[n + 1][m];
            coefficients = new BigInteger[n][m];
            Arrays.fill(minimum[n], BigInteger.ZERO);
            Arrays.fill(maximum[n], BigInteger.ZERO);
            for (int at = n - 1; at >= 0; at--) for (int r = 0; r < m; r++) {
                charge();
                BigInteger a = rows.get(r).terms().getOrDefault(order[at], BigInteger.ZERO);
                coefficients[at][r] = a;
                BigInteger extent = a.multiply(BigInteger.valueOf(widths[order[at]]));
                minimum[at][r] = minimum[at + 1][r].add(extent.min(BigInteger.ZERO));
                maximum[at][r] = maximum[at + 1][r].add(extent.max(BigInteger.ZERO));
            }
            List<BigInteger> root = new ArrayList<>();
            for (var row : rows) {
                BigInteger residual = row.upper();
                for (var term : row.terms().entrySet()) {
                    charge();
                    residual = residual.subtract(term.getValue().multiply(lower[term.getKey()]));
                }
                root.add(residual);
            }
            frontier.add(new Node(List.copyOf(root), null, 0));
            if (budget.proofJournal() != null) proofLayers.add(List.of(List.copyOf(root)));
        } catch (Stop stopped) {
            complete = true;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    public boolean step() {
        if (complete || paused) return true;
        budget.checkpoint();
        if (retaining && work >= allowance) return paused = true;
        try {
            charge();
            if (!prepared) {
                prepared = true;
                prepare();
                return complete;
            }
            if (frontier.isEmpty()) {
                infeasible = true;
                return finish("proven_infeasible");
            }
            if (layer == order.length) {
                for (Node node : frontier) {
                    if (node.residual.stream().anyMatch(v -> v.signum() < 0)) continue;
                    counts = lower.clone();
                    for (int at = layer - 1; at >= 0; at--) {
                        counts[order[at]] = counts[order[at]].add(BigInteger.valueOf(node.value));
                        node = node.parent;
                    }
                    verify();
                    return finish("verified_witness");
                }
                infeasible = true;
                return finish("proven_infeasible");
            }
            for (int sliced = 0; sliced < 32 && parent < frontier.size() && (!retaining || work < allowance); sliced++) {
                Node previous = frontier.get(parent);
                List<BigInteger> residual = new ArrayList<>();
                boolean blocked = false;
                for (int r = 0; r < rows.size(); r++) {
                    charge();
                    BigInteger b = previous.residual.get(r).subtract(coefficients[layer][r].multiply(BigInteger.valueOf(value)));
                    if (b.compareTo(minimum[layer + 1][r]) < 0) {
                        blocked = true;
                        break;
                    }
                    residual.add(b.min(maximum[layer + 1][r]));
                }
                if (!blocked) {
                    List<BigInteger> key = List.copyOf(residual);
                    if (next.containsKey(key)) merged++;
                    else {
                        // With the same suffix and domains, greater remaining
                        // capacity in EVERY original row covers the other state.
                        // No item/fluid costs or unrelated resources are added.
                        boolean dominated = false;
                        if (next.size() <= 32) for (Node other : next.values()) {
                            if (dominates(other.residual, key)) {
                                dominated = true;
                                break;
                            }
                        }
                        if (dominated) subsumed++;
                        else {
                            if (created >= 16384) throw new Stop();
                            reserve(160L + 96L * rows.size());
                            created++;
                            next.put(key, new Node(key, previous, value));
                        }
                    }
                }
                if (++value > widths[order[layer]]) {
                    parent++;
                    value = 0;
                }
            }
            if (parent == frontier.size()) {
                frontier = new ArrayList<>(next.values());
                next.clear();
                parent = value = 0;
                layer++;
                if (budget.proofJournal() != null) proofLayers.add(frontier.stream().map(Node::residual).toList());
            }
            return false;
        } catch (Stop stopped) {
            counts = null;
            return finish("local_limit");
        }
    }

    private boolean dominates(List<BigInteger> a, List<BigInteger> b) {
        for (int r = 0; r < a.size(); r++) {
            charge();
            if (a.get(r).compareTo(b.get(r)) < 0) return false;
        }
        return true;
    }

    private void verify() {
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                charge();
                sum = sum.add(term.getValue().multiply(counts[term.getKey()]));
            }
            if (sum.compareTo(row.upper()) > 0) throw new IllegalStateException("Decision diagram witness violates original constraint");
        }
    }

    private boolean finish(String detail) {
        if (infeasible && budget.proofJournal() != null) budget.proofJournal().add(new CountProof.Diagram("integer_decision_diagram", lower.length,
                rows.stream().map(CountProof::row).toList(), Arrays.asList(lower), Arrays.asList(upper), Arrays.stream(order).boxed().toList(), proofLayers));
        complete = true;
        budget.note("count_mdd", detail + "; layer=" + layer + "; nodes=" + created + "; merged=" + merged + "; dominated=" + subsumed + "; work=" + work);
        return true;
    }

    private void charge() {
        budget.check();
        if (++work > allowance && !retaining) throw new Stop();
    }

    private void reserve(long bytes) {
        if (retaining && bytes > retentionLimit - memory || !budget.tryReserve(bytes)) throw new Stop();
        memory += bytes;
    }

    public BigInteger[] counts() {
        return counts == null ? null : counts.clone();
    }

    boolean infeasible() {
        return infeasible;
    }

    private boolean retaining, paused;

    CountDecisionDiagram retained() { retaining = true; return this; }
    public boolean paused() { return paused; }
    public long work() { return work; }
    // Growing a frontier is paid work, not evidence that a witness is nearer.
    // Reward completed layers rather than exponential state accumulation.
    public long progress() { return layer; }
    public boolean matches(List<ExactLinearProgram.Constraint> rows, BigInteger[] low, BigInteger[] high) {
        return this.rows.equals(rows) && Arrays.equals(lower, low) && Arrays.equals(upper, high);
    }
    public void resume(long quantum) {
        if (!paused || complete) throw new IllegalStateException("CountDecisionDiagram is not paused");
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
