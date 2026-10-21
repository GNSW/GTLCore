// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/** Necessary short-tandem condition, collected by already charged atom visits. */
final class RefillShortBlockHint implements AutoCloseable {
    private record BatchKey(String recipe, long runs) {}
    private static final int WINDOW = RefillWordBlocks.MAX_PERIOD;
    private static final long BYTES = 256L + 128L * WINDOW;
    private final PlanningBudget budget;
    private Set<BatchKey> batches = new HashSet<>();
    private Set<PlanStep> composites = Collections.newSetFromMap(new IdentityHashMap<>());
    private Object[] window = new Object[WINDOW];
    private int next;
    private boolean possible, released;

    static RefillShortBlockHint tryCreate(PlanningBudget budget) {
        if (BYTES > budget.availableBytes() / 16 || !budget.tryReserve(BYTES)) return null;
        return new RefillShortBlockHint(budget);
    }
    private RefillShortBlockHint(PlanningBudget budget) { this.budget = budget; }

    /** Constant-sized index maintenance during the caller's charged emission.
     * No extra pass, recursive equality, or unbounded per-atom scan. */
    void offer(PlanStep atom) {
        if (possible) return;
        Object key = atom instanceof PlanStep.Batch b ? new BatchKey(b.recipe(), b.runs()) : atom;
        if (key instanceof BatchKey b ? batches.contains(b) : composites.contains(atom)) {
            // Even a distance-one match is kept as a conservative possibility.
            // Any exact period2..16 necessarily has such a nearby equal atom.
            possible = true; close(); return;
        }
        Object old = window[next];
        if (old instanceof BatchKey b) batches.remove(b);
        else if (old != null) composites.remove(old);
        if (key instanceof BatchKey b) batches.add(b); else composites.add(atom);
        window[next] = key; if (++next == WINDOW) next = 0;
    }
    boolean possible() { return possible; }
    @Override public void close() {
        if (released) return;
        released = true; batches = null; composites = null; window = null;
        budget.release(BYTES);
    }
}
