// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Bounded tandem hints from repeated atoms; every accepted block is compared exactly. */
final class RefillWordAnchors implements AutoCloseable {
    private enum Phase { FIND, MATCH, EXTEND, PREFIX, BODY, EMIT, TAIL, OUTPUT, DONE }
    private record BatchKey(String recipe, long runs) {}
    private final List<PlanStep> reverse, groups = new ArrayList<>(), output = new ArrayList<>();
    private final PlanningBudget budget;
    private final long started, allowance;
    private final int minimumPeriod;
    private Map<BatchKey, Integer> batches = new HashMap<>();
    private Map<PlanStep, Integer> composites = new IdentityHashMap<>();
    private List<PlanStep> body;
    private PlanStep result;
    private Phase phase = Phase.FIND;
    private int scan, committed, start, period, offset, copies, next;
    private long memory, anchorBytes;
    private boolean allocated, changed, shortBlocks;

    RefillWordAnchors(List<PlanStep> reverse, PlanningBudget budget, long allowance) {
        this(reverse, budget, allowance, 2);
    }

    RefillWordAnchors(List<PlanStep> reverse, PlanningBudget budget, long allowance, int minimumPeriod) {
        if (minimumPeriod < 2) throw new IllegalArgumentException("Invalid minimum tandem period");
        this.reverse = reverse; this.budget = budget; this.allowance = allowance; started = budget.nodes();
        this.minimumPeriod = minimumPeriod;
    }

    boolean step() {
        budget.checkpoint();
        if (phase == Phase.DONE) return true;
        if (!allocated) { reserve(384); allocated = true; }
        for (int i = 0; i < 64; i++) {
            if (budget.nodes() - started >= allowance) { phase = Phase.DONE; return true; }
            budget.check();
            switch (phase) {
                case FIND -> {
                    if (scan == reverse.size()) {
                        if (!changed) { phase = Phase.DONE; return true; }
                        next = committed; phase = Phase.TAIL; break;
                    }
                    Integer previous;
                    PlanStep atom = reverse.get(scan);
                    if (atom instanceof PlanStep.Batch batch) {
                        var key = new BatchKey(batch.recipe(), batch.runs());
                        previous = batches.get(key);
                        if (previous == null) reserveAnchor();
                        if (replace(previous)) batches.put(key, scan);
                    } else {
                        previous = composites.get(atom);
                        if (previous == null) reserveAnchor();
                        if (replace(previous)) composites.put(atom, scan);
                    }
                    // The nearest equal atom is only a hint. A failed hint does
                    // not imply that a longer/different repetition is absent.
                    if (previous == null || scan - previous < minimumPeriod || scan - previous > (reverse.size() - previous) / 2) scan++;
                    else { start = previous; period = scan - previous; offset = 0; phase = Phase.MATCH; }
                }
                case MATCH -> {
                    if (!same(reverse.get(start + offset), reverse.get(start + period + offset))) { scan++; phase = Phase.FIND; }
                    else if (++offset == period) { copies = 2; offset = 0; phase = Phase.EXTEND; }
                }
                case EXTEND -> {
                    if (copies == (reverse.size() - start) / period ||
                            !same(reverse.get(start + offset), reverse.get(start + copies * period + offset))) {
                        next = committed; phase = Phase.PREFIX;
                    } else if (++offset == period) { copies++; offset = 0; }
                }
                case PREFIX -> {
                    if (next < start) { reserve(32); groups.add(reverse.get(next++)); }
                    else { reserve(64); body = new ArrayList<>(); next = period; phase = Phase.BODY; }
                }
                case BODY -> {
                    if (next > 0) { reserve(32); body.add(reverse.get(start + --next)); }
                    else phase = Phase.EMIT;
                }
                case EMIT -> {
                    shortBlocks |= period <= RefillWordBlocks.MAX_PERIOD;
                    reserve(160); groups.add(new PlanStep.Repeat(FrozenPlanSteps.owned(body), copies)); body = null;
                    committed = start + period * copies; scan = committed; changed = true;
                    // No published word owns these maps. Drop them in constant
                    // cursor work instead of clearing every retained anchor.
                    batches = new HashMap<>(); composites = new IdentityHashMap<>();
                    budget.release(anchorBytes); memory -= anchorBytes; anchorBytes = 0;
                    phase = Phase.FIND;
                }
                case TAIL -> {
                    if (next < reverse.size()) { reserve(32); groups.add(reverse.get(next++)); }
                    else { next = groups.size(); phase = Phase.OUTPUT; }
                }
                case OUTPUT -> {
                    if (next > 0) { reserve(32); output.add(groups.get(--next)); }
                    else { reserve(64); result = FrozenPlanSteps.owned(output); phase = Phase.DONE; return true; }
                }
                default -> throw new IllegalStateException("Unexpected anchor phase " + phase);
            }
        }
        return false;
    }

    private static boolean same(PlanStep a, PlanStep b) {
        // Hash collisions never authorize grouping. Opaque nodes use identity,
        // avoiding recursive equality and expansion of huge nested repeats.
        return a == b || a instanceof PlanStep.Batch x && b instanceof PlanStep.Batch y &&
                x.runs() == y.runs() && x.recipe().equals(y.recipe());
    }
    private boolean replace(Integer previous) {
        // In the larger-period continuation, nearby duplicates must not keep
        // hiding the first eligible anchor. Retain it until the distance can
        // span the requested floor. The original nearest-anchor path is intact.
        return previous == null || minimumPeriod == 2 || scan - previous >= minimumPeriod;
    }
    PlanStep result() { return result; }
    boolean hasShortBlocks() { return shortBlocks; }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    private void reserveAnchor() { reserve(128); anchorBytes += 128; }
    @Override public void close() { budget.release(memory); memory = 0; anchorBytes = 0; }
}
