package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;

/** A compact, exact fixed-count program. Its prefix requirements still need funding. */
final class CountProgram<K> implements AutoCloseable {

    private final List<GraphRecipe<K>> recipes;
    private final BigInteger[] counts;
    private final PlanningBudget budget;
    private final Map<String, GraphRecipe<K>> byId = new LinkedHashMap<>();
    private List<PlanStep> children;
    private PlanStep program;
    private SummaryComputation<K> computation;
    private int bit = -1, cursor;
    private long memory;
    private final boolean available;

    CountProgram(List<GraphRecipe<K>> recipes, BigInteger[] counts, PlanningBudget budget) {
        this.recipes = recipes;
        this.counts = counts.clone();
        this.budget = budget;
        for (int i = 0; i < counts.length; i++) {
            budget.check();
            ExactAmounts.of(counts[i]);
            bit = Math.max(bit, counts[i].bitLength() - 1);
            byId.put(recipes.get(i).id(), recipes.get(i));
        }
        var keys = new HashSet<K>();
        for (GraphRecipe<K> recipe : recipes) {
            budget.check();
            keys.addAll(recipe.inputs().keySet());
            keys.addAll(recipe.outputs().keySet());
        }
        long levels = Math.max(1, bit + 1);
        long bytes = 2048 + levels * (128L * recipes.size() + 3L * keys.size() * (96 + levels / 8));
        available = budget.tryReserve(bytes);
        if (available) memory = bytes;
    }

    boolean step() {
        budget.check();
        if (!available) return true;
        if (bit >= 0) {
            if (children == null) {
                children = new ArrayList<>();
                // Doubling the preceding program and appending the next bits
                // reconstructs every count exactly. No count is rounded or
                // expanded into individual executions, even above long range.
                if (program != null) children.add(new PlanStep.Repeat(program, 2));
                cursor = 0;
            }
            if (cursor < counts.length) {
                if (counts[cursor].testBit(bit)) children.add(new PlanStep.Batch(recipes.get(cursor).id(), 1));
                cursor++;
                return false;
            }
            program = children.size() == 1 ? children.get(0) : new PlanStep.Sequence(children);
            children = null;
            bit--;
            return false;
        }
        if (computation == null) {
            if (program == null) program = new PlanStep.Sequence(List.of());
            computation = new SummaryComputation<>(program, byId, budget);
        }
        return computation.step();
    }

    PlanStep program() { return program; }
    SequenceSummary<K> summary() { return computation.result(); }
    boolean available() { return available; }

    @Override
    public void close() {
        budget.release(memory);
        memory = 0;
    }
}
