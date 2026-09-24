package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Resumable final witness verification, including every prefix with exact totals. */
public final class PlanVerification<K> {

    private final GraphPlan<K> plan;
    private final PlanningBudget budget;
    private final SummaryComputation<K> computation;
    private final PlanCountComputation counts;
    private SequenceSummary<K> summary;
    private Iterator<K> keys;
    private Iterator<Map.Entry<String, BigInteger>> patterns;
    private Iterator<Map.Entry<K, Long>> outputs;
    private BigInteger runs;
    private final Map<K, BigInteger> totals = new LinkedHashMap<>();
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
                all.addAll(plan.initialExact().keySet());
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
                BigInteger required = summary.required(key).max(goal.subtract(summary.delta(key)));
                BigInteger initial = plan.initialExact().getOrDefault(key, BigInteger.ZERO);
                if (initial.compareTo(required) < 0) throw new IllegalArgumentException("Unfunded prefix: " + key);
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
                    totals.merge(output.getKey(), runs.multiply(BigInteger.valueOf(output.getValue())), BigInteger::add);
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
