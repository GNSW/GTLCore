package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact totals. A bounded view is only for a long API or one transfer, never a material balance. */
public final class ExactAmounts {

    public static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private ExactAmounts() {}

    public static BigInteger of(Number value) {
        BigInteger result;
        if (value instanceof BigInteger integer) result = integer;
        else if (value instanceof Long || value instanceof Integer) result = BigInteger.valueOf(value.longValue());
        else throw new IllegalArgumentException("Expected an exact integer");
        if (result.signum() < 0) throw new IllegalArgumentException("Negative material amount");
        return result;
    }

    public static <K> Map<K, BigInteger> copy(Map<K, ? extends Number> amounts) {
        Map<K, BigInteger> result = new LinkedHashMap<>();
        amounts.forEach((key, value) -> {
            BigInteger count = of(value);
            if (count.signum() > 0) result.put(key, count);
        });
        return Collections.unmodifiableMap(result);
    }

    public static long capped(BigInteger amount) {
        return of(amount).min(LONG_MAX).longValueExact();
    }

    public static <K> Map<K, Long> longView(Map<K, BigInteger> amounts) {
        Map<K, Long> result = new LinkedHashMap<>();
        amounts.forEach((key, count) -> {
            if (count.signum() > 0) result.put(key, capped(count));
        });
        return Collections.unmodifiableMap(result);
    }
}
