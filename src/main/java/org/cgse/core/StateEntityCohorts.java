// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Necessary threshold matchings for whole-key slots, using original supply.
 * At threshold q each whole slot of size >= q needs one cohort, and key s
 * supplies at most floor(count(s) / q) cohorts along its eligibility edges.
 * Smaller and mixed slots are relaxed away. Failure identifies a deficient
 * subset of this firing; success is never an exact allocation or a plan. */
final class StateEntityCohorts<K> implements AutoCloseable {
    record Deficit(List<Integer> slots, long threshold, int available) {}
    private enum Phase { THRESHOLD, GROUP, SUPPLIES, MATCH }
    private final List<StateEntityAllocation.Demand> demands;
    private final List<StateEntityAllocation.Supply<K>> supplies;
    private final boolean[] allowed;
    private final PlanningBudget budget;
    private final int fixed, width;
    private final int[] capacity, used, assigned, parentDemand, parentSupply, queue;
    private Phase phase = Phase.THRESHOLD;
    private int active, index, root;
    private long threshold, memory;
    private BigInteger divisor;
    private boolean done, possible;

    /** Borrows the owner's sorted whole-slot prefix and immutable edges.
     * Storage is optional; refusal must leave the owner's exact search live. */
    static <K> StateEntityCohorts<K> create(List<StateEntityAllocation.Demand> demands, int fixed,
            List<StateEntityAllocation.Supply<K>> supplies, boolean[] allowed, PlanningBudget budget) {
        if (fixed == 0) return null;
        long bytes = 384L + 64L * fixed + 24L * supplies.size();
        if (!budget.tryReserve(bytes)) return null;
        try { return new StateEntityCohorts<>(demands, fixed, supplies, allowed, budget, bytes); }
        catch (RuntimeException | Error failure) { budget.release(bytes); throw failure; }
    }

    private StateEntityCohorts(List<StateEntityAllocation.Demand> demands, int fixed,
            List<StateEntityAllocation.Supply<K>> supplies, boolean[] allowed, PlanningBudget budget, long bytes) {
        this.demands = demands; this.fixed = fixed; this.supplies = supplies; this.allowed = allowed;
        this.budget = budget; memory = bytes; width = supplies.size();
        capacity = new int[width]; used = new int[width]; parentSupply = new int[width];
        assigned = new int[fixed]; parentDemand = new int[fixed]; queue = new int[fixed];
    }

    /** One preparation term or augmenting path; all work remains charged to
     * the original request and all matching state survives step boundaries. */
    boolean step() {
        budget.checkpoint();
        if (done) return true;
        budget.check();
        switch (phase) {
            case THRESHOLD -> {
                if (active == fixed) { possible = done = true; return true; }
                threshold = demands.get(active).amount(); divisor = BigInteger.valueOf(threshold);
                phase = Phase.GROUP;
            }
            case GROUP -> {
                if (active < fixed && demands.get(active).amount() == threshold) assigned[active++] = -1;
                else { index = 0; phase = Phase.SUPPLIES; }
            }
            case SUPPLIES -> {
                if (index < width) {
                    // Descending thresholds only grow capacities and append
                    // slots. The previous matching remains feasible: retain
                    // used/assigned and augment only the newly active slots.
                    // Augmenting paths may still move any earlier assignment.
                    // Clamp before narrowing, even for supplies wider than long.
                    capacity[index] = supplies.get(index).count().divide(divisor).min(BigInteger.valueOf(active)).intValueExact();
                    index++;
                } else phase = Phase.MATCH;
            }
            case MATCH -> {
                if (root == active) phase = Phase.THRESHOLD;
                else if (augment(root)) root++;
                else done = true;
            }
        }
        return done;
    }

    private boolean augment(int start) {
        for (int d = 0; d < active; d++) { budget.check(); parentDemand[d] = -1; }
        for (int s = 0; s < width; s++) { budget.check(); parentSupply[s] = -1; }
        int head = 0, tail = 0; queue[tail++] = start; parentDemand[start] = -2;
        while (head < tail) {
            int d = queue[head++];
            for (int s = 0; s < width; s++) {
                budget.check();
                if (!allowed[d * width + s] || parentSupply[s] != -1) continue;
                parentSupply[s] = d;
                if (used[s] < capacity[s]) {
                    used[s]++;
                    for (int to = s; ; ) {
                        budget.check(); int slot = parentSupply[to], previous = assigned[slot];
                        assigned[slot] = to;
                        if (slot == start) return true;
                        to = previous;
                    }
                }
                for (int other = 0; other < active; other++) {
                    budget.check();
                    if (parentDemand[other] != -1 || assigned[other] != s) continue;
                    parentDemand[other] = s; queue[tail++] = other;
                }
            }
        }
        return false;
    }

    boolean possible() { return done && possible; }
    int failedSlot() { return demands.get(root).slot(); }

    /** A small checked support can be exported even from a larger matching.
     * This is a cohort deficit, not an ordinary physical-entity Hall deficit. */
    Deficit deficit() {
        if (!done || possible || memory == 0) return null;
        var slots = new ArrayList<Integer>();
        for (int d = 0; d < active; d++) {
            budget.check();
            if (parentDemand[d] != -1) {
                if (slots.size() == 32) return null;
                slots.add(demands.get(d).slot());
            }
        }
        long available = 0;
        for (int s = 0; s < width; s++) { budget.check(); if (parentSupply[s] != -1) available += capacity[s]; }
        if (slots.isEmpty() || available >= slots.size()) throw new IllegalStateException("Invalid cohort deficit");
        return new Deficit(List.copyOf(slots), threshold, (int) available);
    }

    @Override public void close() {
        budget.release(memory); memory = 0; divisor = null; done = true;
    }
}
