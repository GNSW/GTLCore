package org.gtlcore.gtlcore.integration.jei.tree;

import mezz.jei.api.runtime.IJeiRuntime;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Global recipe tree state. Mirrors EMI's {@code BoM} class.
 */
final class RecipeTreeState {

    private static final RecipeTreeDefaults DEFAULTS = new RecipeTreeDefaults();
    private static @Nullable IJeiRuntime runtime;
    private static @Nullable MaterialTree tree;
    private static boolean craftingMode;

    private RecipeTreeState() {}

    static void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        RecipeTreeLookup.reset();
    }

    static void onRuntimeUnavailable() {
        runtime = null;
        tree = null;
        craftingMode = false;
        RecipeTreeLookup.reset();
        TreeIngredient.resetCaches();
    }

    static @Nullable IJeiRuntime runtime() {
        return runtime;
    }

    static @Nullable MaterialTree tree() {
        return tree;
    }

    static void setTree(@Nullable MaterialTree materialTree) {
        tree = materialTree;
    }

    static boolean craftingMode() {
        return craftingMode;
    }

    static void setCraftingMode(boolean value) {
        craftingMode = value;
    }

    static void setGoal(TreeRecipe recipe) {
        if (runtime == null) {
            return;
        }
        tree = new MaterialTree(recipe, RecipeTreeState::resolve);
        craftingMode = false;
    }

    static boolean isGoal(TreeRecipe recipe) {
        return tree != null && recipe.equals(tree.goal.recipe);
    }

    static void clearTree() {
        tree = null;
    }

    @Nullable
    static TreeRecipe resolve(TreeIngredient ingredient) {
        if (runtime == null) {
            return null;
        }
        String identity = DEFAULTS.get(ingredient.key());
        if (identity == null) {
            for (TreeIngredient.Variant variant : ingredient.variants()) {
                identity = DEFAULTS.get(variant.uid());
                if (identity != null) {
                    break;
                }
            }
        }
        if (identity == null) {
            return null;
        }
        return RecipeTreeLookup.find(runtime, ingredient, identity);
    }

    static DefaultStatus getRecipeStatus(String identity, List<TreeIngredient> outputs) {
        int found = 0;
        for (TreeIngredient output : outputs) {
            if (identity.equals(DEFAULTS.get(output.key()))) {
                found++;
            }
        }
        if (found == 0) {
            return DefaultStatus.EMPTY;
        }
        if (found >= outputs.size()) {
            return DefaultStatus.FULL;
        }
        return DefaultStatus.PARTIAL;
    }

    static void addRecipe(String identity, List<TreeIngredient> outputs) {
        for (TreeIngredient output : outputs) {
            DEFAULTS.put(output.key(), identity);
            for (TreeIngredient.Variant variant : output.variants()) {
                DEFAULTS.put(variant.uid(), identity);
            }
        }
        DEFAULTS.save();
        recalculate();
    }

    static void removeRecipe(String identity, List<TreeIngredient> outputs) {
        for (TreeIngredient output : outputs) {
            if (identity.equals(DEFAULTS.get(output.key()))) {
                DEFAULTS.remove(output.key());
            }
            for (TreeIngredient.Variant variant : output.variants()) {
                if (identity.equals(DEFAULTS.get(variant.uid()))) {
                    DEFAULTS.remove(variant.uid());
                }
            }
        }
        DEFAULTS.save();
        recalculate();
    }

    static boolean isDefaultRecipe(TreeIngredient ingredient, TreeRecipe recipe) {
        return recipe.identity().equals(DEFAULTS.get(ingredient.key()));
    }

    @Nullable
    static String recipeIdentity(TreeIngredient ingredient) {
        return DEFAULTS.get(ingredient.key());
    }

    static @Nullable TreeRecipe preferredRecipe(TreeIngredient ingredient,
                                                java.util.Map<String, TreeIngredient> inventory) {
        if (runtime == null) {
            return null;
        }
        return RecipeTreeLookup.preferred(runtime, ingredient, inventory,
                recipe -> isDefaultRecipe(ingredient, recipe));
    }

    static void recalculate() {
        if (tree != null) {
            tree.recalculate();
        }
    }

    enum DefaultStatus {
        EMPTY,
        PARTIAL,
        FULL
    }
}
