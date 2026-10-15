// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Complete unit rows of a small Boolean relaxation, in the original coordinates. */
final class CountHallSupport {
    record Selection(List<ExactLinearProgram.Constraint> rows, int[] variables, long work) {}

    static Selection select(List<ExactLinearProgram.Constraint> source, BigInteger[] lower,
                            BigInteger[] upper, PlanningBudget budget, long allowance) {
        var selected = new ArrayList<ExactLinearProgram.Constraint>();
        var positive = new ArrayList<ExactLinearProgram.Constraint>();
        var variables = new BitSet(lower.length);
        long work = 0;
        int demands = 0;
        // Admission is optional. A partial scan keeps only COMPLETE rows, so
        // its consequences still follow from a relaxation of the input model.
        outer: for (var row : source) {
            if (work >= allowance) break;
            budget.check(); work++;
            if (row.terms().size() < 2 || row.terms().size() > 512 ||
                    !row.upper().abs().equals(BigInteger.ONE)) continue;
            // Choose demand support before considering capacities. Otherwise
            // unrelated positive unit rows can fill the variable cap before
            // the first exact-one group even reaches this optional analysis.
            if (row.upper().signum() > 0) {
                if (positive.size() < 2048) positive.add(row);
                continue;
            }
            int added = 0;
            for (var term : row.terms().entrySet()) {
                if (work >= allowance) break outer;
                budget.check(); work++;
                int id = term.getKey();
                if (!term.getValue().equals(row.upper()) || lower[id].signum() < 0 ||
                        upper[id] == null || upper[id].compareTo(BigInteger.ONE) > 0 ||
                        upper[id].compareTo(lower[id]) < 0) continue outer;
                if (!variables.get(id)) added++;
            }
            // Do not drop crossing terms or turn unselected coordinates into
            // zero. Reject the WHOLE row if its complete support does not fit.
            if (variables.cardinality() + added > 512 || selected.size() == 2048) continue;
            selected.add(row);
            for (int id : row.terms().keySet()) variables.set(id);
            demands++;
        }
        var demandsOnly = (BitSet) variables.clone();
        if (demands >= 2) outer: for (var row : positive) {
            boolean touches = false;
            int added = 0;
            for (var term : row.terms().entrySet()) {
                if (work >= allowance) break outer;
                budget.check(); work++;
                int id = term.getKey();
                if (!term.getValue().equals(BigInteger.ONE) || lower[id].signum() < 0 ||
                        upper[id] == null || upper[id].compareTo(BigInteger.ONE) > 0 ||
                        upper[id].compareTo(lower[id]) < 0) continue outer;
                touches |= demandsOnly.get(id);
                if (!variables.get(id)) added++;
            }
            if (!touches || variables.cardinality() + added > 512 || selected.size() == 2048) continue;
            selected.add(row);
            for (int id : row.terms().keySet()) variables.set(id);
        }
        return demands < 2 ? new Selection(List.of(), new int[0], work) :
                new Selection(List.copyOf(selected), variables.stream().toArray(), work);
    }

    private CountHallSupport() {}
}
