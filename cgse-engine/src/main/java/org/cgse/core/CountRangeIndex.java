// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;
import java.util.function.IntFunction;

/**
 * Retained, exact box queries over an owned table. Floating correlations choose
 * extra indexing directions only; every exclusion uses exact integer bounds.
 * Queries retain the first matching source ordinal, even after spatial refinement.
 */
final class CountRangeIndex implements AutoCloseable {
    private static final int LEAF = 16, MAX_PLANES = 4, MAX_BITS = 1024;

    private static final class Node {
        final int from, to;
        final BigInteger[] min, max;
        Node left, right;
        int first = Integer.MAX_VALUE;

        Node(int from, int to, int dimensions) {
            this.from = from;
            this.to = to;
            min = new BigInteger[dimensions];
            max = new BigInteger[dimensions];
        }
    }

    private record Plane(int a, int b, int shiftA, int shiftB) {
        BigInteger value(BigInteger[] point) {
            if (point[a] == null || point[b] == null) return null;
            return point[a].shiftLeft(shiftA).add(point[b].shiftLeft(shiftB));
        }
    }

    private final PlanningBudget budget;
    private final Runnable charge;
    private final BigInteger[][] points;
    private final int[] order;
    private final int sourceDimensions, dimensions;
    private final Plane[] planes;
    private final Node root;
    private final Deque<Node> building = new ArrayDeque<>(), querying = new ArrayDeque<>();
    private final BigInteger[] low, high;
    private Node leaf;
    private int cursor, match = -1;
    private boolean queryStarted, queryDone, closed, refined;
    private long memory, queryWork;

    static CountRangeIndex create(int size, int dimensions, IntFunction<BigInteger[]> source,
                                  PlanningBudget budget, Runnable charge) {
        budget.checkpoint();
        if (size < 128 || size > 65536 || dimensions < 2 || dimensions > 64) return null;
        // Includes owned references, permutation, a full tree and sampling.
        // Reserve additional integers only for directions actually selected.
        long memoryLimit = budget.availableBytes() / 8;
        long bytes = 65536L + size * (96L + 16L * dimensions);
        if (bytes > memoryLimit || !budget.tryReserve(bytes)) return null;
        try {
            var points = new BigInteger[size][];
            for (int i = 0; i < size; i++) {
                charge.run();
                var row = source.apply(i);
                if (row == null || row.length != dimensions) return null;
                points[i] = row.clone();
                for (var value : points[i]) {
                    charge.run();
                    if (value == null || value.bitLength() > MAX_BITS) return null;
                }
            }
            var planes = planes(points, dimensions, charge);
            int count = planes.length;
            // At most 2 * MAX_BITS + 1 bits per derived integer. Dropping an
            // optional axis changes only query cost, never the covered table.
            while (count > 0 && 528L * size * count > memoryLimit - bytes) count--;
            long extra = 528L * size * count;
            if (extra != 0 && !budget.tryReserve(extra)) count = 0;
            else bytes += extra;
            var result = new CountRangeIndex(points, Arrays.copyOf(planes, count), budget, charge, bytes);
            bytes = 0;
            return result;
        } finally { budget.release(bytes); }
    }

    private CountRangeIndex(BigInteger[][] source, Plane[] planes, PlanningBudget budget, Runnable charge, long memory) {
        this.budget = budget;
        this.charge = charge;
        this.memory = memory;
        sourceDimensions = source[0].length;
        this.planes = planes;
        dimensions = sourceDimensions + planes.length;
        points = source;
        order = new int[source.length];
        for (int i = 0; i < source.length; i++) {
            charge.run();
            var original = source[i];
            points[i] = Arrays.copyOf(original, dimensions);
            order[i] = i;
            for (int p = 0; p < planes.length; p++) {
                charge.run();
                points[i][sourceDimensions + p] = planes[p].value(original);
            }
        }
        root = new Node(0, points.length, dimensions);
        building.add(root);
        low = new BigInteger[dimensions];
        high = new BigInteger[dimensions];
    }

    private static Plane[] planes(BigInteger[][] source, int d, Runnable charge) {
        int n = Math.min(32, source.length);
        var scaled = new double[n][d];
        var min = new BigInteger[d];
        var max = new BigInteger[d];
        for (int r = 0; r < d; r++) {
            min[r] = max[r] = source[0][r];
            for (int i = 1; i < n; i++) {
                charge.run();
                var value = source[i * source.length / n][r];
                min[r] = min[r].min(value);
                max[r] = max[r].max(value);
            }
        }
        var bits = new int[d];
        for (int r = 0; r < d; r++) {
            bits[r] = max[r].subtract(min[r]).bitLength();
            for (int i = 0; i < n; i++) {
                charge.run();
                scaled[i][r] = source[i * source.length / n][r].subtract(min[r])
                        .shiftRight(Math.max(0, bits[r] - 40)).doubleValue() /
                        Math.scalb(1.0, Math.min(40, bits[r]));
            }
        }
        record Pair(int a, int b, double correlation) {}
        var pairs = new ArrayList<Pair>();
        for (int a = 0; a < d; a++) for (int b = a + 1; b < d; b++) {
            double ab = 0, aa = 0, bb = 0, av = 0, bv = 0;
            for (int i = 0; i < n; i++) {
                charge.run();
                double x = scaled[i][a], y = scaled[i][b];
                ab += x * y;
                aa += x * x;
                bb += y * y;
                av += x;
                bv += y;
            }
            double variance = (aa - av * av / n) * (bb - bv * bv / n);
            double correlation = variance <= 0 ? 0 : (ab - av * bv / n) / Math.sqrt(variance);
            if (correlation < -0.5 && Math.abs(bits[a] - bits[b]) <= MAX_BITS)
                pairs.add(new Pair(a, b, correlation));
        }
        pairs.sort(Comparator.comparingDouble(Pair::correlation));
        var result = new Plane[Math.min(MAX_PLANES, pairs.size())];
        for (int i = 0; i < result.length; i++) {
            var pair = pairs.get(i);
            result[i] = new Plane(pair.a, pair.b, Math.max(0, bits[pair.b] - bits[pair.a]),
                    Math.max(0, bits[pair.a] - bits[pair.b]));
        }
        return result;
    }

    boolean buildStep() {
        checkOpen();
        if (building.isEmpty()) return true;
        Node node = building.removeLast();
        if (node.left != null) {
            node.first = Math.min(node.left.first, node.right.first);
            // Internal boxes must cover every point, including unsampled
            // outliers. Samples below select a split, never a pruning bound.
            for (int d = 0; d < dimensions; d++) {
                charge.run();
                node.min[d] = node.left.min[d].min(node.right.min[d]);
                node.max[d] = node.left.max[d].max(node.right.max[d]);
            }
            return building.isEmpty();
        }
        boolean terminal = node.to - node.from <= LEAF;
        if (terminal || refined) bounds(node, terminal ? 1 : Math.max(1, (node.to - node.from) / LEAF));
        if (terminal) {
            if (refined) {
                for (int i = node.from; i < node.to; i++) {
                    charge.run();
                    node.first = Math.min(node.first, order[i]);
                }
            } else node.first = node.from;
            return building.isEmpty();
        }
        int middle = (node.from + node.to) >>> 1;
        // The first tree uses the existing table order: linear construction,
        // no repartitioning. Only sustained query work pays for spatial splits.
        if (refined) {
            int axis = 0;
            for (int d = 1; d < dimensions; d++) {
                charge.run();
                if (node.max[d].subtract(node.min[d]).compareTo(node.max[axis].subtract(node.min[axis])) > 0) axis = d;
            }
            partition(node.from, node.to, middle, axis);
        }
        node.left = new Node(node.from, middle, dimensions);
        node.right = new Node(middle, node.to, dimensions);
        building.addLast(node);
        building.addLast(node.right);
        building.addLast(node.left);
        return false;
    }

    private void bounds(Node node, int stride) {
        for (int d = 0; d < dimensions; d++) {
            charge.run();
            node.min[d] = node.max[d] = points[order[node.from]][d];
        }
        for (int i = node.from + 1; i < node.to; i += stride) for (int d = 0; d < dimensions; d++) {
            charge.run();
            var value = points[order[i]][d];
            node.min[d] = node.min[d].min(value);
            node.max[d] = node.max[d].max(value);
        }
    }

    private void partition(int from, int to, int middle, int axis) {
        while (to - from > 1) {
            var pivot = points[order[(from + to) >>> 1]][axis];
            int less = from, at = from, greater = to;
            while (at < greater) {
                charge.run();
                int comparison = points[order[at]][axis].compareTo(pivot);
                if (comparison < 0) swap(at++, less++);
                else if (comparison > 0) swap(at, --greater);
                else at++;
            }
            if (middle < less) to = less;
            else if (middle >= greater) from = greater;
            else return;
        }
    }

    private void swap(int a, int b) { int value = order[a]; order[a] = order[b]; order[b] = value; }

    void begin(BigInteger[] lower, BigInteger[] upper) {
        checkOpen();
        if (!building.isEmpty()) throw new IllegalStateException("Index still building");
        if (lower.length != sourceDimensions || upper.length != sourceDimensions)
            throw new IllegalArgumentException("Box dimensions differ");
        querying.clear();
        leaf = null;
        match = -1;
        queryDone = false;
        queryStarted = true;
        for (int d = 0; d < sourceDimensions; d++) {
            charge.run();
            low[d] = lower[d] == null ? root.min[d] : lower[d].max(root.min[d]);
            high[d] = upper[d] == null ? root.max[d] : upper[d].min(root.max[d]);
            if (low[d].compareTo(high[d]) > 0) { queryDone = true; return; }
        }
        for (int p = 0; p < planes.length; p++) {
            charge.run();
            // Positive integral weights preserve every point in the original
            // box, including unbounded sides. No floating value enters this test.
            low[sourceDimensions + p] = planes[p].value(low);
            high[sourceDimensions + p] = planes[p].value(high);
        }
        if (!refined && queryWork >= points.length * (4L * dimensions + 16)) {
            // Reuse the owned coordinates and memory. The complete root box
            // above still bounds this query; traversal waits for all new boxes.
            refined = true;
            root.left = root.right = null;
            building.add(root);
        }
        querying.add(root);
    }

    boolean queryStep() {
        checkOpen();
        if (!queryStarted) throw new IllegalStateException("No box query");
        if (queryDone) return true;
        if (!building.isEmpty()) { buildStep(); return false; }
        if (leaf != null) {
            int id = order[cursor++];
            if (match >= 0 && id >= match) {
                queryCharge();
                if (cursor == leaf.to) leaf = null;
                return false;
            }
            boolean inside = true;
            for (int d = 0; d < sourceDimensions; d++) {
                queryCharge();
                var value = points[id][d];
                if (low[d] != null && value.compareTo(low[d]) < 0 ||
                        high[d] != null && value.compareTo(high[d]) > 0) { inside = false; break; }
            }
            if (inside) {
                match = id;
                if (!refined || id == 0) return queryDone = true;
            }
            if (cursor == leaf.to) leaf = null;
            return false;
        }
        if (querying.isEmpty()) return queryDone = true;
        Node node = querying.removeLast();
        if (match >= 0 && node.first >= match) { queryCharge(); return false; }
        for (int d = 0; d < dimensions; d++) {
            queryCharge();
            if (low[d] != null && node.max[d].compareTo(low[d]) < 0 ||
                    high[d] != null && node.min[d].compareTo(high[d]) > 0) return false;
        }
        if (node.left != null) {
            if (node.left.first < node.right.first) {
                querying.addLast(node.right);
                querying.addLast(node.left);
            } else {
                querying.addLast(node.left);
                querying.addLast(node.right);
            }
        } else { leaf = node; cursor = node.from; }
        return false;
    }

    int match() {
        checkOpen();
        if (!queryDone) throw new IllegalStateException("Unfinished query");
        return match;
    }

    private void queryCharge() {
        charge.run();
        if (!refined) queryWork++;
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Closed range index");
        budget.checkpoint();
    }

    @Override public void close() {
        budget.release(memory);
        memory = 0;
        closed = true;
        building.clear();
        querying.clear();
        leaf = null;
    }
}
