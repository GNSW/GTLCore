package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;

/** Material arithmetic. Saturation belongs in display code, never in this ledger. */
public final class CheckedAmounts {

    private CheckedAmounts() {}

    public static long nonNegative(long value) {
        if (value < 0) throw new IllegalArgumentException("Negative material amount: " + value);
        return value;
    }

    public static long add(long left, long right) {
        return Math.addExact(nonNegative(left), nonNegative(right));
    }

    public static long multiply(long left, long right) {
        return Math.multiplyExact(nonNegative(left), nonNegative(right));
    }

    public static long ceilDiv(long value, long divisor) {
        nonNegative(value);
        if (divisor <= 0) throw new IllegalArgumentException("Non-positive divisor");
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }

    public static long amount(BigInteger value) {
        return nonNegative(value.longValueExact());
    }

    public static BigInteger ceilDiv(BigInteger value, BigInteger divisor) {
        if (divisor.signum() <= 0) throw new IllegalArgumentException("Non-positive divisor");
        if (value.signum() <= 0) return BigInteger.ZERO;
        return value.subtract(BigInteger.ONE).divide(divisor).add(BigInteger.ONE);
    }
}
