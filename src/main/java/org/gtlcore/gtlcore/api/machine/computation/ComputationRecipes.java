package org.gtlcore.gtlcore.api.machine.computation;

import com.gregtechceu.gtceu.api.capability.IOpticalComputationProvider;
import com.gregtechceu.gtceu.api.capability.recipe.CWURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Recipe-owned computation: a shared hatch never writes a controller's progress. */
public final class ComputationRecipes {

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Restart> RESTART = new ThreadLocal<>();

    private ComputationRecipes() {}

    /** Named slots and chance rolls remain with GTM's recipe runner; these entries can be aggregated exactly. */
    public static boolean aggregate(List<Content> contents) {
        if (contents == null) return false;
        for (Content content : contents) if (content.slotName != null || content.chance < content.maxChance) return false;
        return true;
    }

    public static List<IOpticalComputationProvider> roots(IRecipeCapabilityHolder holder) {
        List<IOpticalComputationProvider> roots = new ArrayList<>();
        Set<IOpticalComputationProvider> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (IO io : new IO[] { IO.IN, IO.BOTH }) {
            var handlers = holder.getCapabilitiesProxy().get(io, CWURecipeCapability.CAP);
            if (handlers == null) continue;
            for (var handler : handlers) {
                if (!handler.isProxy() && handler instanceof IOpticalComputationProvider provider && seen.add(provider))
                    roots.add(provider);
            }
        }
        return roots;
    }

    public static long required(List<Content> contents) {
        long total = 0;
        try {
            for (Content content : contents) total = Math.addExact(total, ComputationAmounts.read(content.content));
        } catch (IllegalArgumentException | ArithmeticException e) {
            return -1;
        }
        return total;
    }

    public static GTRecipe.ActionResult unavailable() {
        return GTRecipe.ActionResult.fail(() -> Component.translatable("gtceu.recipe_logic.insufficient_in")
                .append(": ").append(CWURecipeCapability.CAP.getName()));
    }

    public static long maximum(IRecipeCapabilityHolder holder, GTRecipe recipe, long required) {
        if (!recipe.data.getBoolean("duration_is_total_cwu")) return required;
        Scope scope = CURRENT.get();
        long progress = scope != null && scope.owner == holder ?
                ((ComputationProgress) scope.logic).gtlcore$completedComputation(recipe) : 0;
        return Math.max(0, ComputationAmounts.total(recipe) - progress);
    }

    public static boolean matches(IRecipeCapabilityHolder holder, GTRecipe recipe, List<Content> contents) {
        long required = required(contents);
        if (required < 0) return false;
        if (required == 0) return true;
        Restart restart = RESTART.get();
        if (restart != null && restart.owner == holder && restart.recipe == recipe && required <= restart.paid)
            return true;
        long maximum = maximum(holder, recipe, required);
        long minimum = Math.min(required, maximum);
        var roots = roots(holder);
        boolean available = minimum == 0 || ComputationNetwork.offer(holder, roots, minimum, maximum) >= minimum;
        if (!available) ComputationNetwork.await(holder, roots, minimum, maximum);
        return available;
    }

    /** A completed cycle already paid this tick; its successor first consumes CWU on the next tick. */
    public static GTRecipe.ActionResult matchRestartTick(IRecipeLogicMachine owner, GTRecipe recipe) {
        var contents = recipe.getTickInputContents(CWURecipeCapability.CAP);
        long required = required(contents);
        if (recipe.data.getBoolean("duration_is_total_cwu") || !aggregate(contents) || required <= 0)
            return recipe.matchTickRecipe(owner);
        long paid = ((ComputationProgress) owner.getRecipeLogic()).gtlcore$usedComputationThisTick();
        if (paid < required) return recipe.matchTickRecipe(owner);

        Restart previous = RESTART.get();
        RESTART.set(new Restart(owner, recipe, paid));
        try {
            // Only the restart's CWU admission reuses the successful tick. Other inputs,
            // outputs and every actual computation debit still use their normal checks.
            return recipe.matchTickRecipe(owner);
        } finally {
            if (previous == null) RESTART.remove();
            else RESTART.set(previous);
        }
    }

    public static ComputationScheduler.Transaction reserve(IRecipeCapabilityHolder holder, GTRecipe recipe,
                                                           List<Content> contents) {
        long required = required(contents);
        long maximum = maximum(holder, recipe, required);
        if (required <= 0 || maximum <= 0) return null;
        var roots = roots(holder);
        long minimum = Math.min(required, maximum);
        var transaction = ComputationNetwork.reserve(holder, roots, minimum, maximum);
        if (transaction == null) ComputationNetwork.await(holder, roots, minimum, maximum);
        return transaction;
    }

    public static void accepted(IRecipeCapabilityHolder owner, GTRecipe recipe, ComputationScheduler.Transaction transaction) {
        Scope scope = CURRENT.get();
        if (scope != null && scope.owner == owner && scope.recipe == recipe) scope.transactions.add(transaction);
        else transaction.commit();
    }

    public static long legacyRequired(GTRecipe recipe, long required) {
        Scope scope = CURRENT.get();
        return scope != null && scope.recipe == recipe && recipe.data.getBoolean("duration_is_total_cwu") ?
                Math.min(required, Math.max(0, maximum(scope.owner, recipe, required) - scope.drawn())) : required;
    }

    /** GTM still owns chance rolls and named-slot selection; the active recipe owns their debits and progress. */
    public static long legacyDraw(IOpticalComputationProvider provider, GTRecipe recipe, long required, boolean simulate) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.recipe != recipe) return ComputationNetwork.request(List.of(provider), required, simulate);
        // GTM processes named slots and chance-selected groups separately. Taking the elastic
        // surplus here would starve later groups sharing this source and roll back the whole tick.
        // Only the aggregate recipe path may claim surplus computation for research.
        long maximum = recipe.data.getBoolean("duration_is_total_cwu") ?
                Math.min(required, Math.max(0, maximum(scope.owner, recipe, required) - scope.drawn())) : required;
        if (maximum <= 0) return 0;
        if (simulate) return Math.min(maximum, ComputationNetwork.available(scope.owner, List.of(provider)));
        var transaction = ComputationNetwork.reserve(scope.owner, List.of(provider), 1, maximum);
        if (transaction == null) return 0;
        scope.transactions.add(transaction);
        return transaction.amount();
    }

    public static Scope begin(IRecipeLogicMachine owner, GTRecipe recipe) {
        Scope scope = new Scope(owner, owner.getRecipeLogic(), recipe, CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    public static boolean active(IRecipeLogicMachine owner, GTRecipe recipe) {
        Scope scope = CURRENT.get();
        return scope != null && scope.owner == owner && scope.recipe == recipe;
    }

    public static void failedCommit() {
        Scope scope = CURRENT.get();
        if (scope != null) scope.failed = true;
    }

    public static boolean commitFailed() {
        Scope scope = CURRENT.get();
        return scope != null && scope.failed;
    }

    public static void undoOnFailure(Runnable refund) {
        Scope scope = CURRENT.get();
        if (scope != null) scope.refunds.add(refund);
    }

    private record Restart(IRecipeLogicMachine owner, GTRecipe recipe, long paid) {}

    public static final class Scope implements AutoCloseable {

        private final IRecipeLogicMachine owner;
        private final RecipeLogic logic;
        private final GTRecipe recipe;
        private final Scope parent;
        private final List<ComputationScheduler.Transaction> transactions = new ArrayList<>();
        private final List<Runnable> refunds = new ArrayList<>();
        private boolean failed;

        private Scope(IRecipeLogicMachine owner, RecipeLogic logic, GTRecipe recipe, Scope parent) {
            this.owner = owner;
            this.logic = logic;
            this.recipe = recipe;
            this.parent = parent;
            if (recipe.data.getBoolean("duration_is_total_cwu"))
                ((ComputationProgress) logic).gtlcore$completedComputation(recipe);
        }

        public void commit(boolean tickAlreadyAdvanced) {
            long drawn = drawn();
            if (recipe.data.getBoolean("duration_is_total_cwu")) {
                ((ComputationProgress) logic).gtlcore$advanceComputation(recipe, drawn);
                if (!tickAlreadyAdvanced) logic.setProgress(logic.getProgress() - 1);
            }
            for (var transaction : transactions) transaction.commit();
            transactions.clear();
            refunds.clear();
            ((ComputationProgress) logic).gtlcore$recordComputation(drawn);
        }

        private long drawn() {
            long drawn = 0;
            for (var transaction : transactions) drawn = ComputationMath.add(drawn, transaction.amount());
            return drawn;
        }

        public boolean failed() {
            return failed;
        }

        @Override
        public void close() {
            try {
                for (int i = refunds.size() - 1; i >= 0; i--) refunds.get(i).run();
                for (int i = transactions.size() - 1; i >= 0; i--) transactions.get(i).close();
            } finally {
                CURRENT.set(parent);
            }
        }
    }
}
