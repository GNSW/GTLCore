// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

/** Private-builder transfer; public sequence construction still makes a copy. */
final class FrozenPlanSteps extends AbstractList<PlanStep> implements RandomAccess {
    private final List<PlanStep> values;

    private FrozenPlanSteps(List<PlanStep> values) { this.values = values; }

    /** All entries must be non-null, and the builder must never be mutated again. */
    static PlanStep.Sequence owned(List<PlanStep> values) {
        return new PlanStep.Sequence(new FrozenPlanSteps(values));
    }

    static List<PlanStep> freeze(List<PlanStep> values) {
        return values instanceof FrozenPlanSteps ? values : List.copyOf(values);
    }

    @Override public PlanStep get(int index) { return values.get(index); }
    @Override public int size() { return values.size(); }
}
