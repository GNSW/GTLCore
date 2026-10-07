package org.cgse.core;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/** Solve only the suffix boundary, then restore a program over original recipe IDs. */
final class CountShellSearch<K> implements AutoCloseable {

    private final RecipeCountModel<K> original;
    private final CountShellCompilation<K> compilation;
    private final PlanningBudget budget;
    private RecipeCountModel<K> residual;
    private CountQuickSolve search;
    private CountSchedule<K> scheduling;
    private BigInteger[] counts;
    private PlanStep witness;
    private boolean complete;

    CountShellSearch(RecipeCountModel<K> original, CountShellCompilation<K> compilation) {
        this.original = original;
        this.compilation = compilation;
        budget = original.budget;
        try {
            residual = compilation.residual(original);
            if (residual == null) {
                complete = true;
                return;
            }
            budget.note("count_shell_kernel", "variables=" + original.recipes.size() + "->" + residual.recipes.size() +
                    "; rows=" + original.constraints.size() + "->" + residual.constraints.size());
            BigInteger[] lower = new BigInteger[residual.recipes.size()];
            Arrays.fill(lower, BigInteger.ZERO);
            search = new CountQuickSolve(residual.constraints, lower, new BigInteger[lower.length], budget);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    boolean step() {
        if (complete) return true;
        try {
            return advance();
        } catch (ExactRational.PrecisionLimit limit) {
            // This is only a candidate view. A local arithmetic limit must
            // leave the original search available, just like a missing witness.
            counts = null;
            witness = null;
            return complete = true;
        }
    }

    private boolean advance() {
        budget.check();
        if (search != null) {
            if (!search.step()) return false;
            BigInteger[] candidate = search.counts();
            counts = compilation.restoreAndCheck(original, candidate);
            search.close();
            search = null;
            if (counts == null) return complete = true;
            if (candidate.length == 0) {
                witness = compilation.lift(original, new PlanStep.Sequence(List.of()));
                return complete = true;
            }
            scheduling = new CountSchedule<>(residual, candidate, budget);
            return false;
        }
        if (!scheduling.step()) return false;
        if (scheduling.result() == CountSchedule.Result.WITNESS)
            witness = compilation.lift(original, scheduling.witness());
        scheduling.close();
        scheduling = null;
        return complete = true;
    }

    BigInteger[] counts() {
        return counts;
    }

    PlanStep witness() {
        return witness;
    }

    @Override
    public void close() {
        if (search != null) search.close();
        search = null;
        if (scheduling != null) scheduling.close();
        scheduling = null;
        if (residual != null) residual.close();
        residual = null;
    }
}
