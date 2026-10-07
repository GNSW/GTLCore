package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Exact confirmation totals for the same immutable plan shown by the graph browser. */
public final class PlanSummary {

    private PlanSummary() {}

    public record Entry<K>(K key, BigInteger missing, BigInteger stored, BigInteger crafted, BigInteger runs) {

        public Entry {
            ExactAmounts.of(missing);
            ExactAmounts.of(stored);
            ExactAmounts.of(crafted);
            ExactAmounts.of(runs);
        }
    }

    /** Amounts are never clipped here; a host's bounded UI performs its own final conversion. */
    public static <K> List<Entry<K>> entries(GraphPlan<K> plan, Map<K, BigInteger> emitted) {
        Map<K, BigInteger> crafted = new LinkedHashMap<>(emitted);
        Map<K, BigInteger> runs = new LinkedHashMap<>();
        plan.patternTimesExact().forEach((id, count) -> plan.recipes().get(id).outputs().forEach((key, amount) -> {
            crafted.merge(key, count.multiply(BigInteger.valueOf(amount)), BigInteger::add);
            runs.merge(key, count, BigInteger::add);
        }));
        var keys = new LinkedHashSet<>(plan.initialExact().keySet());
        keys.addAll(crafted.keySet());
        List<Entry<K>> entries = new ArrayList<>(keys.size());
        for (K key : keys) {
            BigInteger missing = plan.missingExact().getOrDefault(key, BigInteger.ZERO);
            BigInteger stored = plan.initialExact().getOrDefault(key, BigInteger.ZERO)
                    .subtract(missing).subtract(emitted.getOrDefault(key, BigInteger.ZERO));
            entries.add(new Entry<>(key, missing, stored, crafted.getOrDefault(key, BigInteger.ZERO),
                    runs.getOrDefault(key, BigInteger.ZERO)));
        }
        return List.copyOf(entries);
    }
}
