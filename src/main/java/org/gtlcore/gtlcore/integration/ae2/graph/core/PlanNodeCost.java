package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.*;

/** Request-template node fees, preserving MAX_FAST's sharing and terminal occurrences. */
public final class PlanNodeCost {

    private PlanNodeCost() {}

    public static <K> BigInteger count(GraphPlan<K> plan) {
        return count(plan, plan.patternTimes().keySet());
    }

    public static <K> BigInteger count(GraphPlan<K> plan, Set<String> selectedRecipes) {
        Map<K, Set<Request<K>>> dependencies = new LinkedHashMap<>();
        for (String id : selectedRecipes) {
            var recipe = plan.recipes().get(id);
            for (K output : recipe.executionOutputs().keySet()) {
                var children = dependencies.computeIfAbsent(output, ignored -> new LinkedHashSet<>());
                for (int index = 0; index < recipe.slots().size(); index++) {
                    var slot = recipe.slots().get(index);
                    children.add(new Request<>(recipe.binding(), slot.inputSlot() < 0 ? index : slot.inputSlot(),
                            slot.key(), slot.amount(), slot.configuration()));
                }
            }
        }
        Set<Request<K>> requests = new HashSet<>();
        Set<K> reached = new HashSet<>();
        Deque<K> pending = new ArrayDeque<>();
        Set<K> roots = new LinkedHashSet<>();
        roots.add(plan.target());
        roots.addAll(plan.seeds().keySet());
        long total = 0;
        for (K root : roots) {
            if (!reached.add(root)) continue;
            total = Math.addExact(total, 1);
            pending.add(root);
            while (!pending.isEmpty()) {
                for (Request<K> child : dependencies.getOrDefault(pending.removeFirst(), Set.of())) {
                    reached.add(child.key());
                    if (!dependencies.containsKey(child.key())) {
                        // MAX_FAST does not merge terminal boundaries: each expanded
                        // parent request pays its leaf fee, even for the same material.
                        total = Math.addExact(total, 1);
                    } else if (requests.add(child)) {
                        // Share an input template across all incoming paths. Reusing
                        // its entire subtree count would charge an exponentially
                        // expanded tree for a small shared DAG.
                        total = Math.addExact(total, 1);
                        pending.addLast(child.key());
                    }
                }
            }
        }
        return BigInteger.valueOf(total);
    }

    private record Request<K>(String binding, int slot, K key, long amount, boolean configuration) {}
}
