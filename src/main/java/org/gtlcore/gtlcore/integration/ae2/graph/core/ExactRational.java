package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;

/** Reduced rationals for bounded analyses; never round a long-sized deficit. */
record ExactRational(BigInteger numerator, BigInteger denominator) implements Comparable<ExactRational> {

    static final ExactRational ZERO = new ExactRational(BigInteger.ZERO, BigInteger.ONE);
    static final ExactRational ONE = new ExactRational(BigInteger.ONE, BigInteger.ONE);

    ExactRational {
        if (denominator.signum() == 0) throw new ArithmeticException("Zero denominator");
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger common = numerator.gcd(denominator);
        numerator = numerator.divide(common);
        denominator = denominator.divide(common);
        if (numerator.bitLength() > 2048 || denominator.bitLength() > 2048) throw new PrecisionLimit();
    }

    static ExactRational of(BigInteger value) {
        return value.signum() == 0 ? ZERO : new ExactRational(value, BigInteger.ONE);
    }

    int signum() {
        return numerator.signum();
    }

    ExactRational negate() {
        return signum() == 0 ? this : new ExactRational(numerator.negate(), denominator);
    }

    ExactRational add(ExactRational other) {
        if (other.signum() == 0) return this;
        if (signum() == 0) return other;
        BigInteger common = denominator.gcd(other.denominator);
        BigInteger left = denominator.divide(common), right = other.denominator.divide(common);
        return new ExactRational(numerator.multiply(right).add(other.numerator.multiply(left)), denominator.multiply(right));
    }

    ExactRational subtract(ExactRational other) {
        return add(other.negate());
    }

    ExactRational multiply(ExactRational other) {
        if (signum() == 0 || other.signum() == 0) return ZERO;
        BigInteger a = numerator.gcd(other.denominator), b = other.numerator.gcd(denominator);
        return new ExactRational(numerator.divide(a).multiply(other.numerator.divide(b)),
                denominator.divide(b).multiply(other.denominator.divide(a)));
    }

    ExactRational divide(ExactRational other) {
        return multiply(new ExactRational(other.denominator, other.numerator));
    }

    BigInteger floor() {
        BigInteger[] parts = numerator.divideAndRemainder(denominator);
        return numerator.signum() < 0 && parts[1].signum() != 0 ? parts[0].subtract(BigInteger.ONE) : parts[0];
    }

    BigInteger ceil() {
        return negate().floor().negate();
    }

    boolean integral() {
        return denominator.equals(BigInteger.ONE);
    }

    @Override
    public int compareTo(ExactRational other) {
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    static final class PrecisionLimit extends RuntimeException {}
}
