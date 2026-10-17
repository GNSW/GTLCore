// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

/** Retained sparse-exchange matching inside a proposed Boolean face.
 * Each half may change a bounded number of seed coordinates (three by default). Exhausting that
 * restricted neighborhood is never a proof about the original count model.
 * A retained left table is queried by exact integer boxes, without allocating
 * a vector or traversing every left entry for each right-hand combination. */
final class CountPackingExchange implements AutoCloseable {
    private final PlanningBudget budget;
    private final int[] rejected, rejectedPoint;
    private final long[] slack, seedChange, query, prefix;
    private final long seed;
    private final int size, dimensions, split, points;
    private final CountPackingValues vectors;
    private final long[] masks, minimum, maximum, spread;
    private final int[] order, from, to, left, right, building, querying;
    private final Subsets subsets;
    private int cursor, phase, nodes = 1, builds, queries;
    private long memory, assignment, rightMask;
    private boolean complete, feasible;
    CountPackingExchange(long[][] a, long[] b, long seed, PlanningBudget budget) {
        this(a, b, seed, budget, 3);
    }
    CountPackingExchange(long[][] a, long[] b, long seed, PlanningBudget budget, int radius) {
        if (radius < 1 || radius > 4) throw new IllegalArgumentException("Exchange radius");
        if (a.length == 0) throw new IllegalArgumentException("Empty exchange face");
        size = a[0].length;
        dimensions = b.length;
        split = size / 2;
        if (size < 2 || size > 64 || dimensions < 1 || dimensions > 35 || a.length != dimensions)             throw new IllegalArgumentException("Exchange shape");
        this.budget = budget;
        this.seed = seed;
        int combinations = 1, chosen = 1;
        for (int k = 1; k <= Math.min(radius, split); k++) { chosen = chosen * (split - k + 1) / k; combinations += chosen; }
        points = combinations;
        // Retain only lower node bounds for one-sided queries. Upper activities
        // are build scratch; root spreads guide partitioning without tree storage.
        // Median splits need a power-of-two leaf bound, including partial leaves.
        int leaves = 1;
        while (leaves * 8 < points) leaves <<= 1;
        int capacity = 2 * leaves;
        boolean compact = CountPackingValues.canPack(a, 0, split, budget);
        long bytes = 8192L + points * (16L + (compact ? 4L : 8L) * dimensions) + capacity * (20L + 8L * dimensions) + 32L * size * dimensions;
        if (!budget.tryReserve(bytes)) {
            complete = true;
            vectors = null;
            slack = seedChange = query = prefix = masks = minimum = maximum = spread = null;
            order = from = to = left = right = building = querying = rejected = rejectedPoint = null;
            subsets = null;
            return;
        }

        memory = bytes;
        try {
            slack = b.clone();
            query = new long[dimensions];
            prefix = new long[(radius + 1) * dimensions];
            seedChange = new long[dimensions * size];
            for (int r = 0; r < dimensions; r++) {
                long magnitude = Math.abs(b[r]);
                if (a[r].length != size || b[r] <=  - (1L << 60) || b[r] >= 1L << 60) throw new IllegalArgumentException("Exchange row range");
                for (int i = 0; i < size; i++) {
                    budget.check();
                    long v = a[r][i];
                    if (v <=  - (1L << 60) || v >= 1L << 60 || Math.abs(v) >= (1L << 60) - magnitude) throw new IllegalArgumentException("Exchange activity range");
                    magnitude += Math.abs(v);
                    boolean selected = (seed & (1L << i)) != 0;
                    if (selected)slack[r] -= v;
                    seedChange[r * size + i] = selected ?  - v : v;
                }

            }

            vectors = new CountPackingValues(points * dimensions, compact);
            masks = new long[points];
            order = new int[points];
            minimum = new long[capacity * dimensions];
            maximum = new long[dimensions];
            spread = new long[dimensions];
            from = new int[capacity];
            to = new int[capacity];
            left = new int[capacity];
            right = new int[capacity];
            rejectedPoint = new int[points];
            rejected = new int[capacity];
            building = new int[64];
            querying = new int[64];
            subsets = new Subsets(0, split, radius);
        }
 catch (RuntimeException failure) {
            close();
            throw failure;
        }

    }
    boolean step() {
        if (complete) return true;
        budget.checkpoint();
        if (phase == 0) {
            long mask = subsets.next();
            if (mask == -1L) {
                phase = 1;
                from[0] = 0;
                to[0] = cursor;
                building[builds++] = 0;
                return false;
            }

            masks[cursor] = mask;
            order[cursor] = cursor;
            updatePrefix();
            budget.operations(PlanningBudget.Operation.SCAN, 0, dimensions);
            vectors.put(prefix, subsets.count * dimensions, cursor * dimensions, dimensions);

            cursor++;
            return false;
        }

        if (phase == 1) {
            if (builds == 0) {
                phase = 2;
                subsets.reset(split, size);
                return false;
            }

            int node = building[--builds];
            // Queries start only after every exact child bound has been joined.
            if (node >= 0) build(node);
            else join(~node);
            return false;
        }

        if (queries == 0) {
            rightMask = subsets.next();
            if (rightMask == -1L) {
                complete = true;
                return true;
            }

            updatePrefix();
            budget.operations(PlanningBudget.Operation.INTEGER, 64, dimensions);
            for (int r = 0; r < dimensions; r++) query[r] = slack[r] - prefix[subsets.count * dimensions + r];

            querying[queries++] = 0;
        }

        int node = querying[--queries];
        // Recheck the last separating row against this query. The remembered
        // coordinate is an ordering hint, never a cached failure or bound.
        int first = rejected[node] == 0 ? dimensions - 1 : rejected[node] - 1;
        budget.operation(PlanningBudget.Operation.SCAN, 0);
        if (minimum[node * dimensions + first] > query[first]) return false;
        for (int r = dimensions - 1; r >= 0; r--) {
            if (r == first) continue;
            budget.operation(PlanningBudget.Operation.SCAN, 0);
            if (minimum[node * dimensions + r] > query[r]) { rejected[node] = r + 1; return false; }
        }

        if (left[node] != 0) {
            querying[queries++] = right[node];
            querying[queries++] = left[node];
            return false;
        }

        for (int p = from[node]; p < to[node]; p++) {
            int id = order[p];
            int pointFirst = rejectedPoint[id] == 0 ? dimensions - 1 : rejectedPoint[id] - 1;
            budget.operation(PlanningBudget.Operation.SCAN, 0);
            boolean valid = vectors.get(id * dimensions + pointFirst) <= query[pointFirst];
            if (valid) for (int r = dimensions - 1; r >= 0; r--) {
                if (r == pointFirst) continue;
                budget.operation(PlanningBudget.Operation.SCAN, 0);
                if (vectors.get(id * dimensions + r) > query[r]) { rejectedPoint[id] = r + 1; valid = false; break; }
            }
            if (valid) {
                assignment = seed ^ masks[id] ^ rightMask;
                feasible = complete = true;
                return true;
            }
        }

        return false;
    }

    /** Adjacent lexicographic combinations retain every unchanged prefix sum. */
    private void updatePrefix() {
        for (int d = subsets.changed; d < subsets.count; d++) {
            budget.operations(PlanningBudget.Operation.INTEGER, 64, dimensions);
            int id = subsets.chosen[d];
            for (int r = 0; r < dimensions; r++)
                prefix[(d + 1) * dimensions + r] = prefix[d * dimensions + r] + seedChange[r * size + id];
        }
    }

    private void build(int node) {
        int begin = from[node], end = to[node], first = order[begin] * dimensions;
        if (node == 0) {
            budget.operations(PlanningBudget.Operation.SCAN, 0, 2 * dimensions);
            vectors.copy(first, query, 0, dimensions);
            vectors.copy(first, maximum, 0, dimensions);
            for (int p = begin + 1; p < end; p++) {
                budget.operations(PlanningBudget.Operation.INTEGER, 64, 2 * dimensions);
                int id = order[p] * dimensions;
                for (int r = 0; r < dimensions; r++) {
                    query[r] = Math.min(query[r], vectors.get(id + r));
                    maximum[r] = Math.max(maximum[r], vectors.get(id + r));
                }
            }
            budget.operations(PlanningBudget.Operation.INTEGER, 64, dimensions);
            for (int r = 0; r < dimensions; r++) spread[r] = maximum[r] - query[r];
        }
        if (end - begin > 8) {
            // Samples choose a partition axis only; they never become query bounds.
            if (node != 0) {
                budget.operations(PlanningBudget.Operation.SCAN, 0, 2 * dimensions);
                vectors.copy(first, query, 0, dimensions);
                vectors.copy(first, maximum, 0, dimensions);
                for (int sample = 1; sample < 8; sample++) {
                    int id = order[begin + sample * (end - begin - 1) / 7] * dimensions;
                    budget.operations(PlanningBudget.Operation.INTEGER, 64, 2 * dimensions);
                    for (int r = 0; r < dimensions; r++) {
                        query[r] = Math.min(query[r], vectors.get(id + r));
                        maximum[r] = Math.max(maximum[r], vectors.get(id + r));
                    }
                }
            }
            int axis = 0; double widest = -1;
            for (int r = 0; r < dimensions; r++) {
                budget.operation(PlanningBudget.Operation.SCAN, 0);
                double relative = (double) (maximum[r] - query[r]) / Math.max(1, spread[r]);
                if (relative > widest) { widest = relative; axis = r; }
            }
            int middle = (begin + end) >>> 1;
            partition(begin, end, middle, axis);
            int a = nodes++, b = nodes++;
            left[node] = a; right[node] = b;
            from[a] = begin; to[a] = middle; from[b] = middle; to[b] = end;
            building[builds++] = ~node; building[builds++] = b; building[builds++] = a;
            return;
        }
        budget.operations(PlanningBudget.Operation.SCAN, 0, dimensions);
        vectors.copy(first, minimum, node * dimensions, dimensions);
        for (int p = begin + 1; p < end; p++) {
            int id = order[p] * dimensions;
            budget.operations(PlanningBudget.Operation.INTEGER, 64, dimensions);
            for (int r = 0; r < dimensions; r++) minimum[node * dimensions + r] = Math.min(minimum[node * dimensions + r], vectors.get(id + r));
        }
    }

    private void join(int node) {
        budget.operations(PlanningBudget.Operation.INTEGER, 64, dimensions);
        for (int r = 0; r < dimensions; r++)
            minimum[node * dimensions + r] = Math.min(minimum[left[node] * dimensions + r], minimum[right[node] * dimensions + r]);
    }

    private void partition(int begin, int end, int middle, int axis) {
        int low = begin, high = end - 1;
        while (low < high) {
            int pivot = order[(low + high) >>> 1], i = low, j = high;
            while (i <= j) {
                while (compare(order[i], pivot, axis) < 0) i++;
                while (compare(order[j], pivot, axis) > 0) j--;
                if (i <= j) {
                    int t = order[i];
                    order[i++] = order[j];
                    order[j--] = t;
                }

            }

            if (middle <= j) high = j;
            else if (middle >= i) low = i;
            else break;
        }

    }
    private int compare(int a, int b, int axis) {
        budget.operation(PlanningBudget.Operation.SCAN, 0);
        int c = Long.compare(vectors.get(a * dimensions + axis), vectors.get(b * dimensions + axis));
        return c != 0 ? c : Integer.compare(a, b);
    }

    boolean feasible() {
        return feasible;
    }

    long assignment() {
        if (!feasible) throw new IllegalStateException("No exchange witness");
        return assignment;
    }

    @Override public void close() {
        budget.release(memory);
        memory = 0;
    }

    private static final class Subsets {
        int start, end, count, changed;
        final int[] chosen;
        boolean first = true, done;
        Subsets(int start, int end, int radius) {
            chosen = new int[radius];
            reset(start, end);
        }

        void reset(int start, int end) {
            this.start = start;
            this.end = end;
            count = 0;
            changed = 0;
            first = true;
            done = false;
        }

        long next() {
            if (done) return -1L;
            if (first) {
                first = false;
                return 0;
            }

            int i = count - 1;
            while (i >= 0 && chosen[i] == end - count + i) i--;
            if (i < 0) {
                if (++count > Math.min(chosen.length, end - start)) {
                    done = true;
                    return -1L;
                }

                changed = 0;
                for (i = 0; i < count; i++) chosen[i] = start + i;
            } else {
                changed = i;
                chosen[i]++;
                for (int j = i + 1; j < count; j++) chosen[j] = chosen[j - 1] + 1;
            }

            long mask = 0;
            for (int j = 0; j < count; j++) mask |= 1L << chosen[j];
            return mask;
        }

    }
}
