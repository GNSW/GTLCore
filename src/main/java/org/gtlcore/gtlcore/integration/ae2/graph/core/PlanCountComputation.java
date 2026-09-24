package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/** Counts authorizations symbolically, one plan node per step. */
public final class PlanCountComputation {

    private final Deque<Frame> stack = new ArrayDeque<>();
    private final Map<String, BigInteger> counts = new LinkedHashMap<>();

    public PlanCountComputation(PlanStep step) {
        stack.push(new Frame(step, BigInteger.ONE));
    }

    public boolean step(PlanningBudget budget) {
        for (int operation = 0; operation < 32; operation++) {
            budget.check();
            if (stack.isEmpty()) return true;
            Frame frame = stack.peek();
            if (frame.step instanceof PlanStep.Batch batch) {
                add(batch, frame.multiplier);
                stack.pop();
            } else if (frame.step instanceof PlanStep.Repeat repeat) {
                stack.pop();
                if (repeat.times() > 0) stack.push(new Frame(repeat.body(), frame.multiplier.multiply(BigInteger.valueOf(repeat.times()))));
            } else {
                var children = ((PlanStep.Sequence) frame.step).children();
                if (frame.child == children.size()) stack.pop();
                else {
                    PlanStep child = children.get(frame.child++);
                    // Large DAG witnesses are mostly flat batches. Counting one
                    // does not need a traversal frame and a later pop operation.
                    if (child instanceof PlanStep.Batch batch) add(batch, frame.multiplier);
                    else stack.push(new Frame(child, frame.multiplier));
                }
            }
        }
        return stack.isEmpty();
    }

    private void add(PlanStep.Batch batch, BigInteger multiplier) {
        BigInteger runs = multiplier.multiply(BigInteger.valueOf(batch.runs()));
        if (runs.signum() > 0) counts.merge(batch.recipe(), runs, BigInteger::add);
    }

    public Map<String, BigInteger> result() {
        if (!stack.isEmpty()) throw new IllegalStateException("Counts incomplete");
        return Collections.unmodifiableMap(counts);
    }

    private static final class Frame {

        private final PlanStep step;
        private final BigInteger multiplier;
        private int child;

        private Frame(PlanStep step, BigInteger multiplier) {
            this.step = step;
            this.multiplier = multiplier;
        }
    }
}
