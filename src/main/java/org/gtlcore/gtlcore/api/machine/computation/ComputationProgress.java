package org.gtlcore.gtlcore.api.machine.computation;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;

/** Exact research work; the original int progress remains a bounded projection for GTM's lifecycle. */
public interface ComputationProgress extends ComputationUsage {

    long gtlcore$completedComputation(GTRecipe recipe);

    void gtlcore$advanceComputation(GTRecipe recipe, long amount);

    void gtlcore$resetComputation(GTRecipe recipe);

    void gtlcore$recordComputation(long amount);

    default long gtlcore$usedComputationThisTick() {
        return 0;
    }
}
