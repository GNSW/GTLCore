package org.cgse.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stock-guided source assignments; only the ordinary planner can validate them. */
final class GraphSourceScout {

    private GraphSourceScout() {}

    static <K> List<Map<K, Integer>> choices(GraphCompiler<K> compiler, K target,
                                             Map<K, Long> stock, Set<K> external, Set<K> seeds,
                                             boolean force, PlanningBudget budget) {
        long started = budget.threadWork(), allowance = Math.min(131_072, budget.remainingWork() / 16);
        if (allowance < 24_576) return List.of();
        long memory = 0;
        try (var ranking = GraphSourceRanking.create(compiler, stock, external, target, force, budget, allowance)) {
            if (ranking == null) return List.of();
            List<Map<K, Integer>> proposals = new ArrayList<>();
            for (boolean byCost : new boolean[] { false, true }) {
                var pending = new ArrayDeque<K>();
                Set<K> visited = new HashSet<>();
                Map<K, Integer> choices = new LinkedHashMap<>();
                pending.add(target);
                pending.addAll(seeds);
                while (!pending.isEmpty()) {
                    if (budget.threadWork() - started >= allowance) return proposals;
                    budget.check();
                    K key = pending.removeFirst();
                    if (!visited.add(key) || external.contains(key)) continue;
                    if (!budget.tryReserve(192)) return proposals;
                    memory += 192;
                    var ranked = ranking.sources(key, byCost);
                    if (ranked.isEmpty()) continue;
                    var preferred = ranked.get(0);
                    var original = compiler.producers(key);
                    int index = 0;
                    while (index < original.size() && original.get(index) != preferred) {
                        budget.check();
                        index++;
                    }
                    if (index == original.size()) throw new IllegalStateException("Foreign source proposal");
                    if (index != 0) choices.put(key, index);
                    for (K input : preferred.inputs().keySet()) {
                        budget.check();
                        if (!visited.contains(input)) pending.addLast(input);
                    }
                }
                if (!choices.isEmpty() && !proposals.contains(choices)) proposals.add(Map.copyOf(choices));
            }
            return proposals;
        } finally {
            budget.release(memory);
        }
    }
}
