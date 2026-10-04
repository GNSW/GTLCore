package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.math.BigInteger;

/** One branch's optional fixed-multiset continuation; never a negative result cache. */
final class CountScheduleContinuations<K> implements AutoCloseable {

    private final RecipeCountModel<K> model;
    private final PlanningBudget budget;
    private CountSchedule<K> paused;

    CountScheduleContinuations(RecipeCountModel<K> model, PlanningBudget budget) {
        this.model = model;
        this.budget = budget;
    }

    CountSchedule<K> acquire(BigInteger[] counts) {
        if (paused != null && paused.sameCounts(counts)) {
            CountSchedule<K> result = paused;
            paused = null;
            budget.note("count_schedule_reuse", "resumed; exact_original_counts; same_branch_scope");
            return result;
        }
        // Optional retained work must give way before admitting another
        // scheduler when the shared request is already using most of its memory.
        trim();
        return new CountSchedule<>(model, counts, budget);
    }

    boolean retain(CountSchedule<K> schedule) {
        budget.checkpoint();
        if (!schedule.resumableIn(model, budget) || budget.availableBytes() < budget.reservedBytes()) return false;
        // A supplied macro program bypasses this pool. Batch grouping is also
        // semantic: do not identify batch-sensitive candidates by counts alone.
        for (GraphRecipe<K> recipe : model.recipes) {
            budget.check();
            if (recipe.batchSensitiveInputs()) return false;
        }
        // Keep at most one live continuation, with all its original memory
        // reservations still owned. No completed UNKNOWN/DEAD/WITNESS is stored.
        if (paused != schedule) close();
        paused = schedule;
        budget.note("count_schedule_reuse", "paused; next_duplicate_can_continue");
        return true;
    }

    void trim() {
        if (budget.availableBytes() < budget.reservedBytes()) close();
    }

    @Override
    public void close() {
        if (paused != null) {
            paused.close();
            paused = null;
        }
    }
}
