package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.List;

/** A serial execution witness. Batch can dispatch all available runs in one provider call. */
public sealed interface PlanStep permits PlanStep.Batch, PlanStep.Sequence, PlanStep.Repeat {

    /** Keep the serialized long opcodes; nest repetitions to represent an exact larger count. */
    static PlanStep batch(String recipe, BigInteger count) {
        ExactAmounts.of(count);
        if (count.compareTo(ExactAmounts.LONG_MAX) <= 0) return new Batch(recipe, count.longValueExact());
        return repeat(new Batch(recipe, 1), count);
    }

    static PlanStep repeat(PlanStep body, BigInteger count) {
        ExactAmounts.of(count);
        if (count.compareTo(ExactAmounts.LONG_MAX) <= 0) return new Repeat(body, count.longValueExact());
        BigInteger[] division = count.divideAndRemainder(ExactAmounts.LONG_MAX);
        PlanStep full = repeat(new Repeat(body, Long.MAX_VALUE), division[0]);
        return division[1].signum() == 0 ? full : new Sequence(List.of(full, new Repeat(body, division[1].longValueExact())));
    }

    record Batch(String recipe, long runs) implements PlanStep {

        public Batch {
            CheckedAmounts.nonNegative(runs);
        }
    }

    record Sequence(List<PlanStep> children) implements PlanStep {

        public Sequence {
            children = List.copyOf(children);
        }
    }

    record Repeat(PlanStep body, long times) implements PlanStep {

        public Repeat {
            CheckedAmounts.nonNegative(times);
        }
    }
}
