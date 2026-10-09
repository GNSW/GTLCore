// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A necessary execution condition: using a recipe implies a material inequality. */
record CountGuard(int recipe, ExactLinearProgram.Constraint required) {

    CountGuard {
        required = CountReduction.normalize(required);
    }

    boolean violated(ExactRational[] point, PlanningBudget budget) {
        if (point[recipe].signum() == 0) return false;
        ExactRational value = ExactRational.ZERO;
        for (var term : required.terms().entrySet()) {
            budget.check();
            value = value.add(point[term.getKey()].multiply(ExactRational.of(term.getValue())));
        }
        return value.compareTo(ExactRational.of(required.upper())) > 0;
    }

    CountConflict conflict() {
        Map<Integer, BigInteger> opposite = new LinkedHashMap<>();
        required.terms().forEach((key, value) -> opposite.put(key, value.negate()));
        return new CountConflict(List.of(
                new ExactLinearProgram.Constraint(Map.of(recipe, BigInteger.ONE.negate()), BigInteger.ONE.negate()),
                new ExactLinearProgram.Constraint(opposite, required.upper().negate().subtract(BigInteger.ONE))));
    }
}
