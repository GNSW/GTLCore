package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Resumable final witness verification, including every prefix and long boundary. */
public final class PlanVerification<K> {

    private final GraphPlan<K> plan;
    private final PlanningBudget budget;
    private final SummaryComputation<K> computation;
    private final PlanCountComputation counts;
    private SequenceSummary<K> summary;
    private Iterator<K> keys;
    private Iterator<Map.Entry<String, Long>> patterns;
    private Iterator<Map.Entry<K, Long>> outputs;
    private long runs;
    private final Map<K, Long> totals = new LinkedHashMap<>();
    private int phase;

    public PlanVerification(GraphPlan<K> plan, PlanningBudget budget) {
        if (!plan.feasible() || !plan.missing().isEmpty()) throw new IllegalArgumentException("Unverified plan");
        this.plan = plan;
        this.budget = budget;
        computation = new SummaryComputation<>(plan.steps(), plan.recipes(), budget);
        counts = new PlanCountComputation(plan.steps());
    }

    public boolean step() {
        budget.check();
        budget.phase(PlanningBudget.Phase.VERIFY);
        switch (phase) {
            case 0 -> {
                if (!computation.step()) return false;
                summary = computation.result();
                var all = new LinkedHashSet<>(summary.delta().keySet());
                all.add(plan.target());
                all.addAll(plan.seeds().keySet());
                all.addAll(plan.initial().keySet());
                keys = all.iterator();
                phase = 1;
            }
            case 1 -> {
                if (!keys.hasNext()) {
                    phase = 2;
                    return false;
                }
                K key = keys.next();
                BigInteger goal = BigInteger.valueOf(plan.seeds().getOrDefault(key, 0L));
                if (key.equals(plan.target())) goal = goal.add(BigInteger.valueOf(plan.amount()));
                long required = CheckedAmounts.amount(summary.required(key).max(goal.subtract(summary.delta(key))));
                BigInteger initial = BigInteger.valueOf(plan.initial().getOrDefault(key, 0L));
                if (initial.compareTo(BigInteger.valueOf(required)) < 0) throw new IllegalArgumentException("Unfunded prefix: " + key);
                CheckedAmounts.amount(initial.add(summary.delta(key)));
                CheckedAmounts.amount(initial.add(summary.peak(key)));
            }
            case 2 -> {
                if (counts.step(budget)) {
                    patterns = counts.result().entrySet().iterator();
                    phase = 3;
                }
            }
            case 3 -> {
                if (outputs != null && outputs.hasNext()) {
                    var output = outputs.next();
                    totals.merge(output.getKey(), CheckedAmounts.multiply(output.getValue(), runs), CheckedAmounts::add);
                } else if (patterns.hasNext()) {
                    var pattern = patterns.next();
                    runs = pattern.getValue();
                    outputs = plan.recipes().get(pattern.getKey()).outputs().entrySet().iterator();
                } else phase = 4;
            }
            default -> {
                return true;
            }
        }
        return phase == 4;
    }
}
