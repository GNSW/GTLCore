// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/** Exact dispatch-trace identities under sequence association, singleton identity
 * and representable nested repeats. Never splits/merges a batch or unfolds a loop.
 * Representatives are existing words; equality is proved by their normalized keys. */
final class RefillWordNormalForms implements AutoCloseable {
    private record BatchKey(String recipe, long runs) {}
    private record RepeatKey(int body, long times) {}
    private record SequenceKey(int tail) {}
    private record Link(int child, int tail) {}
    private record Entry(int id, PlanStep representative, List<Entry> reverse, Entry body, long times) {}
    private static final class Stop extends RuntimeException {
        Stop() { super(null, null, false, false); }
    }
    private static final class Frame {
        final PlanStep original;
        PlanStep step;
        int next, tail, copied;
        long partBytes;
        List<Entry> parts;
        Entry copying;
        Frame(PlanStep step) {
            original = step;
            unwrap(step);
        }
        void unwrap(PlanStep step) {
            this.step = step;
            if (step instanceof PlanStep.Sequence s && s.children().size() != 1) { next = s.children().size() - 1; parts = new ArrayList<>(); }
        }
    }
    private final List<PlanStep> reverse;
    private final PlanningBudget budget;
    private final long started, allowance, memoryLimit;
    private Map<PlanStep, Entry> known = new IdentityHashMap<>();
    private Map<Object, Entry> nodes = new HashMap<>();
    private Map<Link, Integer> links = new HashMap<>();
    private Deque<Frame> stack = new ArrayDeque<>();
    private List<PlanStep> output = new ArrayList<>(), result;
    private Entry emitting;
    private int next, emitted, nextNode, nextLink;
    private long memory, outputBytes;
    private boolean allocated, changed, done;

    RefillWordNormalForms(List<PlanStep> reverse, PlanningBudget budget, long allowance) {
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
                if (emitting != null) {
                    if (emitted == emitting.reverse().size()) { emitting = null; next++; }
                    else append(emitting.reverse().get(emitted++).representative());
                } else if (stack.isEmpty()) {
                    if (next == reverse.size()) return finish(true);
                    PlanStep atom = reverse.get(next); Entry entry = known.get(atom);
                    if (entry == null) push(atom);
                    else if (entry.reverse() != null) { emitting = entry; emitted = 0; changed = true; }
                    else { append(entry.representative()); changed |= entry.representative() != atom; next++; }
                } else {
                    Frame f = stack.peek();
                    if (f.step instanceof PlanStep.Batch b) complete(f, new BatchKey(b.recipe(), b.runs()), null, 0);
                    else if (f.step instanceof PlanStep.Repeat r) {
                        Entry child = known.get(r.body());
                        if (child == null && r.times() == 1) f.unwrap(r.body());
                        else if (child == null) push(r.body());
                        else if (r.times() == 1) alias(f, child);
                        else {
                            long times = r.times();
                            if (child.body() != null && child.times() > 0 && times > 0 && times <= Long.MAX_VALUE / child.times()) {
                                times *= child.times(); child = child.body();
                            }
                            complete(f, new RepeatKey(child.id(), times), child, times);
                        }
                    } else if (((PlanStep.Sequence) f.step).children().size() == 1) {
                        PlanStep child = ((PlanStep.Sequence) f.step).children().get(0); Entry entry = known.get(child);
                        if (entry == null) f.unwrap(child); else alias(f, entry);
                    } else if (f.copying != null) {
                        if (f.copied == f.copying.reverse().size()) { f.copying = null; f.next--; }
                        else prepend(f, f.copying.reverse().get(f.copied++));
                    } else if (f.next < 0) {
                        if (f.parts.size() == 1) alias(f, f.parts.get(0));
                        else complete(f, new SequenceKey(f.tail), null, 0);
                    } else {
                        PlanStep child = ((PlanStep.Sequence) f.step).children().get(f.next); Entry entry = known.get(child);
                        if (entry == null) push(child);
                        else if (entry.reverse() != null) { f.copying = entry; f.copied = 0; }
                        else { prepend(f, entry); f.next--; }
                    }
                }
            }
            return false;
        } catch (Stop refused) { return finish(false); }
    }
    private void prepend(Frame f, Entry child) {
        var key = new Link(child.id(), f.tail); Integer tail = links.get(key);
        if (tail == null) { reserve(128); tail = ++nextLink; links.put(key, tail); }
        reserve(32); f.partBytes += 32; f.parts.add(child); f.tail = tail;
    }
    private void push(PlanStep step) { reserve(96); stack.push(new Frame(step)); }
    private void complete(Frame f, Object key, Entry body, long times) {
        Entry entry = nodes.get(key);
        if (entry == null) {
            reserve(192); entry = new Entry(++nextNode, f.step, f.parts, body, times); nodes.put(key, entry);
            f.partBytes = 0; // The interned node now owns this private child list.
        }
        alias(f, entry);
    }
    private void alias(Frame f, Entry entry) {
        reserve(96); known.put(f.original, entry); stack.pop(); release(96 + f.partBytes);
    }
    private void append(PlanStep step) { reserve(32); outputBytes += 32; output.add(step); }
    private void reserve(long bytes) {
        if (bytes > memoryLimit - memory || !budget.tryReserve(bytes)) throw new Stop();
        memory += bytes;
    }
    private void release(long bytes) { budget.release(bytes); memory -= bytes; }
    private boolean finish(boolean complete) {
        result = complete ? changed ? output : reverse : null;
        long keep = complete && changed ? outputBytes : 0;
        known = null; nodes = null; links = null; stack = null; output = null; emitting = null;
        release(memory - keep); done = true; return true;
    }
    List<PlanStep> result() { return result; }
    boolean changed() { return changed; }
    @Override public void close() {
        known = null; nodes = null; links = null; stack = null; output = null; emitting = null;
        release(memory);
    }
}
