package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.util.List;

/** A serial execution witness. Batch can dispatch all available runs in one provider call. */
public sealed interface PlanStep permits PlanStep.Batch, PlanStep.Sequence, PlanStep.Repeat {

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
