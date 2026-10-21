// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resumable final witness verification, including every prefix with exact totals. */
public final class PlanVerification<K> implements AutoCloseable {

    private final GraphPlan<K> plan;
    private final PlanningBudget budget;
    private final SummaryComputation<K> computation;
    private PlanCountComputation counts;
    private long refillWorkFloor = -1;
    private SequenceSummary<K> summary;
    private Iterator<K> keys;
    private Iterator<Map.Entry<String, BigInteger>> patterns;
    private Iterator<Map.Entry<K, Long>> outputs;
    private BigInteger runs;
    private final Map<K, BigInteger> totals;
    private int phase;
    private boolean checkingSeeds;

    public PlanVerification(GraphPlan<K> plan, PlanningBudget budget) {
        this(plan, budget, null);
    }

    /** Supplemental refill witnesses need exact prefix, delivery and final-seed
     * checks, but their callers do not consume gross production/count metadata.
     * Their existing force-credit guards remain separate. This is not a general
     * forced-crafting proof; ordinary/public verification keeps its metadata. */
    static <K> PlanVerification<K> refill(GraphPlan<K> funded, PlanningBudget budget) {
        return new PlanVerification<>(funded, budget, null, false);
    }

    /** The pruning pass retains the exact unit fold under these same bindings
     * until this verifier closes. Only arithmetic is shared; all funded prefix,
     * target and final-seed checks still run against the shortened word. */
    static <K> PlanVerification<K> refillFolded(GraphPlan<K> funded, PlanningBudget budget,
                                               PlanStep unit, SequenceSummary<K> summary) {
        var known = new IdentityHashMap<PlanStep, SequenceSummary<K>>(1);
        known.put(unit, summary);
        return new PlanVerification<>(funded, budget, null, false, known);
    }

    /** Expansion uses elapsed work to size later quality passes. Retain the old
     * metadata pass unless the mandatory fold/key checks already reach that
     * scheduling floor. Never remove the prefix/goal/seed checks themselves. */
    static <K> PlanVerification<K> refillAfterWork(GraphPlan<K> funded, PlanningBudget budget, long floor) {
        var verification = new PlanVerification<K>(funded, budget);
        verification.refillWorkFloor = floor;
        return verification;
    }

    /** Internal assembly owns this exact immutable word and its bindings. The
     * completed fold replaces arithmetic only; goals/seeds and production
     * bookkeeping still use the new plan. Public verification never trusts a
     * caller-supplied fold. Identity lookup cannot alias a different word. */
    static <K> PlanVerification<K> folded(GraphPlan<K> plan, PlanningBudget budget,
                                          PlanStep word, SequenceSummary<K> summary) {
        var known = new IdentityHashMap<PlanStep, SequenceSummary<K>>();
        known.put(word, summary);
        return new PlanVerification<>(plan, budget, null, true, known);
    }

    /** The refill owner preserves the original suffix and all its bindings. */
    static <K> PlanVerification<K> refillAfterWork(GraphPlan<K> funded, PlanningBudget budget, long floor,
                                                   PlanStep suffix, SequenceSummary<K> summary) {
        var verification = folded(funded, budget, suffix, summary);
        verification.refillWorkFloor = floor;
        return verification;
    }

    /** Only the exact immutable plan produced by this assembly can reuse its
     * completed fold. Other plans, including copies with changed inventory or
     * recipes, take the ordinary verification path. No cross-request cache.
     * Ordinary state orders only need prefix feasibility. Forced orders whose
     * verified net target gain covers the order also need no gross totals;
     * the remaining forced orders retain the full production bookkeeping. */
    PlanVerification(GraphPlan<K> plan, PlanningBudget budget, AllocationSearch.Candidate<K> assembled) {
        this(plan, budget, assembled, true);
    }

    private PlanVerification(GraphPlan<K> plan, PlanningBudget budget, AllocationSearch.Candidate<K> assembled,
                             boolean collectProduction) {
        this(plan, budget, assembled, collectProduction, Map.of());
    }

    private PlanVerification(GraphPlan<K> plan, PlanningBudget budget, AllocationSearch.Candidate<K> assembled,
                             boolean collectProduction, Map<PlanStep, SequenceSummary<K>> completed) {
        try (var costs = budget.trace(PlanningCostTrace.Stage.VERIFY)) {
            if (!plan.feasible() || !plan.missingExact().isEmpty()) throw new IllegalArgumentException("Unverified plan");
            this.plan = plan;
            this.budget = budget;
            boolean sameAssembly = assembled != null && assembled.proposed == plan && assembled.summary != null &&
                    assembled.witness == plan.steps();
            var known = sameAssembly ? Map.of(plan.steps(), assembled.summary) : completed;
            computation = new SummaryComputation<>(plan.steps(), plan.recipes(), budget, known);
            boolean needProduction = collectProduction && (!sameAssembly || assembled.force &&
                    assembled.summary.delta(plan.target()).compareTo(BigInteger.valueOf(plan.amount())) < 0);
            counts = needProduction ? new PlanCountComputation(plan.steps()) : null;
            totals = needProduction ? new LinkedHashMap<>() : null;
        }
    }

    public boolean step() {
        try (var costs = budget.trace(PlanningCostTrace.Stage.VERIFY)) {
            return stepMeasured();
        }
    }

    private boolean stepMeasured() {
        // The batched key loop charges each visit itself. Yield boundaries do
        // not add another arithmetic unit on top of those material checks.
        if (phase != 1) budget.check();
        budget.phase(PlanningBudget.Phase.VERIFY);
        switch (phase) {
            case 0 -> {
                if (!computation.step()) return false;
                summary = computation.result();
                keys = summary.delta().keySet().iterator();
                phase = 1;
            }
            case 1 -> verifyKeys();
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
                    outputs = plan.recipes().get(pattern.getKey()).executionOutputs().entrySet().iterator();
                } else phase = 4;
            }
            default -> {
                return true;
            }
        }
        return phase == 4;
    }

    private void verifyKeys() {
        // Visit delta, target and seeds in their original order without copying
        // a union. Every prefix requirement from the trusted fold has a delta
        // entry (including zero changes for returned tools). Remaining initial
        // keys have zero need/change/goal and are already nonnegative in GraphPlan,
        // so checking unused inventory again cannot reject another plan.
        for (int operation = 0; operation < 32; operation++) {
            budget.check();
            K key;
            if (keys.hasNext()) {
                key = keys.next();
                if (checkingSeeds && (summary.delta().containsKey(key) || key.equals(plan.target()))) continue;
            } else if (!checkingSeeds) {
                checkingSeeds = true; keys = plan.seeds().keySet().iterator(); key = plan.target();
                if (summary.delta().containsKey(key)) continue;
            } else {
                keys = null;
                if (refillWorkFloor >= 0 && budget.nodes() >= refillWorkFloor) counts = null;
                phase = counts == null ? 4 : 2; return;
            }
            BigInteger goal = BigInteger.valueOf(plan.seeds().getOrDefault(key, 0L));
            if (key.equals(plan.target())) goal = goal.add(BigInteger.valueOf(plan.amount()));
            BigInteger required = summary.required(key).max(goal.subtract(summary.delta(key)));
            BigInteger initial = plan.initialExact().getOrDefault(key, BigInteger.ZERO);
            if (initial.compareTo(required) < 0) throw new IllegalArgumentException("Unfunded prefix: " + key);
        }
    }

    SequenceSummary<K> summary() {
        if (phase != 4) throw new IllegalStateException("Verification incomplete");
        return summary;
    }

    BigInteger physicalProduced(K key) {
        if (phase != 4) throw new IllegalStateException("Verification incomplete");
        if (counts == null) throw new IllegalStateException("Production totals were not requested");
        return totals.getOrDefault(key, BigInteger.ZERO);
    }

    Map<String, BigInteger> patternCounts() {
        if (phase != 4) throw new IllegalStateException("Verification incomplete");
        if (counts == null) throw new IllegalStateException("Production totals were not requested");
        return counts.result();
    }

    @Override
    public void close() {
        keys = null;
        computation.close();
    }
}
