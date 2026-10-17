// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Exact mixed-integer rounding of a nonnegative aggregation in nonnegative coordinates. */
final class CountMirCuts {

    private CountMirCuts() {}

    /** GMI disjunction on an integer tableau equality, including its integer slacks. */
    static ExactLinearProgram.Constraint tableau(List<ExactLinearProgram.Constraint> rows, List<BigInteger> multipliers,
                                                 Map<Integer, BigInteger> sum, BigInteger bound, BigInteger divisor,
                                                 Runnable charge) {
        if (divisor.signum() <= 0 || rows.size() != multipliers.size()) throw new IllegalArgumentException("Invalid GMI aggregation");
        BigInteger remainder = bound.mod(divisor);
        if (remainder.signum() == 0) return null;
        BigInteger complement = divisor.subtract(remainder);
        BigInteger rhs = remainder.multiply(complement).negate();
        Map<Integer, BigInteger> terms = new TreeMap<>();
        for (var term : sum.entrySet()) {
            charge.run();
            terms.put(term.getKey(), triangle(term.getValue().mod(divisor), remainder, complement, divisor).negate());
        }
        for (int i = 0; i < rows.size(); i++) {
            charge.run();
            BigInteger weight = triangle(multipliers.get(i).mod(divisor), remainder, complement, divisor);
            if (weight.signum() == 0) continue;
            rhs = rhs.add(rows.get(i).upper().multiply(weight));
            for (var term : rows.get(i).terms().entrySet()) {
                charge.run();
                terms.merge(term.getKey(), term.getValue().multiply(weight), BigInteger::add);
            }
        }
        terms.values().removeIf(value -> value.signum() == 0);
        return new ExactLinearProgram.Constraint(terms, rhs);
    }

    private static BigInteger triangle(BigInteger value, BigInteger remainder, BigInteger complement, BigInteger divisor) {
        return value.compareTo(remainder) <= 0 ? value.multiply(complement) : remainder.multiply(divisor.subtract(value));
    }

    static ExactLinearProgram.Constraint strengthen(Map<Integer, BigInteger> sum, BigInteger bound,
                                                    BigInteger divisor, Runnable charge) {
        if (divisor.signum() <= 0) throw new IllegalArgumentException("Nonpositive MIR divisor");
        BigInteger remainder = bound.mod(divisor);
        if (remainder.signum() == 0) return null;
        BigInteger factor = divisor.subtract(remainder);
        BigInteger rhs = bound.subtract(remainder).divide(divisor).multiply(factor);
        Map<Integer, BigInteger> terms = new TreeMap<>();
        for (var term : sum.entrySet()) {
            charge.run();
            BigInteger residue = term.getValue().mod(divisor);
            BigInteger coefficient = term.getValue().subtract(residue).divide(divisor).multiply(factor)
                    .add(residue.subtract(remainder).max(BigInteger.ZERO));
            if (coefficient.signum() != 0) terms.put(term.getKey(), coefficient);
        }
        return new ExactLinearProgram.Constraint(terms, rhs);
    }
}
