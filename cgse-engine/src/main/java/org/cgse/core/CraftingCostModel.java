// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;

/** CPU admission charge of a finished plan, independent of planning work and physical capacity checks. */
public final class CraftingCostModel {

    public enum Mode {
        LEGACY,
        COMPACT
    }

    private static final BigInteger TASK_BYTES = BigInteger.valueOf(8);

    private CraftingCostModel() {}

    public static <K> BigInteger bytes(GraphPlan<K> plan, Mode mode, ToLongFunction<K> amountPerByte) {
        Objects.requireNonNull(mode);
        Map<String, BigInteger> counts = plan.patternTimesExact();
        Map<Long, BigInteger> material = new LinkedHashMap<>();
        BigInteger metadata;
        if (mode == Mode.COMPACT) {
            // Initial includes held inputs, retained seeds and material awaiting
            // supply (emitted or missing). Charge this partition exactly once.
            plan.initialExact().forEach((key, amount) -> add(material, amountPerByte.applyAsLong(key), amount));
            metadata = TASK_BYTES.multiply(BigInteger.valueOf(counts.size()));
        } else {
            add(material, amountPerByte.applyAsLong(plan.target()), BigInteger.valueOf(plan.amount()));
            BigInteger runs = BigInteger.ZERO;
            for (var count : counts.entrySet()) {
                BigInteger repetitions = count.getValue();
                runs = runs.add(repetitions);
                GraphRecipe<K> recipe = plan.recipes().get(count.getKey());
                recipe.inputs().forEach((key, amount) -> add(material, amountPerByte.applyAsLong(key),
                        BigInteger.valueOf(amount).multiply(repetitions)));
            }
            metadata = runs.add(PlanNodeCost.count(plan, counts.keySet()).multiply(TASK_BYTES));
        }
        // Group equal denominators, then round the combined rational charge
        // once. Never round individual keys or use floating point near long max.
        BigInteger numerator = metadata, denominator = BigInteger.ONE;
        BigInteger scalePerUnit = mode == Mode.COMPACT ? BigInteger.ONE : TASK_BYTES;
        for (var entry : material.entrySet()) {
            BigInteger divisor = BigInteger.valueOf(entry.getKey());
            BigInteger gcd = denominator.gcd(divisor);
            BigInteger scale = divisor.divide(gcd);
            numerator = numerator.multiply(scale).add(entry.getValue().multiply(scalePerUnit).multiply(denominator.divide(gcd)));
            denominator = denominator.multiply(scale);
        }
        return CheckedAmounts.ceilDiv(numerator, denominator);
    }

    private static void add(Map<Long, BigInteger> material, long units, BigInteger amount) {
        if (units <= 0 || amount.signum() < 0) throw new IllegalArgumentException("Invalid storage units");
        if (amount.signum() > 0) material.merge(units, amount, BigInteger::add);
    }
}
