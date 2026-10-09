// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.BitSet;
import java.util.List;

/**
 * Potential-guided completion of a restricted repair neighborhood. Following
 * the idea in SCIP's repair heuristic, keep enough row activity available to
 * repair a violation. This only releases speculative fixings: neither a
 * cutoff nor a contradictory restricted row is a proof about the original.
 */
final class CountRepairDomains {

    private CountRepairDomains() {}

    static int widen(List<ExactLinearProgram.Constraint> rows, BigInteger[] originalLower, BigInteger[] originalUpper,
                     BigInteger[] lower, BigInteger[] upper, BitSet fixed, PlanningBudget budget, long allowance) {
        if (fixed.isEmpty() || allowance <= 0) return 0;
        long bytes = 1024L + 256L * lower.length + 16L * lower.length * ((lower.length + 63L) / 64);
        if (!budget.tryReserve(bytes)) return 0;
        try {
            return probe(rows, originalLower, originalUpper, lower, upper, fixed, budget, allowance);
        } finally {
            budget.release(bytes);
        }
    }

    private static int probe(List<ExactLinearProgram.Constraint> rows, BigInteger[] originalLower, BigInteger[] originalUpper,
                             BigInteger[] lower, BigInteger[] upper, BitSet fixed, PlanningBudget budget, long allowance) {
        long started = budget.threadWork();
        int released = 0;
        long copyWork = (4L * lower.length + 2L * lower.length * ((lower.length + 63L) / 64) + 3) / 4;
        if (copyWork >= allowance) return 0;
        budget.charge(copyWork);
        BigInteger[] probeLower = lower.clone(), probeUpper = upper.clone();
        BitSet[] lowerReasons = reasons(lower.length, fixed), upperReasons = reasons(lower.length, fixed);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var row : rows) {
                BigInteger minimum = BigInteger.ZERO;
                int unknown = 0;
                for (var term : row.terms().entrySet()) {
                    if (budget.threadWork() - started >= allowance) return released;
                    budget.operation(PlanningBudget.Operation.SCAN, 1);
                    if (term.getValue().signum() == 0) continue;
                    BigInteger value = term.getValue().signum() > 0 ? probeLower[term.getKey()] : probeUpper[term.getKey()];
                    if (value == null) {
                        unknown++;
                        continue;
                    }
                    budget.operation(PlanningBudget.Operation.INTEGER, term.getValue().bitLength() + value.bitLength());
                    minimum = minimum.add(term.getValue().multiply(value));
                }
                if (unknown == 0 && minimum.compareTo(row.upper()) > 0) {
                    int selected = -1;
                    BigInteger potential = BigInteger.ZERO;
                    for (var term : row.terms().entrySet()) {
                        if (budget.threadWork() - started >= allowance || released >= 32) return released;
                        budget.operation(PlanningBudget.Operation.SCAN, 1);
                        int id = term.getKey();
                        if (!fixed.get(id)) continue;
                        BigInteger coefficient = term.getValue();
                        if (coefficient.signum() == 0) continue;
                        BigInteger bound = coefficient.signum() > 0 ? originalLower[id] : originalUpper[id];
                        if (bound == null) {
                            selected = id;
                            break;
                        }
                        budget.operation(PlanningBudget.Operation.INTEGER,
                                coefficient.bitLength() + Math.max(bound.bitLength(), lower[id].bitLength()) + 1);
                        BigInteger gain = coefficient.multiply(lower[id].subtract(bound));
                        if (gain.compareTo(potential) > 0) {
                            selected = id;
                            potential = gain;
                        }
                    }
                    if (selected < 0) {
                        // The actual fixing can be several rows away from this
                        // conflict. Follow the bound reasons instead of freeing
                        // arbitrary nearby columns or rejecting this repair.
                        BitSet conflict = reason(row, -1, lowerReasons, upperReasons, budget);
                        conflict.and(fixed);
                        selected = conflict.nextSetBit(0);
                    }
                    if (selected < 0) return released;
                    lower[selected] = originalLower[selected];
                    upper[selected] = originalUpper[selected];
                    fixed.clear(selected);
                    released++;
                    // Earlier propagated bounds depend on the old fixings. Reset
                    // this private probe after releasing one, without exporting
                    // any of its assumptions to the real count search.
                    System.arraycopy(lower, 0, probeLower, 0, lower.length);
                    System.arraycopy(upper, 0, probeUpper, 0, upper.length);
                    lowerReasons = reasons(lower.length, fixed);
                    upperReasons = reasons(lower.length, fixed);
                    budget.charge(copyWork);
                    changed = true;
                    break;
                }
                for (var term : row.terms().entrySet()) {
                    if (budget.threadWork() - started >= allowance) return released;
                    budget.operation(PlanningBudget.Operation.SCAN, 1);
                    int id = term.getKey();
                    BigInteger coefficient = term.getValue();
                    if (coefficient.signum() == 0) continue;
                    BigInteger bound = coefficient.signum() > 0 ? probeLower[id] : probeUpper[id];
                    if (unknown - (bound == null ? 1 : 0) != 0) continue;
                    budget.operation(PlanningBudget.Operation.INTEGER,
                            coefficient.bitLength() + minimum.bitLength() + row.upper().bitLength() +
                                    (bound == null ? 0 : bound.bitLength()));
                    BigInteger limit = row.upper().subtract(minimum);
                    if (bound != null) limit = limit.add(coefficient.multiply(bound));
                    BigInteger[] division = limit.divideAndRemainder(coefficient);
                    BigInteger next = division[0];
                    if (division[1].signum() < 0) next = next.add(BigInteger.valueOf(coefficient.signum() > 0 ? -1 : 1));
                    if (coefficient.signum() > 0) {
                        if (probeUpper[id] == null || next.compareTo(probeUpper[id]) < 0) {
                            probeUpper[id] = next;
                            upperReasons[id] = reason(row, id, lowerReasons, upperReasons, budget);
                            changed = true;
                        }
                    } else if (next.compareTo(probeLower[id]) > 0) {
                        probeLower[id] = next;
                        lowerReasons[id] = reason(row, id, lowerReasons, upperReasons, budget);
                        changed = true;
                    }
                }
            }
        }
        return released;
    }

    private static BitSet[] reasons(int variables, BitSet fixed) {
        BitSet[] reasons = new BitSet[variables];
        for (int i = 0; i < variables; i++) {
            reasons[i] = new BitSet(variables);
            if (fixed.get(i)) reasons[i].set(i);
        }
        return reasons;
    }

    private static BitSet reason(ExactLinearProgram.Constraint row, int excluded,
                                 BitSet[] lower, BitSet[] upper, PlanningBudget budget) {
        BitSet reason = new BitSet(lower.length);
        for (var term : row.terms().entrySet()) {
            budget.operation(PlanningBudget.Operation.SCAN, lower.length);
            if (term.getKey() != excluded && term.getValue().signum() != 0)
                reason.or(term.getValue().signum() > 0 ? lower[term.getKey()] : upper[term.getKey()]);
        }
        return reason;
    }
}
