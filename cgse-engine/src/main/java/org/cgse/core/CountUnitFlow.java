// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;

/** Recognizes unit-token conversion networks whose cycles cannot discharge forced production. */
final class CountUnitFlow {
    private CountUnitFlow() {}

    static <K> boolean requiresNetProduction(RecipeCountModel<K> model, K target) {
        var budget = model.budget;
        long bytes = 1024L + 256L * model.keys.size();
        if (!budget.tryReserve(bytes)) return false;
        try {
            Map<K, Long> units = new HashMap<>(), catalysts = new HashMap<>();
            for (var recipe : model.recipes) {
                budget.check();
                if (recipe.batchSensitiveInputs()) return false;
                K source = null, destination = null;
                for (var input : recipe.inputs().entrySet()) {
                    budget.check();
                    long returned = recipe.outputs().getOrDefault(input.getKey(), 0L);
                    if (returned == input.getValue()) {
                        catalysts.merge(input.getKey(), input.getValue(), Math::max);
                    } else if (returned != 0 || source != null) return false;
                    else {
                        source = input.getKey();
                        if (!unit(units, source, input.getValue())) return false;
                    }
                }
                for (var output : recipe.outputs().entrySet()) {
                    budget.check();
                    if (recipe.inputs().containsKey(output.getKey())) continue;
                    if (destination != null) return false;
                    destination = output.getKey();
                    if (!unit(units, destination, output.getValue())) return false;
                }
                // Every productive edge moves exactly one token. A free
                // producer, sink, joint input or coproduct needs general solving.
                if ((source == null) != (destination == null)) return false;
            }
            if (!Long.valueOf(1).equals(units.get(target))) return false;
            for (K key : units.keySet()) {
                budget.check();
                // A mandatory second product can make exporting existing
                // target stock necessary. This certificate has one flow goal.
                if (!key.equals(target) && model.goal(key).signum() > 0) return false;
            }
            for (var catalyst : catalysts.entrySet()) {
                budget.check();
                // Read arcs must be immutable, already funded tools. A token
                // moved by another edge could enable a necessary startup cycle.
                if (units.containsKey(catalyst.getKey()) || !model.external.contains(catalyst.getKey()) &&
                        model.stock.getOrDefault(catalyst.getKey(), 0L) < catalyst.getValue()) return false;
            }
            budget.note("count_unit_flow", "unit_conversion_certificate; materials=" + units.size() +
                    "; recipes=" + model.recipes.size() + "; target_quantum=1; all_model_sources");
            return true;
        } finally {
            budget.release(bytes);
        }
    }

    private static <K> boolean unit(Map<K, Long> units, K key, long amount) {
        Long old = units.putIfAbsent(key, amount);
        return old == null || old == amount;
    }
}
