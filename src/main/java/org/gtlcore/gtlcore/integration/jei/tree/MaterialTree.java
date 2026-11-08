package org.gtlcore.gtlcore.integration.jei.tree;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The recipe tree itself. Mirrors EMI's {@code MaterialTree}.
 */
final class MaterialTree {

    final MaterialNode goal;
    final TreeCost cost = new TreeCost();
    final Map<String, Optional<TreeRecipe>> resolutions = new HashMap<>();
    long batches = 1;

    private final Function<TreeIngredient, TreeRecipe> resolver;

    MaterialTree(TreeRecipe recipe, Function<TreeIngredient, TreeRecipe> resolver) {
        this.resolver = resolver;
        if (recipe.outputs().isEmpty()) {
            throw new IllegalArgumentException("Recipe has no output ingredient");
        }
        this.goal = new MaterialNode(recipe.outputs().get(0));
        goal.defineRecipe(recipe);
        recalculate();
    }

    @Nullable
    TreeRecipe getRecipe(TreeIngredient ingredient) {
        Optional<TreeRecipe> resolved = resolutions.get(ingredient.key());
        if (resolved != null) {
            return resolved.orElse(null);
        }
        return resolver.apply(ingredient);
    }

    void addResolution(TreeIngredient ingredient, @Nullable TreeRecipe recipe) {
        resolutions.put(ingredient.key(), Optional.ofNullable(recipe));
        if (ingredient.key().equals(goal.ingredient.key())) {
            goal.defineRecipe(recipe);
            goal.amount = ingredient.amount();
        }
        recalculate();
    }

    void recalculate() {
        goal.recalculate(this);
    }

    void calculateProgress(Map<String, TreeIngredient> inventory) {
        cost.calculateProgress(goal, batches, inventory);
    }

    void calculateCost() {
        cost.calculate(goal, batches);
    }
}
