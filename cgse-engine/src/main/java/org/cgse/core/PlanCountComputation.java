// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Counts a shared program in topological order, without expanding call occurrences. */
public final class PlanCountComputation {

    private final Deque<Frame> stack = new ArrayDeque<>();
    private Map<PlanStep, BigInteger> weights = new IdentityHashMap<>();
    private List<PlanStep> order = new ArrayList<>();
    private final Map<String, BigInteger> counts = new LinkedHashMap<>();
    private final PlanStep root;
    private boolean indexed, complete;
    private int cursor, child;

    public PlanCountComputation(PlanStep step) {
        root = step;
        stack.push(new Frame(step));
        weights.put(step, BigInteger.ZERO);
    }

    public boolean step(PlanningBudget budget) {
        for (int operation = 0; operation < 32 && !complete; operation++) {
            if (budget != null) budget.check();
            if (!indexed) {
                if (stack.isEmpty()) {
                    indexed = true;
                    weights.put(root, BigInteger.ONE);
                    cursor = order.size() - 1;
                    continue;
                }
                Frame frame = stack.peek();
                PlanStep next = null;
                if (frame.step instanceof PlanStep.Repeat repeat && repeat.times() > 0 && frame.child++ == 0) next = repeat.body();
                else if (frame.step instanceof PlanStep.Sequence sequence && frame.child < sequence.children().size()) next = sequence.children().get(frame.child++);
                if (next == null) {
                    if (frame.step instanceof PlanStep.Batch batch && batch.runs() > 0) counts.putIfAbsent(batch.recipe(), BigInteger.ZERO);
                    order.add(frame.step);
                    stack.pop();
                } else if (!weights.containsKey(next)) {
                    weights.put(next, BigInteger.ZERO);
                    stack.push(new Frame(next));
                }
                continue;
            }
            if (cursor < 0) {
                complete = true;
                // Drop completed scratch without synchronous array scans. Every
                // indexed edge has positive multiplicity (zero repeats are not
                // traversed), and only positive batches were inserted in counts.
                // Consequently no zero count can survive the propagation.
                order = null;
                weights = null;
                continue;
            }
            PlanStep node = order.get(cursor);
            BigInteger copies = weights.get(node);
            if (node instanceof PlanStep.Batch batch) {
                BigInteger runs = copies.multiply(BigInteger.valueOf(batch.runs()));
                if (runs.signum() > 0) counts.merge(batch.recipe(), runs, BigInteger::add);
            } else if (node instanceof PlanStep.Repeat repeat) {
                if (repeat.times() > 0) weights.merge(repeat.body(), copies.multiply(BigInteger.valueOf(repeat.times())), BigInteger::add);
            } else {
                var children = ((PlanStep.Sequence) node).children();
                if (child < children.size()) {
                    weights.merge(children.get(child++), copies, BigInteger::add);
                    continue;
                }
            }
            cursor--;
            child = 0;
        }
        return complete;
    }

    public Map<String, BigInteger> result() {
        if (!complete) throw new IllegalStateException("Counts incomplete");
        return Collections.unmodifiableMap(counts);
    }

    public static Map<String, BigInteger> of(PlanStep step) {
        PlanCountComputation computation = new PlanCountComputation(step);
        while (!computation.step(null)) { /* bounded by program structure */ }
        return computation.result();
    }

    private static final class Frame {

        final PlanStep step;
        int child;

        Frame(PlanStep step) {
            this.step = step;
        }
    }
}
