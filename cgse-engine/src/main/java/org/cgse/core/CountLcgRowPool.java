// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.function.IntConsumer;

/** Activity is optional; row identities and proof premises are never removed. */
final class CountLcgRowPool implements AutoCloseable {

    private final PlanningBudget budget;
    private final Runnable charge;
    private final int original, desired;
    private final boolean[] active;
    private final int[] pins, lastUseful, lastWake, visits, gains;
    private final long[] costs;
    private long memory, slept, woke, skipped, pinChecks;
    private int size, activeDerived, epoch = -1, cursor;

    CountLcgRowPool(int original, int capacity, int variables, PlanningBudget budget, Runnable charge) {
        this.original = original;
        this.budget = budget;
        this.charge = charge;
        desired = Math.max(32, 2 * variables);
        long bytes = 256L + 64L * capacity;
        if (!budget.tryReserve(bytes)) {
            active = null;
            pins = lastUseful = lastWake = visits = gains = null;
            costs = null;
            return;
        }
        memory = bytes;
        try {
            active = new boolean[capacity];
            pins = new int[capacity];
            lastUseful = new int[capacity];
            lastWake = new int[capacity];
            visits = new int[capacity];
            gains = new int[capacity];
            costs = new long[capacity];
        } catch (RuntimeException | Error failed) {
            budget.release(memory);
            memory = 0;
            throw failed;
        }
        size = original;
    }

    void added(int id, int decision) {
        if (active == null) return;
        charge.run();
        active[id] = true;
        lastUseful[id] = lastWake[id] = decision;
        size = id + 1;
        activeDerived++;
    }

    boolean active(int id) {
        if (active == null || id < original || active[id]) return true;
        skipped++;
        return false;
    }

    void pin(int id) {
        if (active == null || id < original) return;
        charge.run();
        if (!active[id]) throw new IllegalStateException("Sleeping propagation reason");
        pins[id]++;
        pinChecks++;
    }

    void unpin(int id) {
        if (active == null || id < original) return;
        charge.run();
        if (--pins[id] < 0) throw new IllegalStateException("Unbalanced row pin");
    }

    void observed(int id, int gain, long work, int decision) {
        if (active == null || id < original) return;
        charge.run();
        if (visits[id] < Integer.MAX_VALUE) visits[id]++;
        long cost = Math.max(1, work);
        costs[id] = costs[id] > Long.MAX_VALUE - cost ? Long.MAX_VALUE : costs[id] + cost;
        gains[id] = (int) Math.min(Integer.MAX_VALUE, gains[id] + (long) gain);
        if (gain > 0) lastUseful[id] = decision;
    }

    /** Bounded ageing, then round-robin reconsideration of every sleeping row. */
    void maintain(int decision, IntConsumer deactivate, IntConsumer activate) {
        if (active == null || decision / 32 == epoch) return;
        epoch = decision / 32;
        if (activeDerived > desired) {
            double totalYield = 0;
            int candidates = 0;
            for (int id = original; id < size; id++) {
                charge.run();
                if (active[id] && pins[id] == 0) {
                    totalYield += productivity(id);
                    candidates++;
                }
            }
            double mean = candidates == 0 ? 0 : totalYield / candidates;
            for (int id = original; id < size && activeDerived > desired; id++) {
                charge.run();
                if (!active[id] || pins[id] != 0 || visits[id] < 4 || decision - lastUseful[id] < 64 || decision - lastWake[id] < 64) continue;
                if (productivity(id) > mean) continue;
                active[id] = false;
                activeDerived--;
                slept++;
                deactivate.accept(id);
            }
        }
        int quota = Math.max(1, desired / 32), inspected = 0;
        while (quota > 0 && inspected < size - original) {
            if (cursor < original || cursor >= size) cursor = original;
            int id = cursor++;
            inspected++;
            charge.run();
            if (active[id] || decision - lastWake[id] < 64) continue;
            active[id] = true;
            activeDerived++;
            lastWake[id] = decision;
            woke++;
            activate.accept(id);
            quota--;
        }
    }

    void restart(int decision, IntConsumer activate) {
        if (active == null) return;
        for (int id = original; id < size; id++) {
            charge.run();
            if (active[id]) continue;
            active[id] = true;
            activeDerived++;
            lastWake[id] = decision;
            woke++;
            activate.accept(id);
        }
    }

    private double productivity(int id) {
        return (double) gains[id] / Math.max(1, costs[id]);
    }

    String diagnostic() {
        return "slept=" + slept + "; woke=" + woke + "; skipped=" + skipped + "; pins=" + pinChecks + "; active=" + activeDerived;
    }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
