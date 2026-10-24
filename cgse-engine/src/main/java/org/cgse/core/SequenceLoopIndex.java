// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Optional, sliced recognition of repeated sequence prefixes; never rewrites a saved witness. */
final class SequenceLoopIndex {
    static final int MAX_WORK = 65_536;
    record Loop(Map<String, BigInteger> counts, int start, int width, int extent) {}
    private record Atom(String recipe, long runs) {}
    private record Repetition(int body, long times) {}
    private record Cell(int prefix, int child) {}
    private enum Empty { INSTANCE }
    private static final BigInteger COUNT_LIMIT = ExactAmounts.LONG_MAX.multiply(BigInteger.valueOf(PipelineScheduler.WINDOW));
    private static final BigInteger COUNT_OVERFLOW = COUNT_LIMIT.add(BigInteger.ONE);

    private final Map<PlanStep, NavigableMap<Integer, Loop>> loops = new IdentityHashMap<>();
    private Map<PlanStep, Integer> shapes = new IdentityHashMap<>();
    private Map<Object, Integer> canonical = new HashMap<>();
    private ArrayList<BigInteger> volumes = new ArrayList<>();
    private ArrayList<Frame> stack = new ArrayList<>();
    private PlanCountComputation counting;
    private PlanStep countingOwner;
    private int countingStart, countingWidth, countingExtent, work;
    private boolean complete;
    private final Storage storage;

    SequenceLoopIndex(PlanStep root) { this(root, new Storage()); }
    SequenceLoopIndex(PlanStep root, Storage storage) {
        this.storage = storage;
        volumes.add(BigInteger.ZERO); // Empty interned prefix, not a PlanStep ID.
        stack.add(new Frame(root));
    }

    /** Bound child-array storage across every lane of one job as well as traversal work. */
    static final class Storage {
        private int remaining = MAX_WORK;
        boolean reserve(int children) {
            if (children > remaining) return false;
            remaining -= children;
            return true;
        }
    }

    Loop get(PlanStep sequence, int offset) {
        var found = loops.get(sequence);
        if (found == null) return null;
        var candidate = found.floorEntry(offset);
        if (candidate != null && offset < candidate.getValue().extent()) return candidate.getValue();
        var prefix = found.get(0);
        return prefix != null && offset < prefix.extent() ? prefix : null;
    }
    int work() { return work; }
    boolean complete() { return complete; }

    /** Each unit is one structural edge, constant-size shape operation, or count operation. */
    void advance(int allowance) {
        while (!complete && allowance > 0) {
            int cost = counting == null ? 1 : 32;
            if (work + cost > MAX_WORK) { finish(); break; }
            if (cost > allowance) break;
            work += cost;
            allowance -= cost;
            if (counting != null) {
                if (counting.step(null)) {
                    var counts = counting.result();
                    if (counts.size() >= 2 && counts.size() <= PipelineScheduler.WINDOW
                            && counts.values().stream().allMatch(n -> n.compareTo(ExactAmounts.LONG_MAX) <= 0))
                        loops.computeIfAbsent(countingOwner, ignored -> new TreeMap<>()).put(countingStart,
                                new Loop(counts, countingStart, countingWidth, countingExtent));
                    counting = null;
                    countingOwner = null;
                }
            } else if (stack.isEmpty()) finish();
            else visit(stack.get(stack.size() - 1));
        }
    }

    private int intern(Object shape) {
        return canonical.computeIfAbsent(shape, ignored -> {
            BigInteger volume;
            if (shape instanceof Atom atom) volume = BigInteger.valueOf(atom.runs());
            else if (shape instanceof Repetition repeat)
                volume = volumes.get(repeat.body()).multiply(BigInteger.valueOf(repeat.times()));
            else if (shape instanceof Cell cell) volume = volumes.get(cell.prefix()).add(volumes.get(cell.child()));
            else volume = BigInteger.ZERO;
            volumes.add(volume.min(COUNT_OVERFLOW));
            return volumes.size() - 1;
        });
    }

    private void resolved(Frame frame, int shape) {
        shapes.put(frame.step, shape);
        stack.remove(stack.size() - 1);
    }

    private void visit(Frame frame) {
        if (frame.step instanceof PlanStep.Batch batch) {
            resolved(frame, intern(new Atom(batch.recipe(), batch.runs())));
        } else if (frame.step instanceof PlanStep.Repeat repeat) {
            Integer child = shapes.get(repeat.body());
            if (child == null) stack.add(new Frame(repeat.body()));
            else resolved(frame, repeat.times() == 1 ? child : intern(new Repetition(child, repeat.times())));
        } else {
            var sequence = (PlanStep.Sequence) frame.step;
            var children = sequence.children();
            if (frame.child == children.size()) {
                if (!frame.lastRunFlushed && frame.child - frame.sameStart > 1) {
                    frame.lastRunFlushed = true;
                    count(sequence, children.get(frame.sameStart), frame.sameStart, 1, frame.child);
                    return;
                }
                resolved(frame, children.isEmpty() ? intern(Empty.INSTANCE) :
                        children.size() == 1 ? frame.ids[0] : frame.chain);
                if (frame.extent > 0 && !(frame.width == 1 && frame.sameStart == 0)) {
                    PlanStep body = frame.width == 1 ? children.get(0) :
                            FrozenPlanSteps.owned(children.subList(0, frame.width));
                    count(sequence, body, 0, frame.width, frame.extent);
                }
                return;
            }
            if (frame.ids == null) {
                // Decline oversized optional work before allocating child arrays.
                if (children.size() > MAX_WORK - work || !storage.reserve(children.size())) { finish(); return; }
                frame.ids = new int[children.size()];
                frame.prefix = new int[children.size()];
                return;
            }
            if (frame.pending == 0) {
                PlanStep next = children.get(frame.child);
                Integer shape = shapes.get(next);
                if (shape == null && next instanceof PlanStep.Batch batch) {
                    shape = intern(new Atom(batch.recipe(), batch.runs()));
                    shapes.put(next, shape);
                }
                if (shape == null) { stack.add(new Frame(next)); return; }
                frame.pending = shape;
                frame.ids[frame.child] = shape;
                frame.previous = frame.child == 0 ? 0 : frame.prefix[frame.child - 1];
                return;
            }
            if (frame.child > 0 && frame.pending != frame.ids[frame.child - 1]) {
                int start = frame.sameStart;
                frame.sameStart = frame.child;
                if (frame.child - start > 1) {
                    count(sequence, children.get(start), start, 1, frame.child);
                    return;
                }
            }
            // One KMP fallback per work unit, including adversarial almost-periods.
            if (frame.child > 0 && frame.pending != frame.ids[frame.previous] && frame.previous > 0) {
                frame.previous = frame.prefix[frame.previous - 1];
                return;
            }
            if (frame.child > 0 && frame.pending == frame.ids[frame.previous]) frame.previous++;
            frame.prefix[frame.child] = frame.previous;
            int extent = frame.child + 1;
            int width = extent - frame.previous;
            if (frame.previous > 0 && extent % width == 0) { frame.width = width; frame.extent = extent; }
            // A chain of interned pairs has constant-size hash/equality operations;
            // hashing a complete child list here would defeat the tick allowance.
            frame.chain = intern(new Cell(frame.chain, frame.pending));
            frame.child++;
            frame.pending = 0;
        }
    }

    private void count(PlanStep owner, PlanStep body, int start, int width, int extent) {
        // Grouping allows at most WINDOW distinct recipes, each at most Long.MAX_VALUE.
        // Saturated shape totals reject larger candidates before exact nested counts
        // can allocate huge integers. For a prefix, the complete owner is a safe bound.
        int shape = shapes.get(width == 1 ? body : owner);
        if (volumes.get(shape).compareTo(COUNT_LIMIT) > 0) return;
        counting = new PlanCountComputation(body);
        countingOwner = owner;
        countingStart = start;
        countingWidth = width;
        countingExtent = extent;
    }

    private void finish() {
        complete = true;
        // Completed loops survive an optional cutoff. Drop scratch without a full map clear.
        stack = null;
        shapes = null;
        canonical = null;
        volumes = null;
        counting = null;
        countingOwner = null;
    }

    private static final class Frame {
        final PlanStep step;
        int child, pending, previous, chain, width, extent, sameStart;
        int[] ids, prefix;
        boolean lastRunFlushed;
        Frame(PlanStep step) { this.step = step; }
    }
}
