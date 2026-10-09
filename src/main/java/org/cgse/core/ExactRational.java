// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;

/** Reduced rationals for bounded analyses; never round a long-sized deficit. */
final class ExactRational implements Comparable<ExactRational> {

    private static final int MAX_BITS = 2048;
    private final BigInteger numerator, denominator;

    static final ExactRational ZERO = new ExactRational(BigInteger.ZERO, BigInteger.ONE);
    static final ExactRational ONE = new ExactRational(BigInteger.ONE, BigInteger.ONE);

    ExactRational(BigInteger numerator, BigInteger denominator) {
        this(numerator, denominator, false);
    }

    /** Only arithmetic preserving coprimality may bypass normalization. */
    private ExactRational(BigInteger numerator, BigInteger denominator, boolean reduced) {
        if (denominator.signum() == 0) throw new ArithmeticException("Zero denominator");
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        if (numerator.signum() == 0) denominator = BigInteger.ONE;
        else if (!reduced && !denominator.equals(BigInteger.ONE)) {
            BigInteger common = numerator.gcd(denominator);
            if (!common.equals(BigInteger.ONE)) {
                numerator = numerator.divide(common);
                denominator = denominator.divide(common);
            }
        }
        if (numerator.bitLength() > MAX_BITS || denominator.bitLength() > MAX_BITS) throw new PrecisionLimit();
        this.numerator = numerator;
        this.denominator = denominator;
    }

    BigInteger numerator() {
        return numerator;
    }

    BigInteger denominator() {
        return denominator;
    }

    static ExactRational of(BigInteger value) {
        return value.signum() == 0 ? ZERO : value.equals(BigInteger.ONE) ? ONE : new ExactRational(value, BigInteger.ONE, true);
    }

    int signum() {
        return numerator.signum();
    }

    ExactRational negate() {
        return signum() == 0 ? this : new ExactRational(numerator.negate(), denominator, true);
    }

    ExactRational add(ExactRational other) {
        return combine(other, false);
    }

    private ExactRational combine(ExactRational other, boolean subtract) {
        if (other.signum() == 0) return this;
        if (signum() == 0) return subtract ? other.negate() : other;
        if (denominator.equals(other.denominator)) {
            BigInteger result = subtract ? numerator.subtract(other.numerator) : numerator.add(other.numerator);
            if (denominator.equals(BigInteger.ONE)) return of(result);
            if (result.signum() == 0) return ZERO;
            BigInteger cancel = result.gcd(denominator);
            return new ExactRational(quotient(result, cancel), quotient(denominator, cancel), true);
        }
        // Adding an integer cannot change coprimality of a reduced fraction.
        // Avoid GCD/division work in integral simplex and lattice columns.
        if (denominator.equals(BigInteger.ONE)) {
            BigInteger scaled = numerator.multiply(other.denominator);
            return new ExactRational(subtract ? scaled.subtract(other.numerator) : scaled.add(other.numerator), other.denominator, true);
        }
        if (other.denominator.equals(BigInteger.ONE)) {
            BigInteger scaled = other.numerator.multiply(denominator);
            return new ExactRational(subtract ? numerator.subtract(scaled) : numerator.add(scaled), denominator, true);
        }
        BigInteger common = denominator.gcd(other.denominator);
        BigInteger left = quotient(denominator, common), right = quotient(other.denominator, common);
        BigInteger a = numerator.multiply(right), b = other.numerator.multiply(left);
        BigInteger result = subtract ? a.subtract(b) : a.add(b);
        if (result.signum() == 0) return ZERO;
        // With coprime inputs, any remaining cancellation divides the old
        // denominator GCD, not the much larger new denominator product.
        BigInteger cancel = common.equals(BigInteger.ONE) ? BigInteger.ONE : result.gcd(common);
        return new ExactRational(quotient(result, cancel), left.multiply(quotient(other.denominator, cancel)), true);
    }

    ExactRational subtract(ExactRational other) {
        return combine(other, true);
    }

    ExactRational multiply(ExactRational other) {
        if (signum() == 0 || other.signum() == 0) return ZERO;
        if (equals(ONE)) return other;
        if (other.equals(ONE)) return this;
        if (integral() && other.integral()) return of(numerator.multiply(other.numerator));
        BigInteger a = other.integral() ? BigInteger.ONE : numerator.gcd(other.denominator);
        BigInteger b = integral() ? BigInteger.ONE : other.numerator.gcd(denominator);
        // Cross-cancellation completely reduces this product. Recomputing a
        // GCD of the products repeats the expensive part of every LP pivot.
        return new ExactRational(quotient(numerator, a).multiply(quotient(other.numerator, b)),
                quotient(denominator, b).multiply(quotient(other.denominator, a)), true);
    }

    /** Sufficient bound before cancellation; false means use the original operation order. */
    private boolean productFitsPrecision(ExactRational other) {
        // The extra sign bits conservatively cover BigInteger's negative powers
        // of two without allocating absolute-value wrappers in the pivot loop.
        return numerator.bitLength() + other.numerator.bitLength() + 2 <= MAX_BITS &&
                denominator.bitLength() + other.denominator.bitLength() <= MAX_BITS;
    }

    /** Reuse b/c across many a*b/c operations without changing their precision cutoffs. */
    static final class ProductQuotient {
        private final ExactRational multiplier, divisor, factor;

        ProductQuotient(ExactRational multiplier, ExactRational divisor) {
            this.multiplier = multiplier;
            this.divisor = divisor;
            ExactRational prepared;
            try { prepared = multiplier.divide(divisor); }
            catch (PrecisionLimit optionalFactorTooWide) { prepared = null; }
            factor = prepared;
        }

        ExactRational apply(ExactRational value) {
            return factor != null && value.productFitsPrecision(multiplier) ?
                    value.multiply(factor) : value.multiply(multiplier).divide(divisor);
        }
    }

    ExactRational divide(ExactRational other) {
        if (other.signum() == 0) throw new ArithmeticException("Zero denominator");
        if (signum() == 0) return ZERO;
        if (other.equals(ONE)) return this;
        BigInteger a = numerator.gcd(other.numerator);
        BigInteger b = integral() || other.integral() ? BigInteger.ONE : denominator.gcd(other.denominator);
        return new ExactRational(quotient(numerator, a).multiply(quotient(other.denominator, b)),
                quotient(denominator, b).multiply(quotient(other.numerator, a)), true);
    }

    private static BigInteger quotient(BigInteger value, BigInteger divisor) {
        return divisor.equals(BigInteger.ONE) ? value : value.divide(divisor);
    }

    BigInteger floor() {
        if (integral()) return numerator;
        BigInteger[] parts = numerator.divideAndRemainder(denominator);
        return numerator.signum() < 0 && parts[1].signum() != 0 ? parts[0].subtract(BigInteger.ONE) : parts[0];
    }

    BigInteger ceil() {
        if (integral()) return numerator;
        BigInteger[] parts = numerator.divideAndRemainder(denominator);
        return numerator.signum() > 0 && parts[1].signum() != 0 ? parts[0].add(BigInteger.ONE) : parts[0];
    }

    boolean integral() {
        return denominator.equals(BigInteger.ONE);
    }

    @Override
    public int compareTo(ExactRational other) {
        if (denominator.equals(other.denominator)) return numerator.compareTo(other.numerator);
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExactRational value && numerator.equals(value.numerator) && denominator.equals(value.denominator);
    }

    @Override
    public int hashCode() {
        return 31 * numerator.hashCode() + denominator.hashCode();
    }

    @Override
    public String toString() {
        return "ExactRational[numerator=" + numerator + ", denominator=" + denominator + "]";
    }

    static final class PrecisionLimit extends RuntimeException {}
}
