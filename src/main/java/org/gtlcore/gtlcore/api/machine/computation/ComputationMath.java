package org.gtlcore.gtlcore.api.machine.computation;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Non-negative arithmetic at the legacy int capability boundary. */
public final class ComputationMath {

    private ComputationMath() {}

    public static long add(long a, long b) {
        if (a < 0 || b < 0) throw new IllegalArgumentException("Negative computation");
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    public static int toInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    public static long multiplyDivide(long a, long b, long divisor) {
        if (a < 0 || b < 0 || divisor <= 0) throw new IllegalArgumentException("Invalid computation cost");
        if (a == 0 || b == 0) return 0;
        if (a <= Long.MAX_VALUE / b) return a * b / divisor;
        return new BigInteger(Long.toString(a)).multiply(BigInteger.valueOf(b))
                .divide(BigInteger.valueOf(divisor)).min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
    }

    /** Scale research work when an overclock result replaces its legacy duration. */
    public static long overclockTotal(long total, int durationBefore, int durationAfter,
                                      double durationFactor, int overclocks) {
        if (total <= 0 || durationBefore <= 0 || durationAfter <= 0)
            throw new IllegalArgumentException("Research work and durations must be positive");

        // A long research total uses an int duration proxy. Its rounded OC result is
        // not an exact scale for the real work; use the OC factor when it describes
        // the result. Custom modifiers without matching OC metadata use the ratio.
        if (total > Integer.MAX_VALUE && durationFactor > 0 && durationFactor < 1 && overclocks > 0) {
            BigDecimal multiplier = BigDecimal.valueOf(durationFactor).pow(overclocks);
            int expectedDuration = Math.max(1, multiplier.multiply(BigDecimal.valueOf(durationBefore)).intValue());
            if (expectedDuration == durationAfter) {
                return Math.max(1, multiplier.multiply(BigDecimal.valueOf(total)).toBigInteger().longValueExact());
            }
        }

        return Math.max(1, BigInteger.valueOf(total).multiply(BigInteger.valueOf(durationAfter))
                .divide(BigInteger.valueOf(durationBefore)).longValueExact());
    }
}
