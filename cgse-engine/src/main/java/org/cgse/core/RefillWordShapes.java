// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/** Exact local hash-consing of opaque word atoms, with charged iterative traversal.
 * Structural identity preserves every sequence/repeat/batch boundary; it does
 * not use algebraic equivalence or unfold a repetition. */
final class RefillWordShapes implements AutoCloseable {
    private record BatchKey(String recipe, long runs) {}
    private record RepeatKey(int body, long times) {}
    private record SingletonKey(int child) {}
    private record SequenceKey(int tail) {}
    private record Link(int child, int tail) {}
    private record Entry(int id, PlanStep representative) {}
    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }
    private static final class Frame {
        final PlanStep step;
        int next, tail;
        Frame(PlanStep step) { this.step = step; next = step instanceof PlanStep.Sequence s ? s.children().size() - 1 : -1; }
    }
    private final List<PlanStep> reverse;
    private final PlanningBudget budget;
    private final long started, allowance, memoryLimit;
    private Map<PlanStep, Entry> known = new IdentityHashMap<>();
    private Map<Object, Entry> nodes = new HashMap<>();
    private Map<Link, Integer> links = new HashMap<>();
    private Deque<Frame> stack = new ArrayDeque<>();
    private List<PlanStep> output = new ArrayList<>(), result;
    private int next, nextNode, nextLink;
    private long memory, outputBytes;
    private boolean allocated, changed, done;

    RefillWordShapes(List<PlanStep> reverse, PlanningBudget budget, long allowance) {
        this.reverse = reverse; this.budget = budget; this.allowance = allowance; started = budget.nodes();
        memoryLimit = Math.min(262144, budget.availableBytes() / 16);
    }
    boolean step() {
        budget.checkpoint();
        if (done) return true;
        try {
            if (!allocated) { reserve(512); allocated = true; }
            for (int i = 0; i < 64; i++) {
                if (budget.nodes() - started >= allowance) return finish(false);
                budget.check();
                if (stack.isEmpty()) {
                    if (next == reverse.size()) return finish(true);
                    PlanStep atom = reverse.get(next); Entry entry = known.get(atom);
                    if (entry == null) push(atom);
                    else {
                        reserve(32); outputBytes += 32; output.add(entry.representative());
                        changed |= entry.representative() != atom; next++;
                    }
                    continue;
                }
                Frame frame = stack.peek(); PlanStep step = frame.step;
                if (step instanceof PlanStep.Batch b) complete(frame, new BatchKey(b.recipe(), b.runs()));
                else if (step instanceof PlanStep.Repeat r) {
                    Entry child = known.get(r.body());
                    if (child == null) push(r.body());
                    else complete(frame, new RepeatKey(child.id(), r.times()));
                } else {
                    var children = ((PlanStep.Sequence) step).children();
                    // Preserve the exact singleton boundary, but do not build a
                    // one-element linked signature and then look it up again.
                    if (children.size() == 1) {
                        PlanStep child = children.get(0); Entry entry = known.get(child);
                        if (entry == null) push(child);
                        else complete(frame, new SingletonKey(entry.id()));
                    } else if (frame.next < 0) complete(frame, new SequenceKey(frame.tail));
                    else {
                        PlanStep child = children.get(frame.next); Entry entry = known.get(child);
                        if (entry == null) push(child);
                        else {
                            var key = new Link(entry.id(), frame.tail); Integer tail = links.get(key);
                            if (tail == null) { reserve(128); tail = ++nextLink; links.put(key, tail); }
                            frame.tail = tail; frame.next--;
                        }
                    }
                }
            }
            return false;
        } catch (Stop refused) { return finish(false); }
    }
    private void push(PlanStep step) { reserve(64); stack.push(new Frame(step)); }
    private void complete(Frame frame, Object key) {
        Entry entry = nodes.get(key);
        if (entry == null) { reserve(160); entry = new Entry(++nextNode, frame.step); nodes.put(key, entry); }
        reserve(96); known.put(frame.step, entry); stack.pop(); budget.release(64); memory -= 64;
    }
    private void reserve(long bytes) {
        if (bytes > memoryLimit - memory || !budget.tryReserve(bytes)) throw new Stop();
        memory += bytes;
    }
    private boolean finish(boolean complete) {
        result = complete ? changed ? output : reverse : null;
        long keep = complete && changed ? outputBytes : 0;
        // No result references the private identity/intern tables or traversal.
        known = null; nodes = null; links = null; stack = null; output = null;
        budget.release(memory - keep); memory = keep; done = true; return true;
    }
    List<PlanStep> result() { return result; }
    boolean changed() { return changed; }
    @Override public void close() { known = null; nodes = null; links = null; stack = null; output = null; budget.release(memory); memory = 0; }
}
