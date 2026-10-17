// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/**
 * Candidate-only Boolean proximity search, inspired by local branching in SCIP.
 * Keep every fractional choice and permit up to three changes to rounded integral
 * coordinates. A dominance tree joins one change with a pair, checking every row.
 * A subsequent priced neighborhood reuses the same join machinery for up to five
 * changes, guided by the LP aggregate rather than enumerating a larger radius.
 * Exhausting this neighborhood never proves the original problem infeasible.
 */
final class CountLpProximity implements AutoCloseable {

    private static final int MAX_STATES = 32768, LEAF_SIZE = 8;

    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }

    private static final class Node {
        final int from, to;
        final long[] minimum;
        Node left, right;

        Node(int from, int to, int dimensions) {
            this.from = from;
            this.to = to;
            minimum = new long[dimensions];
        }
    }

    private final List<ExactLinearProgram.Constraint> rows;
    private final BigInteger[] lower, upper, seed;
    private final PlanningBudget budget;
    private final long allowance;
    private final int[] fractional, integral;
    private final int dimensions, states, stride;
    private final long[][] coefficients;
    private final long[] capacity, goal;
    private long[] points;
    private final int[] order;
    private int[] equalityRows, buckets;
    private CountLpPriceQueue priced;
    private final Deque<Node> building = new ArrayDeque<>();
    private Node root;
    private BigInteger[] counts;
    private long memory, queries, work, visitStarted;
    private int phase, rowCursor, entryCursor, first = -1, second = -1;
    private boolean complete;

    /** Floating values choose a neighborhood only. All accepted values are exact. */
    static CountLpProximity create(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                                   BigInteger[] upper, double[] point, PlanningBudget budget) {
        return create(rows, lower, upper, point, null, budget);
    }

    static CountLpProximity create(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                                   BigInteger[] upper, double[] point, ExactLinearProgram.Constraint preference,
                                   PlanningBudget budget) {
        return create(rows, lower, upper, point, preference, budget, false);
    }

    static CountLpProximity createPriced(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                                         BigInteger[] upper, double[] point, ExactLinearProgram.Constraint preference,
                                         PlanningBudget budget) {
        return create(rows, lower, upper, point, preference, budget, true);
    }

    private static CountLpProximity create(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower,
                                           BigInteger[] upper, double[] point, ExactLinearProgram.Constraint preference,
                                           PlanningBudget budget, boolean priceOrdered) {
        budget.checkpoint();
        int n = lower.length;
        if (point == null || n < 2 || n > 128 || upper.length != n || point.length != n ||
                rows.isEmpty() || rows.size() > 16 || priceOrdered && preference == null) return null;
        long started = budget.threadWork();
        long allowance = Math.min(priceOrdered ? 524288 : 131072, budget.remainingWork() / (priceOrdered ? 32 : 16));
        if (allowance < 4096) return null;
        long bytes = 2048L + 192L * n + 128L * rows.size();
        if (!budget.tryReserve(bytes)) return null;
        try {
            if (priceOrdered) for (int i = 0; i < n; i++) {
                budget.operation(PlanningBudget.Operation.SCAN, 0);
                if (lower[i] == null || upper[i] == null || lower[i].signum() < 0 ||
                        lower[i].compareTo(upper[i]) > 0 || upper[i].compareTo(BigInteger.ONE) > 0 ||
                        !Double.isFinite(point[i])) return null;
            }
            BigInteger slack = null;
            if (priceOrdered) {
                // These restrictions belong only to this optional candidate
                // neighborhood. They are never published as global bounds or
                // proofs, even if a caller supplies a merely heuristic row.
                lower = lower.clone(); upper = upper.clone();
                BigInteger minimum = BigInteger.ZERO;
                if (preference.upper().bitLength() > 1024) return null;
                for (var term : preference.terms().entrySet()) {
                    int id = term.getKey(); var a = term.getValue();
                    if (id < 0 || id >= n || a.bitLength() > 1024) return null;
                    budget.operation(PlanningBudget.Operation.INTEGER, Math.max(a.bitLength(), minimum.bitLength()));
                    if ((a.signum() > 0 ? lower[id] : upper[id]).signum() != 0) minimum = minimum.add(a);
                }
                budget.operation(PlanningBudget.Operation.INTEGER, Math.max(minimum.bitLength(), preference.upper().bitLength()));
                slack = preference.upper().subtract(minimum);
                if (slack.signum() < 0) return null;
                for (var term : preference.terms().entrySet()) {
                    budget.operation(PlanningBudget.Operation.INTEGER, Math.max(slack.bitLength(), term.getValue().bitLength()));
                    if (term.getValue().abs().compareTo(slack) > 0) {
                        int id = term.getKey();
                        if (term.getValue().signum() > 0) upper[id] = lower[id]; else lower[id] = upper[id];
                    }
                }
            }
            var fractional = new ArrayList<Integer>();
            var integral = new ArrayList<Integer>();
            var seed = new BigInteger[n];
            for (int i = 0; i < n; i++) {
                budget.operation(PlanningBudget.Operation.SCAN, 0);
                if (!priceOrdered && (lower[i] == null || upper[i] == null || lower[i].signum() < 0 ||
                        lower[i].compareTo(upper[i]) > 0 || upper[i].compareTo(BigInteger.ONE) > 0 ||
                        !Double.isFinite(point[i]))) return null;
                seed[i] = (point[i] >= 0.5 ? BigInteger.ONE : BigInteger.ZERO).max(lower[i]).min(upper[i]);
                if (lower[i].equals(upper[i])) continue;
                if (point[i] > 1e-6 && point[i] < 1 - 1e-6) {
                    fractional.add(i);
                    seed[i] = BigInteger.ZERO;
                } else integral.add(i);
            }
            if (fractional.size() > 8) return null;
            if (preference != null) {
                // The existing LP aggregate approximates reduced costs. Cheap
                // changes are queried first, but no coordinate is excluded and
                // these weights are not premises of any feasibility conclusion.
                integral.sort((a, b) -> {
                    var x = preference.terms().getOrDefault(a, BigInteger.ZERO);
                    var y = preference.terms().getOrDefault(b, BigInteger.ZERO);
                    budget.operation(PlanningBudget.Operation.INTEGER, Math.max(x.bitLength(), y.bitLength()));
                    return x.abs().compareTo(y.abs());
                });
            }
            long[] prices = null;
            long priceCapacity = 0;
            if (priceOrdered) {
                int shift = Math.max(0, slack.bitLength() - 48);
                priceCapacity = slack.shiftRight(shift).longValueExact();
                prices = new long[integral.size()];
                for (int i = 0; i < integral.size(); i++) {
                    int id = integral.get(i);
                    var a = preference.terms().getOrDefault(id, BigInteger.ZERO);
                    budget.operation(PlanningBudget.Operation.INTEGER, Math.max(slack.bitLength(), a.bitLength()));
                    if (a.signum() != 0 && (seed[id].signum() == 0) != (a.signum() > 0)) return null;
                    // Flooring nonnegative prices and ignoring fractional-choice
                    // costs only relaxes the necessary price bound. At most five
                    // 48-bit prices are added, so queue arithmetic cannot overflow.
                    prices[i] = a.abs().shiftRight(shift).longValueExact();
                }
            }
            if (budget.threadWork() - started >= allowance) return null;
            int states = (1 << fractional.size()) * (integral.size() + 1);
            if (states > MAX_STATES) return null;
            int leaves = 1;
            while (leaves * LEAF_SIZE < states) leaves *= 2;
            // Flat point table and primitive indices, optional equality buckets,
            // bounded tree nodes/minima, stack and exact row conversion scratch.
            long workspace = 4096L + 8L * n * rows.size() + 32L * rows.size() +
                    states * (24L + 8L * rows.size()) + (2L * leaves - 1) * (160L + 8L * rows.size());
            if (workspace > budget.availableBytes() / 4 || !budget.tryReserve(workspace)) return null;
            bytes += workspace;
            var result = new CountLpProximity(rows, lower, upper, seed, fractional, integral,
                    states, budget, started, allowance, bytes);
            if (priceOrdered) result.priced = new CountLpPriceQueue(prices, priceCapacity, budget, result::check);
            bytes = 0;
            return result;
        } finally {
            budget.release(bytes);
        }
    }

    private CountLpProximity(List<ExactLinearProgram.Constraint> rows, BigInteger[] lower, BigInteger[] upper,
                             BigInteger[] seed, List<Integer> fractional, List<Integer> integral, int states,
                             PlanningBudget budget, long started, long allowance, long bytes) {
        this.rows = List.copyOf(rows);
        this.lower = lower.clone();
        this.upper = upper.clone();
        this.seed = seed;
        this.fractional = fractional.stream().mapToInt(Integer::intValue).toArray();
        this.integral = integral.stream().mapToInt(Integer::intValue).toArray();
        this.states = states;
        stride = integral.size() + 1;
        this.budget = budget;
        work = budget.threadWork() - started;
        this.allowance = allowance;
        memory = bytes;
        dimensions = rows.size();
        coefficients = new long[lower.length][dimensions];
        capacity = new long[dimensions];
        goal = new long[dimensions];
        order = new int[states];
    }

    /** Completed steps retain conversion, enumeration, tree and query cursors. */
    boolean step() {
        if (complete) return true;
        budget.checkpoint();
        visitStarted = budget.threadWork();
        try {
            scan();
            if (phase == 0) {
                if (rowCursor < rows.size()) {
                    if (!convert(rowCursor++)) return finish("magnitude_declined");
                    return false;
                }
                prepareEqualities();
                // Equality joins retain each fractional sum once. Integral
                // changes are added on lookup, without copying a Cartesian table.
                points = new long[(buckets == null ? states : states / stride) * dimensions];
                phase++;
            }
            if (phase == 1) {
                // Bound a cooperative visit even when the point table is large.
                int until = Math.min(states, entryCursor + 128);
                while (entryCursor < until) enumerate(entryCursor++);
                if (entryCursor < states) return false;
                if (buckets != null) {
                    phase = 3;
                    return false;
                }
                root = new Node(0, states, dimensions);
                building.addLast(root);
                phase++;
                return false;
            }
            if (phase == 2) {
                build(building.removeLast());
                if (building.isEmpty()) phase++;
                return false;
            }
            if (priced == null ? first >= integral.length : priced.complete())
                return finish(priced != null && priced.limited() ? "queue_limit" : "neighborhood_exhausted");
            int a = first < 0 ? -1 : integral[first], b = second < 0 ? -1 : integral[second];
            for (int r = 0; r < dimensions; r++) {
                scalar();
                goal[r] = capacity[r] - delta(a, r) - delta(b, r);
                if (priced != null) for (int k = 0; k < priced.length(); k++) {
                    scalar(); goal[r] -= delta(integral[priced.at(k)], r);
                }
            }
            queries++;
            int hit = buckets == null ? find(root, a, b) : findEquality(a, b);
            if (hit >= 0) {
                var value = seed.clone();
                int mask = hit / stride, extra = hit % stride - 1;
                for (int i = 0; i < fractional.length; i++) {
                    scan();
                    value[fractional[i]] = (mask & (1 << i)) == 0 ? BigInteger.ZERO : BigInteger.ONE;
                }
                if (a >= 0) value[a] = BigInteger.ONE.subtract(value[a]);
                if (b >= 0) value[b] = BigInteger.ONE.subtract(value[b]);
                if (priced != null) for (int k = 0; k < priced.length(); k++) {
                    scan(); int id = integral[priced.at(k)]; value[id] = BigInteger.ONE.subtract(value[id]);
                }
                if (extra >= 0) {
                    int id = integral[extra];
                    value[id] = BigInteger.ONE.subtract(value[id]);
                }
                if (!valid(value)) return finish("candidate_rejected");
                counts = value;
                return finish("verified_witness");
            }
            if (priced != null) { priced.advance(); return false; }
            // Empty right side, each singleton, then each distinct unordered pair.
            second++;
            if (second >= integral.length) {
                first++;
                second = first + 1;
                if (second >= integral.length) first = integral.length;
            }
            return false;
        } catch (Stop stopped) {
            return finish("local_work_limit");
        } finally {
            work += budget.threadWork() - visitStarted;
        }
    }

    private boolean convert(int index) {
        var row = rows.get(index);
        if (row.upper().bitLength() > 1024) return false;
        BigInteger gcd = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) {
            scan();
            if (term.getKey() < 0 || term.getKey() >= seed.length || term.getValue().bitLength() > 1024) return false;
            integer(gcd, term.getValue());
            gcd = gcd.gcd(term.getValue());
        }
        if (gcd.signum() == 0) gcd = BigInteger.ONE;
        integer(row.upper(), gcd);
        BigInteger[] qr = row.upper().divideAndRemainder(gcd);
        BigInteger bound = qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
        BigInteger magnitude = BigInteger.ZERO;
        for (var term : row.terms().entrySet()) {
            scan();
            integer(term.getValue(), gcd);
            var coefficient = term.getValue().divide(gcd);
            integer(magnitude, coefficient);
            magnitude = magnitude.add(coefficient.abs());
            // This bounds every subset sum, spread and up-to-five-flip query. It
            // also excludes Long.MIN_VALUE before negation or subtraction.
            if (magnitude.bitLength() > 60) return false;
            int id = term.getKey();
            coefficients[id][index] = coefficient.longValueExact();
            if (seed[id].signum() != 0) {
                integer(bound, coefficient);
                bound = bound.subtract(coefficient);
            }
        }
        if (bound.bitLength() > 60) return false;
        capacity[index] = bound.longValueExact();
        return true;
    }

    private long delta(int id, int row) {
        return id < 0 ? 0 : seed[id].signum() == 0 ? coefficients[id][row] : -coefficients[id][row];
    }

    /** Opposite exact rows fix a residual, so a hash join can replace the tree. */
    private void prepareEqualities() {
        var selected = new int[dimensions];
        var paired = new boolean[dimensions];
        int size = 0;
        for (int a = 0; a < dimensions; a++) {
            if (paired[a]) continue;
            boolean active = false;
            for (int i = 0; i < seed.length; i++) {
                scan();
                if (coefficients[i][a] != 0 && !lower[i].equals(upper[i])) active = true;
            }
            if (!active) continue;
            pairs: for (int b = a + 1; b < dimensions; b++) {
                scan();
                if (paired[b] || capacity[a] != -capacity[b]) continue;
                for (int i = 0; i < seed.length; i++) {
                    scalar();
                    if (coefficients[i][a] != -coefficients[i][b]) continue pairs;
                }
                selected[size++] = a;
                paired[a] = paired[b] = true;
                break;
            }
        }
        if (size == 0) return;
        equalityRows = Arrays.copyOf(selected, size);
        int slots = 1;
        while (slots < states * 2) slots <<= 1;
        buckets = new int[slots];
        int leaves = 1;
        while (leaves * LEAF_SIZE < states) leaves *= 2;
        long unused = 8L * (states - states / stride) * dimensions +
                (2L * leaves - 1) * (160L + 8L * dimensions);
        budget.release(unused);
        memory -= unused;
    }

    private void enumerate(int entry) {
        int mask = entry / stride, extra = entry % stride - 1, base = entry * dimensions;
        if (buckets != null) {
            if (extra < 0) {
                int previous = (mask & (mask - 1)) * dimensions;
                int added = mask == 0 ? -1 : fractional[Integer.numberOfTrailingZeros(mask)];
                for (int r = 0; r < dimensions; r++) {
                    scalar();
                    points[mask * dimensions + r] = added < 0 ? 0 : points[previous + r] + coefficients[added][r];
                }
            }
            int bucket = signature(entry);
            order[entry] = buckets[bucket] - 1;
            buckets[bucket] = entry + 1;
            return;
        }
        // Each mask's fractional sum is compiled once. Every integral option
        // reuses it, instead of resumming all fractional coordinates per point.
        int previous = extra >= 0 ? (entry - extra - 1) * dimensions :
                (mask & (mask - 1)) * stride * dimensions;
        int added = mask == 0 ? -1 : fractional[Integer.numberOfTrailingZeros(mask)];
        for (int r = 0; r < dimensions; r++) {
            scalar();
            points[base + r] = extra >= 0 ? points[previous + r] + delta(integral[extra], r) :
                    added < 0 ? 0 : points[previous + r] + coefficients[added][r];
        }
        order[entry] = entry;
    }

    /** Hash overflow/collisions affect indexing only; every hit checks all rows. */
    private int signature(int entry) {
        int hash = 1;
        for (int row : equalityRows) {
            scalar();
            hash = 31 * hash + Long.hashCode(entry < 0 ? goal[row] : point(entry, row));
        }
        return hash & (buckets.length - 1);
    }

    private long point(int entry, int row) {
        if (buckets == null) return points[entry * dimensions + row];
        int extra = entry % stride - 1;
        return points[(entry / stride) * dimensions + row] + delta(extra < 0 ? -1 : integral[extra], row);
    }

    private int findEquality(int first, int second) {
        for (int entry = buckets[signature(-1)] - 1; entry >= 0; entry = order[entry]) {
            if (matches(entry, first, second)) return entry;
        }
        return -1;
    }

    private void build(Node node) {
        if (node.left != null) {
            // Children supply exact bounding boxes. Do not rescan every point
            // at every ancestor merely to reconstruct the same minima.
            for (int r = 0; r < dimensions; r++) {
                scan();
                node.minimum[r] = Math.min(node.left.minimum[r], node.right.minimum[r]);
            }
            return;
        }
        long[] maximum = new long[dimensions];
        int base = order[node.from] * dimensions;
        for (int r = 0; r < dimensions; r++) {
            scan();
            maximum[r] = node.minimum[r] = points[base + r];
        }
        boolean leaf = node.to - node.from <= LEAF_SIZE;
        // A sampled spread chooses the partition axis only. Leaf boxes inspect
        // every point, and internal boxes merge both children before any query.
        int stride = leaf ? 1 : Math.max(1, (node.to - node.from) / LEAF_SIZE);
        for (int i = node.from + 1; i < node.to; i += stride) for (int r = 0; r < dimensions; r++) {
            scan();
            long value = points[order[i] * dimensions + r];
            node.minimum[r] = Math.min(node.minimum[r], value);
            maximum[r] = Math.max(maximum[r], value);
        }
        if (leaf) return;
        int axis = 0;
        for (int r = 1; r < dimensions; r++) {
            scalar();
            if (maximum[r] - node.minimum[r] > maximum[axis] - node.minimum[axis]) axis = r;
        }
        int middle = (node.from + node.to) >>> 1;
        partition(node.from, node.to, middle, axis);
        node.left = new Node(node.from, middle, dimensions);
        node.right = new Node(middle, node.to, dimensions);
        building.addLast(node);
        building.addLast(node.right);
        building.addLast(node.left);
    }

    /** Three-way median selection also terminates cheaply when many signatures tie. */
    private void partition(int from, int to, int middle, int dimension) {
        while (to - from > 1) {
            long pivot = points[order[(from + to) >>> 1] * dimensions + dimension];
            int less = from, cursor = from, greater = to;
            while (cursor < greater) {
                scan();
                long value = points[order[cursor] * dimensions + dimension];
                if (value < pivot) swap(cursor++, less++);
                else if (value > pivot) swap(cursor, --greater);
                else cursor++;
            }
            if (middle < less) to = less;
            else if (middle >= greater) from = greater;
            else return;
        }
    }

    private void swap(int a, int b) {
        int value = order[a]; order[a] = order[b]; order[b] = value;
    }

    private int find(Node node, int first, int second) {
        for (int r = 0; r < dimensions; r++) {
            scan();
            if (node.minimum[r] > goal[r]) return -1;
        }
        if (node.left != null) {
            int hit = find(node.left, first, second);
            return hit >= 0 ? hit : find(node.right, first, second);
        }
        for (int i = node.from; i < node.to; i++)
            if (matches(order[i], first, second)) return order[i];
        return -1;
    }

    private boolean matches(int entry, int first, int second) {
        scan();
        int extra = entry % stride - 1;
        // Two occurrences of one flip would be an invalid additive join.
        if (extra >= 0 && (integral[extra] == first || integral[extra] == second)) return false;
        if (extra >= 0 && priced != null && !priced.permits(extra)) return false;
        for (int r = 0; r < dimensions; r++) {
            scan();
            if (buckets != null) scalar();
            if (point(entry, r) > goal[r]) return false;
        }
        return true;
    }

    private boolean valid(BigInteger[] value) {
        for (int i = 0; i < value.length; i++) {
            scan();
            if (value[i].compareTo(lower[i]) < 0 || value[i].compareTo(upper[i]) > 0) return false;
        }
        for (var row : rows) {
            BigInteger sum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                scan();
                integer(sum, term.getValue());
                if (value[term.getKey()].signum() != 0) sum = sum.add(term.getValue());
            }
            if (sum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    private void scan() {
        check();
        budget.operation(PlanningBudget.Operation.SCAN, 0);
    }

    private void scalar() {
        check();
        budget.operation(PlanningBudget.Operation.INTEGER, 63);
    }

    private void integer(BigInteger a, BigInteger b) {
        check();
        budget.operation(PlanningBudget.Operation.INTEGER, Math.max(a.bitLength(), b.bitLength()));
    }

    private void check() {
        if (work + budget.threadWork() - visitStarted >= allowance) throw new Stop();
    }

    private boolean finish(String detail) {
        complete = true;
        budget.note("count_lp_proximity", detail + "; states=" + states + "; fractional=" + fractional.length +
                "; neighborhood=" + (priced == null ? "three_changes" : "priced_five_changes") +
                "; pending_peak=" + (priced == null ? 0 : priced.peak()) +
                "; index=" + (buckets == null ? "dominance" : "equality") +
                "; phase=" + phase + "; queries=" + queries + "; work=" + (work + budget.threadWork() - visitStarted));
        return true;
    }

    boolean complete() { return complete; }
    BigInteger[] counts() { return counts == null ? null : counts.clone(); }

    @Override
    public void close() {
        complete = true;
        if (priced != null) priced.close();
        building.clear();
        root = null;
        budget.release(memory);
        memory = 0;
    }
}
