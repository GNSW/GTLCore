// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Exact adjacent tandem blocks in a completed reverse word; no recipe algebra. */
final class RefillWordBlocks implements AutoCloseable {
    private enum Phase { FIND, EXTEND, BODY, EMIT, OUTPUT, DONE }
    static final int MAX_PERIOD = 16;
    private final List<PlanStep> reverse, groups = new ArrayList<>(), output = new ArrayList<>();
    private final PlanningBudget budget;
    private final long started, allowance;
    private List<PlanStep> body;
    private PlanStep result;
    private Phase phase = Phase.FIND;
    private int position, period = 2, offset, copies, next;
    private long memory;
    private boolean changed, allocated;
    private Window window;

    RefillWordBlocks(List<PlanStep> reverse, PlanningBudget budget, long allowance) {
        this(reverse, budget, allowance, false);
    }

    RefillWordBlocks(List<PlanStep> reverse, PlanningBudget budget, long allowance, boolean indexed) {
        this.reverse = reverse; this.budget = budget; this.allowance = allowance; started = budget.nodes();
        if (indexed && Window.BYTES <= budget.availableBytes() / 16 && budget.tryReserve(Window.BYTES)) window = new Window();
    }

    boolean step() {
        budget.checkpoint();
        if (phase == Phase.DONE) return true;
        if (!allocated) { reserve(256); allocated = true; }
        for (int i = 0; i < 64; i++) {
            if (budget.nodes() - started >= allowance) { discardWindow(); phase = Phase.DONE; return true; }
            budget.check();
            switch (phase) {
                case FIND -> {
                    // Only equal first atoms can start a tandem. Fill the small
                    // lookahead incrementally, then skip exactly unequal periods.
                    // The complete block is still compared in its original order.
                    if (window != null && offset == 0 && position < reverse.size()) {
                        if (!window.prepare(position)) break;
                        period = window.nextPeriod(reverse.get(position), position, period);
                    }
                    if (position == reverse.size()) {
                        discardWindow();
                        if (!changed) { phase = Phase.DONE; return true; }
                        next = groups.size(); phase = Phase.OUTPUT;
                    } else if (period > MAX_PERIOD || period > (reverse.size() - position) / 2) {
                        reserve(32); groups.add(reverse.get(position++)); period = 2; offset = 0;
                    } else if (!same(reverse.get(position + offset), reverse.get(position + period + offset))) {
                        period++; offset = 0;
                    } else if (++offset == period) { copies = 2; offset = 0; phase = Phase.EXTEND; }
                }
                case EXTEND -> {
                    // Division bounds avoid overflow even for an enormous list.
                    if (copies == (reverse.size() - position) / period ||
                            !same(reverse.get(position + offset), reverse.get(position + copies * period + offset))) {
                        reserve(64); body = new ArrayList<>(); next = period; phase = Phase.BODY;
                    } else if (++offset == period) { copies++; offset = 0; }
                }
                case BODY -> {
                    if (next > 0) { reserve(32); body.add(reverse.get(position + --next)); }
                    else phase = Phase.EMIT;
                }
                case EMIT -> {
                    reserve(160);
                    // Keep each original batch boundary, including batch counts.
                    // Never collapse Repeat(Batch(n), m) into Batch(n*m).
                    groups.add(new PlanStep.Repeat(FrozenPlanSteps.owned(body), copies)); body = null;
                    position += period * copies; period = 2; offset = 0; changed = true; phase = Phase.FIND;
                }
                case OUTPUT -> {
                    if (next > 0) { reserve(32); output.add(groups.get(--next)); }
                    else { reserve(64); result = FrozenPlanSteps.owned(output); phase = Phase.DONE; return true; }
                }
                default -> throw new IllegalStateException("Unexpected block phase " + phase);
            }
        }
        return false;
    }

    private static boolean same(PlanStep a, PlanStep b) {
        // Record equals/hashCode recurse through composite words. Composite
        // identity is sufficient, but each occurrence is still counted.
        return a == b || a instanceof PlanStep.Batch x && b instanceof PlanStep.Batch y &&
                x.runs() == y.runs() && x.recipe().equals(y.recipe());
    }
    PlanStep result() { return result; }
    private void reserve(long bytes) {
        if (window != null && budget.availableBytes() < bytes) discardWindow();
        budget.reserve(bytes); memory += bytes;
    }
    private void discardWindow() {
        if (window != null) { window = null; budget.release(Window.BYTES); }
    }
    @Override public void close() { discardWindow(); budget.release(memory); memory = 0; }

    /** At most 15 future atoms. Masks use absolute positions modulo 32, so
     * eviction and insertion are constant sized and every update is charged. */
    private final class Window {
        private static final long BYTES = 4096;
        private record BatchKey(String recipe, long runs) {}
        private Map<BatchKey, Integer> batches = new HashMap<>();
        private Map<PlanStep, Integer> composites = new IdentityHashMap<>();
        private Object[] ring = new Object[32];
        private int first, end;

        boolean prepare(int position) {
            // Long arithmetic also covers a list near the maximum int size.
            int begin = (int) Math.min((long) reverse.size(), (long) position + 2);
            int limit = (int) Math.min((long) reverse.size(), (long) position + MAX_PERIOD + 1);
            if (begin > end) {
                // An accepted block can jump past the whole window. No retained
                // word owns these private containers; do not walk the skipped gap.
                batches = new HashMap<>(); composites = new IdentityHashMap<>(); ring = new Object[32];
                first = end = begin;
                return false;
            }
            if (first < begin) {
                int slot = first++ & 31, bit = 1 << slot; Object key = ring[slot]; ring[slot] = null;
                if (key instanceof BatchKey b) remove(batches, b, bit);
                else remove(composites, (PlanStep) key, bit);
                return false;
            }
            if (end < limit) {
                PlanStep atom = reverse.get(end); int slot = end++ & 31, bit = 1 << slot;
                if (atom instanceof PlanStep.Batch b) {
                    var key = new BatchKey(b.recipe(), b.runs()); ring[slot] = key;
                    batches.put(key, batches.getOrDefault(key, 0) | bit);
                } else { ring[slot] = atom; composites.put(atom, composites.getOrDefault(atom, 0) | bit); }
                // The final inserted atom completes the window. Its caller can
                // inspect the fixed-size mask now; no empty readiness turn is
                // needed. Filling/expiring each other atom still takes a visit.
                return end == limit;
            }
            return true;
        }
        private <T> void remove(Map<T, Integer> entries, T key, int bit) {
            int mask = entries.get(key) & ~bit;
            if (mask == 0) entries.remove(key); else entries.put(key, mask);
        }
        int nextPeriod(PlanStep atom, int position, int period) {
            if (period > MAX_PERIOD) return period;
            int mask = atom instanceof PlanStep.Batch b ? batches.getOrDefault(new BatchKey(b.recipe(), b.runs()), 0) : composites.getOrDefault(atom, 0);
            int allowed = (1 << (MAX_PERIOD + 1)) - 1;
            int distances = Integer.rotateRight(mask, position & 31) & allowed & (-1 << period);
            return distances == 0 ? MAX_PERIOD + 1 : Integer.numberOfTrailingZeros(distances);
        }
    }
}
