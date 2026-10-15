// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

/** Retained, price-ordered subsets for a candidate-only LP neighborhood. */
final class CountLpPriceQueue implements AutoCloseable {
    private static final int MAX_PENDING = 32768, MAX_CHANGES = 4;
    private final PlanningBudget budget;
    private final Runnable check;
    private final long[] prices;
    private final long capacity;
    private int[] parts = new int[0];
    private long[] costs = new long[0];
    private int size, code, length, peak;
    private long cost, memory;
    private boolean complete, limited;

    CountLpPriceQueue(long[] prices, long capacity, PlanningBudget budget, Runnable check) {
        this.prices = prices;
        this.capacity = capacity;
        this.budget = budget;
        this.check = check;
    }

    int length() { return length; }
    int at(int index) { return (code >>> (7 * (length - index - 1))) & 127; }
    boolean complete() { return complete; }
    boolean limited() { return limited; }
    int peak() { return peak; }

    boolean permits(int extra) {
        for (int k = 0; k < length; k++) {
            charge();
            if (at(k) == extra) return false;
        }
        charge();
        return cost + prices[extra] <= capacity;
    }

    /** The current subset starts empty; each subsequent subset occurs once. */
    void advance() {
        if (complete) return;
        if (length == 0) {
            if (prices.length > 0) offer(0, 1, prices[0]);
        } else {
            int last = code & 127, following = last + 1;
            if (following < prices.length) {
                // Replacing the largest index or appending its successor gives
                // every increasing subset a unique parent. Sorted nonnegative
                // prices make both child costs monotone.
                offer((code & ~127) | following, length, cost - prices[last] + prices[following]);
                if (length < MAX_CHANGES)
                    offer((code << 7) | following, length + 1, cost + prices[following]);
            }
        }
        if (limited || size == 0) { complete = true; return; }
        int part = parts[0];
        code = part & 0x0fffffff;
        length = part >>> 28;
        cost = costs[0];
        int moved = parts[--size];
        long movedCost = costs[size];
        int index = 0;
        while (index * 2 + 1 < size) {
            int child = index * 2 + 1;
            if (child + 1 < size && less(costs[child + 1], parts[child + 1], costs[child], parts[child])) child++;
            if (!less(costs[child], parts[child], movedCost, moved)) break;
            parts[index] = parts[child]; costs[index] = costs[child];
            index = child;
        }
        if (size > 0) { parts[index] = moved; costs[index] = movedCost; }
    }

    private void offer(int nextCode, int nextLength, long nextCost) {
        charge();
        if (limited || nextCost > capacity) return;
        if (size == MAX_PENDING || !grow()) { limited = true; return; }
        int part = (nextLength << 28) | nextCode;
        int index = size++;
        peak = Math.max(peak, size);
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (!less(nextCost, part, costs[parent], parts[parent])) break;
            parts[index] = parts[parent]; costs[index] = costs[parent];
            index = parent;
        }
        parts[index] = part; costs[index] = nextCost;
    }

    private boolean grow() {
        if (size < parts.length) return true;
        int capacity = Math.min(MAX_PENDING, Math.max(64, parts.length * 2));
        long bytes = 128L + 12L * capacity;
        // Both arrays coexist with their replacements during a resize. Reserve
        // that peak, and retain the array capacity after removals from the heap.
        if (!budget.tryReserve(bytes)) return false;
        boolean transferred = false;
        try {
            var newParts = new int[capacity];
            var newCosts = new long[capacity];
            for (int i = 0; i < size; i++) {
                charge();
                newParts[i] = parts[i]; newCosts[i] = costs[i];
            }
            budget.release(memory);
            parts = newParts; costs = newCosts; memory = bytes;
            transferred = true;
            return true;
        } finally {
            if (!transferred) budget.release(bytes);
        }
    }

    private boolean less(long a, int partA, long b, int partB) {
        charge();
        return a < b || a == b && partA < partB;
    }

    private void charge() {
        check.run();
        budget.operation(PlanningBudget.Operation.INTEGER, 63);
    }

    @Override
    public void close() {
        complete = true;
        parts = new int[0]; costs = new long[0]; size = 0;
        budget.release(memory); memory = 0;
    }
}
