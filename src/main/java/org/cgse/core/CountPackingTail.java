// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

/** Exact suffix table for repeated queries in one Boolean face. All assignments
 * are retained; forced suffix values are query premises, never global facts. */
final class CountPackingTail implements AutoCloseable {
    private final PlanningBudget budget;
    private final int[] rejected, rejectedPoint;
    private final int offset, size, dimensions, points;
    private final long[][] coefficients;
    private final CountPackingValues vectors;
    private final long[] minimum, maximum, spread, common, possible, query;
    private final int[] order, from, to, left, right, stack;
    private long memory, fixed, selected, assignment;
    private int cursor, phase, nodes = 1, pending;
    private boolean ready, complete, feasible;

    static CountPackingTail create(long[][] coefficients, int offset, PlanningBudget budget) {
        int size = coefficients[0].length - offset;
        if (offset < 0 || size < 1 || size > 12) throw new IllegalArgumentException("Packing tail width");
        int points = 1 << size, nodes = 2 * ((points + 7) / 8);
        boolean compact = CountPackingValues.canPack(coefficients, offset, coefficients[0].length, budget);
        long bytes = 8192L + points * (8L + (compact ? 4L : 8L) * coefficients.length) + nodes * (36L + 8L * coefficients.length);
        if (!budget.tryReserve(bytes)) return null;
        try { return new CountPackingTail(coefficients, offset, budget, bytes, compact); }
        catch (RuntimeException failure) { budget.release(bytes); throw failure; }
    }

    private CountPackingTail(long[][] coefficients, int offset, PlanningBudget budget, long memory, boolean compact) {
        this.coefficients = coefficients; this.offset = offset; this.budget = budget; this.memory = memory;
        size = coefficients[0].length - offset; dimensions = coefficients.length; points = 1 << size;
        int capacity = 2 * ((points + 7) / 8);
        vectors = new CountPackingValues(points * dimensions, compact); minimum = new long[capacity * dimensions];
        maximum = new long[dimensions]; spread = new long[dimensions]; query = new long[dimensions];
        common = new long[capacity]; possible = new long[capacity];
        order = new int[points]; from = new int[capacity]; to = new int[capacity];
        rejectedPoint = new int[points];
        rejected = new int[capacity];
        left = new int[capacity]; right = new int[capacity]; stack = new int[64];
        // This complete Boolean box has an exact row spread without enumerating
        // its points. Relative spreads guide partitioning, never exclusions.
        for (int r = 0; r < dimensions; r++) {
            budget.operations(PlanningBudget.Operation.INTEGER, 64, size);
            for (int i = offset; i < offset + size; i++) spread[r] += Math.abs(coefficients[r][i]);
        }
    }

    /** One table point or one index node, with construction retained on yield. */
    boolean prepare() {
        if (ready) return true;
        budget.checkpoint();
        if (phase == 0) {
            if (cursor == points) {
                phase = 1; from[0] = 0; to[0] = points; stack[pending++] = 0;
                return false;
            }
            int previous = cursor & (cursor - 1), variable = Integer.numberOfTrailingZeros(cursor);
            budget.operations(PlanningBudget.Operation.INTEGER, 64, dimensions);
            for (int r = 0; r < dimensions; r++)
                vectors.set(cursor * dimensions + r, cursor == 0 ? 0 :
                        vectors.get(previous * dimensions + r) + coefficients[r][offset + variable]);
            order[cursor] = cursor; cursor++;
            return false;
        }
        if (pending == 0) return ready = true;
        int node = stack[--pending];
        if (node >= 0) build(node);
        else join(~node);
        return false;
    }

    void begin(long undecided, long selected, long[] slacks) {
        if (!ready) throw new IllegalStateException("Unprepared suffix table");
        long mask = (1L << size) - 1;
        fixed = (~(undecided >>> offset)) & mask;
        this.selected = (selected >>> offset) & fixed;
        // slacks = b - fixed activity - minimum remaining activity. Restore
        // the full suffix RHS, including forced coordinates checked by masks.
        for (int r = 0; r < dimensions; r++) {
            long bound = slacks[r];
            budget.operations(PlanningBudget.Operation.INTEGER, 64, size);
            for (int i = 0; i < size; i++) {
                long a = coefficients[r][offset + i], bit = 1L << i;
                if ((fixed & bit) == 0) bound += Math.min(0, a);
                else if ((this.selected & bit) != 0) bound += a;
            }
            query[r] = bound;
        }
        pending = 1; stack[0] = 0; complete = feasible = false;
    }

    /** Exact orthant query; a failed query only closes its caller's prefix. */
    boolean step() {
        if (complete) return true;
        budget.checkpoint();
        if (pending == 0) return complete = true;
        int node = stack[--pending];
        budget.operation(PlanningBudget.Operation.SCAN, 0);
        if ((common[node] & fixed & ~selected) != 0 || (selected & ~possible[node]) != 0) return false;
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
            stack[pending++] = right[node]; stack[pending++] = left[node];
            return false;
        }
        for (int p = from[node]; p < to[node]; p++) {
            int id = order[p];
            budget.operation(PlanningBudget.Operation.SCAN, 0);
            if ((((long) id ^ selected) & fixed) != 0) continue;
            int pointFirst = rejectedPoint[id] == 0 ? dimensions - 1 : rejectedPoint[id] - 1;
            budget.operation(PlanningBudget.Operation.SCAN, 0);
            boolean valid = vectors.get(id * dimensions + pointFirst) <= query[pointFirst];
            if (valid) for (int r = dimensions - 1; r >= 0; r--) {
                if (r == pointFirst) continue;
                budget.operation(PlanningBudget.Operation.SCAN, 0);
                if (vectors.get(id * dimensions + r) > query[r]) { rejectedPoint[id] = r + 1; valid = false; break; }
            }
            if (valid) { assignment = (long) id << offset; feasible = complete = true; return true; }
        }
        return false;
    }

    private void build(int node) {
        int begin = from[node], end = to[node], start = order[begin];
        if (end - begin > 8) {
            // A sample chooses the partition axis only. No sampled minimum is
            // retained as a bound: exact leaf minima are joined after both
            // children complete. Repeated full scans at every tree level would
            // cost the same suffix work this index is intended to avoid.
            budget.operations(PlanningBudget.Operation.SCAN, 0, 2 * dimensions);
            vectors.copy(start * dimensions, query, 0, dimensions);
            vectors.copy(start * dimensions, maximum, 0, dimensions);
            for (int sample = 1; sample < 8; sample++) {
                int id = order[begin + sample * (end - begin - 1) / 7];
                budget.operations(PlanningBudget.Operation.INTEGER, 64, 2 * dimensions);
                for (int r = 0; r < dimensions; r++) {
                    long value = vectors.get(id * dimensions + r);
                    query[r] = Math.min(query[r], value); maximum[r] = Math.max(maximum[r], value);
                }
            }
            int axis = 0; double widest = -1;
            for (int r = 0; r < dimensions; r++) {
                budget.operation(PlanningBudget.Operation.SCAN, 0);
                double width = (double) (maximum[r] - query[r]) / Math.max(1, spread[r]);
                if (width > widest) { widest = width; axis = r; }
            }
            int middle = (begin + end) >>> 1;
            partition(begin, end, middle, axis);
            int a = nodes++, b = nodes++;
            left[node] = a; right[node] = b;
            from[a] = begin; to[a] = middle; from[b] = middle; to[b] = end;
            stack[pending++] = ~node; stack[pending++] = b; stack[pending++] = a;
            return;
        }
        budget.operations(PlanningBudget.Operation.SCAN, 0, 2 * dimensions);
        vectors.copy(start * dimensions, minimum, node * dimensions, dimensions);
        long all = start, any = start;
        for (int p = begin + 1; p < end; p++) {
            int id = order[p]; all &= id; any |= id;
            budget.operations(PlanningBudget.Operation.INTEGER, 64, dimensions + 2);
            for (int r = 0; r < dimensions; r++) {
                long value = vectors.get(id * dimensions + r);
                minimum[node * dimensions + r] = Math.min(minimum[node * dimensions + r], value);
            }
        }
        common[node] = all; possible[node] = any;
    }

    private void join(int node) {
        int a = left[node], b = right[node];
        budget.operations(PlanningBudget.Operation.INTEGER, 64, dimensions + 2);
        for (int r = 0; r < dimensions; r++) {
            minimum[node * dimensions + r] = Math.min(minimum[a * dimensions + r], minimum[b * dimensions + r]);
        }
        common[node] = common[a] & common[b]; possible[node] = possible[a] | possible[b];
    }

    private void partition(int begin, int end, int middle, int axis) {
        int low = begin, high = end - 1;
        while (low < high) {
            int pivot = order[(low + high) >>> 1], i = low, j = high;
            while (i <= j) {
                while (compare(order[i], pivot, axis) < 0) i++;
                while (compare(order[j], pivot, axis) > 0) j--;
                if (i <= j) { int value = order[i]; order[i++] = order[j]; order[j--] = value; }
            }
            if (middle <= j) high = j;
            else if (middle >= i) low = i;
            else break;
        }
    }
    private int compare(int first, int second, int axis) {
        budget.operation(PlanningBudget.Operation.SCAN, 0);
        int result = Long.compare(vectors.get(first * dimensions + axis), vectors.get(second * dimensions + axis));
        return result == 0 ? Integer.compare(first, second) : result;
    }
    boolean feasible() { return feasible; }
    long assignment() {
        if (!feasible) throw new IllegalStateException("No suffix witness");
        return assignment;
    }
    @Override public void close() { budget.release(memory); memory = 0; }
}
