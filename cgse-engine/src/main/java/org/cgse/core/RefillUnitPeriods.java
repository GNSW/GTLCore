// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/** Factor an accepted unit's exact dispatch word, without unfolding repeats.
 * A rounded requirement is only an admission hint; the complete normalized
 * body must have an exact period before any representation is changed. */
final class RefillUnitPeriods implements AutoCloseable {
    private enum Phase { UNWRAP, NORMALIZE, PREFIX, BODY, WRAP, OUTPUT, DONE }
    private final List<PlanStep> reverse;
    private final PlanStep selected;
    private final PlanningBudget budget;
    private final long started, allowance, memoryLimit;
    private final List<Long> wrappers = new ArrayList<>();
    private final List<PlanStep> body = new ArrayList<>(), output = new ArrayList<>();
    private RefillWordNormalForms normal;
    private List<PlanStep> atoms;
    private int[] prefix;
    private PlanStep unit, replacement, result;
    private Phase phase = Phase.UNWRAP;
    private int index, matched, period;
    private long memory, prefixBytes;
    private boolean allocated, found;

    RefillUnitPeriods(List<PlanStep> reverse, PlanStep selected, PlanningBudget budget, long allowance) {
        this.reverse = reverse; this.selected = selected; unit = selected;
        this.budget = budget; this.allowance = allowance; started = budget.nodes();
        memoryLimit = Math.min(65536, budget.availableBytes() / 16);
    }
    boolean step() {
        budget.checkpoint();
        if (phase == Phase.DONE) return true;
        if (budget.nodes() - started >= allowance) return finish();
        if (!allocated) { if (!reserve(256)) return finish(); allocated = true; }
        // The child traversal owns its slice. Do not append another full slice.
        if (phase == Phase.NORMALIZE) {
            if (!normal.step()) return false;
            atoms = normal.result();
            if (atoms == null || atoms.size() < 4) return finish();
            prefixBytes = 16L + 4L * atoms.size();
            if (!reserve(prefixBytes)) { prefixBytes = 0; return finish(); }
            prefix = new int[atoms.size()]; index = 1; phase = Phase.PREFIX; return false;
        }
        for (int visits = 0; visits < 64; visits++) {
            if (budget.nodes() - started >= allowance) return finish();
            budget.check();
            switch (phase) {
                case UNWRAP -> {
                    if (unit instanceof PlanStep.Repeat r) {
                        if (!reserve(64)) return finish(); wrappers.add(r.times()); unit = r.body();
                    } else if (unit instanceof PlanStep.Sequence s && s.children().size() == 1) unit = s.children().get(0);
                    else {
                        if (!(unit instanceof PlanStep.Sequence)) return finish();
                        normal = new RefillWordNormalForms(List.of(unit), budget, allowance - (budget.nodes() - started));
                        phase = Phase.NORMALIZE; return false;
                    }
                }
                case PREFIX -> {
                    if (index == atoms.size()) {
                        period = atoms.size() - prefix[index - 1];
                        // A one-event period would let the pruning cursor merge
                        // dispatch batches. Leave that separate case unchanged.
                        if (period < 2 || period == atoms.size() || atoms.size() % period != 0) return finish();
                        prefix = null; budget.release(prefixBytes); memory -= prefixBytes; prefixBytes = 0;
                        index = period; phase = Phase.BODY;
                    } else if (atoms.get(index) == atoms.get(matched)) prefix[index++] = ++matched;
                    else if (matched > 0) matched = prefix[matched - 1];
                    else prefix[index++] = 0;
                }
                case BODY -> {
                    if (index > 0) {
                        if (!reserve(32)) return finish(); body.add(atoms.get(--index));
                    } else {
                        if (!reserve(128)) return finish();
                        replacement = new PlanStep.Repeat(FrozenPlanSteps.owned(body), atoms.size() / period);
                        index = wrappers.size(); phase = Phase.WRAP;
                    }
                }
                case WRAP -> {
                    if (index > 0) {
                        if (!reserve(64)) return finish(); replacement = new PlanStep.Repeat(replacement, wrappers.get(--index));
                    } else { index = reverse.size(); phase = Phase.OUTPUT; }
                }
                case OUTPUT -> {
                    if (index > 0) {
                        if (!reserve(32)) return finish();
                        var step = reverse.get(--index); found |= step == selected;
                        output.add(step == selected ? replacement : step);
                    } else {
                        if (found) { if (!reserve(64)) return finish(); result = FrozenPlanSteps.owned(output); }
                        return finish();
                    }
                }
                default -> throw new IllegalStateException("Unexpected period phase " + phase);
            }
        }
        return false;
    }
    private boolean reserve(long bytes) {
        if (bytes > memoryLimit - memory || !budget.tryReserve(bytes)) return false;
        memory += bytes; return true;
    }
    private boolean finish() { phase = Phase.DONE; return true; }
    PlanStep result() { return result; }
    @Override public void close() {
        if (normal != null) { normal.close(); normal = null; }
        atoms = null; prefix = null;
        budget.release(memory); memory = 0;
    }
}
