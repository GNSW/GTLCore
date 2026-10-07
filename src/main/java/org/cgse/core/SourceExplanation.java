package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Explains a failed source support, then jumps only over decisions absent from its proof. */
final class SourceExplanation<K> implements AutoCloseable {

    private final OrderProofs<K> proofs;
    private final GraphCompiler<K> compiler;
    private final K target;
    private final Set<K> additional;
    private final Set<String> excluded;
    private final PlanningBudget budget;
    private final long allowance;
    private long work, stepStarted;
    private final Map<K, Integer> core = new LinkedHashMap<>();
    private final List<ExactLinearProgram.Constraint> assumptions = new ArrayList<>();
    private CountBounds checking;
    private CountConflict conflict;
    private Iterator<K> minimizing;
    private List<ExactLinearProgram.Constraint> minimized;
    private int removeIndex;
    private boolean replaying, complete;
    private Map<K, Integer> result;

    SourceExplanation(OrderProofs<K> proofs, GraphCompiler<K> compiler, K target, Set<K> additional, Set<String> excluded,
                      GraphCompiler.Compiled<K> graph, Map<K, Integer> choices, PlanningBudget budget) {
        this.proofs = proofs;
        this.compiler = compiler;
        this.target = target;
        this.additional = additional;
        this.excluded = excluded;
        this.budget = budget;
        stepStarted = budget.threadWork();
        allowance = Math.min(32768, budget.remainingWork() / 16);
        for (K key : graph.selected().keySet()) {
            if (compiler.producers(key).stream().filter(r -> !excluded.contains(r.id())).limit(2).count() > 1)
                core.put(key, choices.getOrDefault(key, 0));
        }
        conflict = proofs.rejectedSupport(graph.recipes().keySet());
        if (conflict != null) minimizing = new ArrayList<>(core.keySet()).iterator();
        else {
            var model = proofs.model;
            for (int i = 0; i < model.recipes.size(); i++) if (!graph.recipes().containsKey(model.recipes.get(i).id()))
                assumptions.add(new ExactLinearProgram.Constraint(Map.of(i, BigInteger.ONE), BigInteger.ZERO));
            check(assumptions);
        }
        work = budget.threadWork() - stepStarted;
    }

    private void check(List<ExactLinearProgram.Constraint> assumptions) {
        var rows = new ArrayList<>(proofs.model.constraints);
        int global = rows.size();
        rows.addAll(assumptions);
        checking = new CountBounds(proofs.model.recipes.size(), rows, budget, global);
    }

    boolean step() {
        stepStarted = budget.threadWork();
        try {
            return advance();
        } finally {
            work += budget.threadWork() - stepStarted;
        }
    }

    private long usedWork() {
        return work + budget.threadWork() - stepStarted;
    }

    private boolean advance() {
        if (complete) return true;
        budget.check();
        if (usedWork() >= allowance) return finish(null);
        if (checking != null) {
            if (!checking.step()) return false;
            boolean blocked = checking.blocked();
            BitSet reasons = checking.conflictingAssumptions();
            checking.close();
            checking = null;
            if (minimized != null) {
                if (blocked) minimized.remove(removeIndex);
                else removeIndex++;
                minimizeCounts();
                return false;
            }
            if (!blocked || reasons == null) return finish(null);
            if (!replaying) {
                var used = new ArrayList<ExactLinearProgram.Constraint>();
                for (int i = reasons.nextSetBit(0); i >= 0; i = reasons.nextSetBit(i + 1)) used.add(assumptions.get(i));
                conflict = new CountConflict(used);
                // Independently replay the minimized core with the original
                // all-source balance rows, not the failed branch's bound cache.
                replaying = true;
                check(conflict.assumptions());
            } else {
                minimized = new ArrayList<>(conflict.assumptions());
                minimizeCounts();
            }
            return false;
        }
        if (minimizing.hasNext()) {
            K key = minimizing.next();
            Integer value = core.remove(key);
            if (!rejectsRelaxed()) core.put(key, value);
            return false;
        }
        // The certificate remains valid when every unmentioned source choice
        // is free. This is why unrelated decisions can safely be skipped.
        return rejectsRelaxed() ? finish(core) : finish(null);
    }

    private void minimizeCounts() {
        if (removeIndex == minimized.size()) {
            conflict = new CountConflict(minimized);
            minimized = null;
            proofs.publish(proofs.model, List.of(conflict));
            minimizing = new ArrayList<>(core.keySet()).iterator();
        } else {
            var trial = new ArrayList<>(minimized);
            trial.remove(removeIndex);
            check(trial);
        }
    }

    private boolean rejectsRelaxed() {
        Set<K> seen = new HashSet<>();
        Set<String> allowed = new HashSet<>();
        Deque<K> pending = new ArrayDeque<>();
        pending.add(target);
        pending.addAll(additional);
        while (!pending.isEmpty()) {
            budget.check();
            if (usedWork() >= allowance) return false;
            K key = pending.removeFirst();
            if (!seen.add(key)) continue;
            int index = 0;
            for (var recipe : compiler.producers(key)) {
                if (excluded.contains(recipe.id())) continue;
                int position = index++;
                if (core.containsKey(key) && position != core.get(key)) continue;
                if (allowed.add(recipe.id())) pending.addAll(recipe.inputs().keySet());
            }
        }
        BigInteger[] lower = new BigInteger[proofs.model.recipes.size()], upper = new BigInteger[lower.length];
        Arrays.fill(lower, BigInteger.ZERO);
        for (int i = 0; i < upper.length; i++) if (!allowed.contains(proofs.model.recipes.get(i).id())) upper[i] = BigInteger.ZERO;
        return conflict.impliedBy(lower, upper, budget);
    }

    private boolean finish(Map<K, Integer> value) {
        // Repair scheduling follows this decision order. Map.copyOf would
        // randomize it across JVM starts, changing which candidates get a turn.
        result = value == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(value));
        if (result != null) {
            proofs.learnSource(result);
            budget.note("source_backjump", "relevant_decisions=" + result.size() + "; count_premises=" + conflict.assumptions().size());
        }
        complete = true;
        close();
        return true;
    }

    Map<K, Integer> result() {
        return result;
    }

    @Override
    public void close() {
        if (checking != null) checking.close();
        checking = null;
    }
}
