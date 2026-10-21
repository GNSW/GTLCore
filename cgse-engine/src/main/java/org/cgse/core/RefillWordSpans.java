// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/** Expose equal nonoverlapping spans as individual units, preserving intervening
 * steps in place. Recognition does not remove anything; the refill verifier and
 * backward requirements decide whether a particular occurrence is dispensable. */
final class RefillWordSpans implements AutoCloseable {
    private enum Phase { FIND, MATCH, PREFIX, BODY, EMIT_FIRST, GAP, EMIT_SECOND, TAIL, OUTPUT, DONE }
    private record BatchKey(String recipe, long runs) {}
    private final List<PlanStep> reverse, groups = new ArrayList<>(), output = new ArrayList<>();
    private final PlanningBudget budget;
    private final long started, allowance;
    private Map<BatchKey, Integer> batches = new HashMap<>();
    private Map<PlanStep, Integer> composites = new IdentityHashMap<>();
    private List<PlanStep> body, previousBody;
    private PlanStep span, previousSpan, result;
    private Phase phase = Phase.FIND;
    private int scan, committed, start, period, matched, next;
    private long memory, anchorBytes, bodyBytes;
    private boolean allocated, changed, reuseSpan;

    RefillWordSpans(List<PlanStep> reverse, PlanningBudget budget, long allowance) {
        this.reverse = reverse; this.budget = budget; this.allowance = allowance; started = budget.nodes();
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
                    Integer previous; PlanStep atom = reverse.get(scan);
                    if (atom instanceof PlanStep.Batch b) {
                        var key = new BatchKey(b.recipe(), b.runs()); previous = batches.get(key);
                        if (previous == null) reserveAnchor(); batches.put(key, scan);
                    } else {
                        previous = composites.get(atom);
                        if (previous == null) reserveAnchor(); composites.put(atom, scan);
                    }
                    if (previous == null || scan - previous < 2) scan++;
                    else { start = previous; period = scan - start; matched = 1; phase = Phase.MATCH; }
                }
                case MATCH -> {
                    if (matched == period || matched == reverse.size() - scan ||
                            !same(reverse.get(start + matched), reverse.get(scan + matched))) {
                        if (matched < 2) { scan++; phase = Phase.FIND; }
                        else { next = committed; phase = Phase.PREFIX; }
                    } else matched++;
                }
                case PREFIX -> {
                    if (next < start) emit(reverse.get(next++));
                    else {
                        reserve(64); bodyBytes = 64; body = new ArrayList<>(); next = matched;
                        reuseSpan = previousBody != null && previousBody.size() == matched;
                        phase = Phase.BODY;
                    }
                }
                case BODY -> {
                    if (next > 0) {
                        var atom = reverse.get(start + --next);
                        if (reuseSpan) reuseSpan = same(previousBody.get(body.size()), atom);
                        reserve(32); bodyBytes += 32; body.add(atom);
                    }
                    else {
                        // Use the raw constructor: Repeat(Sequence,1) is a unit
                        // boundary for the pruning cursor, not a merged batch.
                        // Compare during the existing charged body walk. Equal
                        // spans on either side of unrelated gaps can share their
                        // immutable unit, letting pruning and verification reuse
                        // its exact fold. No occurrence or gap moves or vanishes.
                        if (reuseSpan) {
                            span = previousSpan; budget.release(bodyBytes); memory -= bodyBytes;
                        } else {
                            reserve(128); span = new PlanStep.Repeat(FrozenPlanSteps.owned(body), 1);
                            previousSpan = span; previousBody = body;
                        }
                        body = null; bodyBytes = 0;
                        phase = Phase.EMIT_FIRST;
                    }
                }
                case EMIT_FIRST -> { emit(span); next = start + matched; phase = Phase.GAP; }
                case GAP -> { if (next < scan) emit(reverse.get(next++)); else phase = Phase.EMIT_SECOND; }
                case EMIT_SECOND -> {
                    emit(span); span = null; committed = scan + matched; scan = committed; changed = true;
                    batches = new HashMap<>(); composites = new IdentityHashMap<>(); budget.release(anchorBytes); memory -= anchorBytes; anchorBytes = 0;
                    phase = Phase.FIND;
                }
                case TAIL -> {
                    if (next < reverse.size()) emit(reverse.get(next++));
                    else { next = groups.size(); phase = Phase.OUTPUT; }
                }
                case OUTPUT -> {
                    if (next > 0) { reserve(32); output.add(groups.get(--next)); }
                    else { reserve(64); result = FrozenPlanSteps.owned(output); phase = Phase.DONE; return true; }
                }
                default -> throw new IllegalStateException("Unexpected span phase " + phase);
            }
        }
        return false;
    }
    private static boolean same(PlanStep a, PlanStep b) {
        return a == b || a instanceof PlanStep.Batch x && b instanceof PlanStep.Batch y && x.runs() == y.runs() && x.recipe().equals(y.recipe());
    }
    private void emit(PlanStep step) { reserve(32); groups.add(step); }
    private void reserve(long bytes) { budget.reserve(bytes); memory += bytes; }
    private void reserveAnchor() { reserve(128); anchorBytes += 128; }
    PlanStep result() { return result; }
    @Override public void close() {
        previousBody = body = null; previousSpan = span = null;
        budget.release(memory); memory = 0; anchorBytes = bodyBytes = 0;
    }
}
