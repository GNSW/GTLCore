// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Integer-equivalent GCD scaling with one small-domain exceptional coefficient. */
final class CountIntegerCoefficients {

    private CountIntegerCoefficients() {}

    static ExactLinearProgram.Constraint simplify(ExactLinearProgram.Constraint row, BigInteger[] lower,
                                                  BigInteger[] upper, PlanningBudget budget) {
        int n = row.terms().size();
        if (n < 2 || n > 64) return row;
        long bytes = 256L + 160L * n;
        if (!budget.tryReserve(bytes)) return row;
        try {
            var terms = new ArrayList<>(row.terms().entrySet());
            BigInteger[] prefix = new BigInteger[n + 1], suffix = new BigInteger[n + 1];
            prefix[0] = suffix[n] = BigInteger.ZERO;
            for (int i = 0; i < n; i++) {
                budget.check();
                prefix[i + 1] = prefix[i].gcd(terms.get(i).getValue());
                suffix[n - i - 1] = suffix[n - i].gcd(terms.get(n - i - 1).getValue());
            }
            int probes = 0;
            for (int i = 0; i < n; i++) {
                budget.check();
                var term = terms.get(i);
                int id = term.getKey();
                if (upper[id] == null) continue;
                BigInteger span = upper[id].subtract(lower[id]);
                if (span.signum() <= 0 || span.compareTo(BigInteger.valueOf(16)) > 0) continue;
                BigInteger gcd = prefix[i].gcd(suffix[i + 1]);
                if (gcd.compareTo(BigInteger.ONE) <= 0 || term.getValue().remainder(gcd).signum() == 0) continue;
                if (++probes > 8) break;
                BigInteger residual = row.upper().subtract(term.getValue().multiply(lower[id]));
                BigInteger base = floor(residual, gcd), coefficient = base.subtract(floor(residual.subtract(term.getValue()), gcd));
                boolean affine = true;
                for (int value = 2; value <= span.intValueExact(); value++) {
                    budget.check();
                    BigInteger distance = BigInteger.valueOf(value);
                    if (!floor(residual.subtract(term.getValue().multiply(distance)), gcd).equals(base.subtract(coefficient.multiply(distance)))) {
                        affine = false;
                        break;
                    }
                }
                if (!affine) continue;
                // Every other term is divisible by gcd. The rounded RHS as a
                // function of this variable is exactly affine on its ENTIRE
                // domain, so this transformation preserves every integer point.
                Map<Integer, BigInteger> scaled = new LinkedHashMap<>();
                for (var original : terms) {
                    budget.check();
                    BigInteger value = original.getKey() == id ? coefficient : original.getValue().divide(gcd);
                    if (value.signum() != 0) scaled.put(original.getKey(), value);
                }
                return CountReduction.normalize(new ExactLinearProgram.Constraint(scaled, base.add(coefficient.multiply(lower[id]))));
            }
            return row;
        } finally {
            budget.release(bytes);
        }
    }

    private static BigInteger floor(BigInteger numerator, BigInteger denominator) {
        BigInteger[] qr = numerator.divideAndRemainder(denominator);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }
}
