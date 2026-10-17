// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** A proven forbidden conjunction, shared only within one immutable order model. */
record CountConflict(List<ExactLinearProgram.Constraint> assumptions) {

    CountConflict {
        Map<Map<Integer, BigInteger>, ExactLinearProgram.Constraint> strongest = new LinkedHashMap<>();
        for (var input : assumptions) {
            var row = CountReduction.normalize(input);
            var old = strongest.get(row.terms());
            if (old == null || row.upper().compareTo(old.upper()) < 0) strongest.put(row.terms(), row);
        }
        assumptions = List.copyOf(strongest.values());
    }

    boolean impliedBy(BigInteger[] lower, BigInteger[] upper, PlanningBudget budget) {
        for (var row : assumptions) {
            BigInteger maximum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check();
                BigInteger bound = term.getValue().signum() > 0 ? upper[term.getKey()] : lower[term.getKey()];
                if (bound == null) return false;
                maximum = maximum.add(term.getValue().multiply(bound));
            }
            if (maximum.compareTo(row.upper()) > 0) return false;
        }
        return true;
    }

    record Propagation(ExactLinearProgram.Constraint row, List<ExactLinearProgram.Constraint> premises) {}

    /** All but one forbidden assumption hold: the last one must be false. */
    Propagation propagate(BigInteger[] lower, BigInteger[] upper, PlanningBudget budget) {
        ExactLinearProgram.Constraint unresolved = null;
        for (var row : assumptions) {
            BigInteger minimum = BigInteger.ZERO, maximum = BigInteger.ZERO;
            for (var term : row.terms().entrySet()) {
                budget.check();
                int id = term.getKey();
                BigInteger low = term.getValue().signum() > 0 ? lower[id] : upper[id];
                BigInteger high = term.getValue().signum() > 0 ? upper[id] : lower[id];
                minimum = minimum == null || low == null ? null : minimum.add(term.getValue().multiply(low));
                maximum = maximum == null || high == null ? null : maximum.add(term.getValue().multiply(high));
            }
            if (minimum != null && minimum.compareTo(row.upper()) > 0) return null;
            if (maximum == null || maximum.compareTo(row.upper()) > 0) {
                if (unresolved != null) return null;
                unresolved = row;
            }
        }
        if (unresolved == null) return new Propagation(null, assumptions);
        // Most inspected clauses cannot propagate. Keep their hot path free of
        // a temporary premise list, without changing the certificate order.
        List<ExactLinearProgram.Constraint> premises = new ArrayList<>(assumptions.size() - 1);
        for (var row : assumptions) if (row != unresolved) premises.add(row);
        Map<Integer, BigInteger> terms = new LinkedHashMap<>();
        unresolved.terms().forEach((key, value) -> terms.put(key, value.negate()));
        return new Propagation(new ExactLinearProgram.Constraint(terms, unresolved.upper().negate().subtract(BigInteger.ONE)), premises);
    }
}
