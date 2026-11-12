package org.gtlcore.gtlcore.integration.jei.tree;

import org.gtlcore.gtlcore.GTLCore;

import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Predicate;

/**
 * JEI recipe lookups used by the tree. This is the closest JEI equivalent of EMI's recipe manager
 * index ({@code EmiApi.getRecipeManager().getRecipesByOutput(...)}).
 */
final class RecipeTreeLookup {

    private static final int MAX_ALTERNATIVES = 24;
    private static final int MAX_VARIANTS_TO_SCAN = 32;
    private static final Map<String, List<TreeRecipe>> RECIPES_BY_OUTPUT = new HashMap<>();
    private static final Map<String, Optional<TreeRecipe>> MATCHES = new HashMap<>();
    private static final Map<IIngredientType<?>, Map<String, Integer>> INGREDIENT_ORDER = new HashMap<>();
    private static final Random RANDOM = new Random();

    private RecipeTreeLookup() {}

    static void reset() {
        RECIPES_BY_OUTPUT.clear();
        MATCHES.clear();
        INGREDIENT_ORDER.clear();
    }

    static @Nullable TreeRecipe find(IJeiRuntime runtime,
                                     TreeIngredient ingredient,
                                     String identity) {
        String cacheKey = ingredient.key() + "|" + identity;
        Optional<TreeRecipe> cached = MATCHES.get(cacheKey);
        if (cached != null) {
            return cached.orElse(null);
        }
        TreeRecipe found = null;
        int scanned = 0;
        for (TreeIngredient.Variant variant : ingredient.variants()) {
            if (variant.typed() == null) {
                continue;
            }
            if (scanned++ >= MAX_VARIANTS_TO_SCAN) {
                break;
            }
            for (TreeRecipe recipe : recipesFor(runtime, variant.typed())) {
                if (recipe.identity().equals(identity)) {
                    found = recipe;
                    break;
                }
            }
            if (found != null) {
                break;
            }
        }
        MATCHES.put(cacheKey, Optional.ofNullable(found));
        return found;
    }

    static List<TreeRecipe> all(IJeiRuntime runtime, TreeIngredient ingredient) {
        Map<String, TreeRecipe> recipes = new LinkedHashMap<>();
        int scanned = 0;
        for (TreeIngredient.Variant variant : ingredient.variants()) {
            if (variant.typed() == null) {
                continue;
            }
            if (scanned++ >= MAX_VARIANTS_TO_SCAN) {
                break;
            }
            for (TreeRecipe recipe : recipesFor(runtime, variant.typed())) {
                recipes.putIfAbsent(recipe.identity(), recipe);
            }
        }
        return List.copyOf(recipes.values());
    }

    static List<TreeRecipe> recipesFor(IJeiRuntime runtime, ITypedIngredient<?> typed) {
        String key = cacheKey(runtime, typed);
        List<TreeRecipe> cached = RECIPES_BY_OUTPUT.get(key);
        if (cached != null) {
            return cached;
        }

        List<TreeRecipe> recipes = new ArrayList<>();
        List<IFocus<?>> focuses = List.of(runtime.getJeiHelpers().getFocusFactory()
                .createFocus(RecipeIngredientRole.OUTPUT, typed));
        List<IRecipeCategory<?>> categories = runtime.getRecipeManager()
                .createRecipeCategoryLookup()
                .limitFocus(focuses)
                .get()
                .toList();
        for (IRecipeCategory<?> category : categories) {
            collect(runtime, category, focuses, recipes);
            if (recipes.size() >= MAX_ALTERNATIVES) {
                break;
            }
        }
        List<TreeRecipe> result = List.copyOf(recipes);
        RECIPES_BY_OUTPUT.put(key, result);
        return result;
    }

    private static <T> void collect(IJeiRuntime runtime,
                                    IRecipeCategory<T> category,
                                    List<IFocus<?>> focuses,
                                    List<TreeRecipe> recipes) {
        try {
            runtime.getRecipeManager().createRecipeLookup(category.getRecipeType())
                    .limitFocus(focuses)
                    .get()
                    .limit(MAX_ALTERNATIVES - recipes.size())
                    .map(recipe -> TreeRecipe.of(runtime, category, recipe))
                    .forEach(recipes::add);
        } catch (RuntimeException exception) {
            GTLCore.LOGGER.debug("Could not query JEI recipe tree alternatives for {}",
                    category.getRecipeType().getUid(), exception);
        }
    }

    static @Nullable TreeRecipe preferred(IJeiRuntime runtime,
                                          TreeIngredient ingredient,
                                          Map<String, TreeIngredient> inventory,
                                          Predicate<TreeRecipe> enabled) {
        TreeRecipe preferred = null;
        int preferredWeight = -1;
        for (TreeRecipe recipe : all(runtime, ingredient)) {
            int weight = 0;
            if (enabled.test(recipe)) {
                weight += 4;
            }
            if (inventoryCanCraft(recipe, inventory)) {
                weight += 8;
            }
            if (recipe.category() != null &&
                    recipe.category().getRecipeType().equals(RecipeTypes.CRAFTING)) {
                weight += 2;
            }
            if (weight > preferredWeight) {
                preferredWeight = weight;
                preferred = recipe;
            }
        }
        return preferred;
    }

    static boolean inventoryCanCraft(TreeRecipe recipe, Map<String, TreeIngredient> inventory) {
        Map<String, Long> remaining = new HashMap<>();
        inventory.forEach((key, stack) -> remaining.put(key, stack.amount()));
        for (TreeIngredient input : recipe.inputs()) {
            long need = Math.max(1, input.amount());
            boolean satisfied = false;
            for (TreeIngredient.Variant variant : input.variants()) {
                long have = remaining.getOrDefault(variant.uid(), 0L);
                if (have >= need) {
                    remaining.put(variant.uid(), have - need);
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) {
                return false;
            }
        }
        return true;
    }

    static @Nullable TreeRecipe randomRecipe(IJeiRuntime runtime) {
        List<IRecipeCategory<?>> categories = runtime.getRecipeManager()
                .createRecipeCategoryLookup()
                .get()
                .toList();
        if (categories.isEmpty()) {
            return null;
        }
        for (int attempt = 0; attempt < 100; attempt++) {
            IRecipeCategory<?> category = categories.get(RANDOM.nextInt(categories.size()));
            List<?> recipes;
            try {
                recipes = runtime.getRecipeManager().createRecipeLookup(
                        (mezz.jei.api.recipe.RecipeType) category.getRecipeType()).get().toList();
            } catch (RuntimeException exception) {
                continue;
            }
            if (recipes.isEmpty()) {
                continue;
            }
            Object recipe = recipes.get(RANDOM.nextInt(recipes.size()));
            TreeRecipe candidate = TreeRecipe.of(runtime, category, recipe);
            if (!candidate.outputs().isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    static int order(IJeiRuntime runtime, TreeIngredient ingredient) {
        TreeIngredient.Variant variant = ingredient.displayVariant();
        if (variant.typed() == null) {
            return Integer.MAX_VALUE;
        }
        IIngredientType<?> type = variant.typed().getType();
        Map<String, Integer> order = INGREDIENT_ORDER.computeIfAbsent(type,
                key -> buildOrder(runtime, variant.typed()));
        return order.getOrDefault(variant.uid(), Integer.MAX_VALUE);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> buildOrder(IJeiRuntime runtime,
                                                   ITypedIngredient<?> typed) {
        Map<String, Integer> order = new HashMap<>();
        IIngredientHelper<Object> helper = (IIngredientHelper<Object>) runtime.getIngredientManager()
                .getIngredientHelper((IIngredientType<Object>) typed.getType());
        int index = 0;
        for (Object ingredient : runtime.getIngredientManager()
                .getAllIngredients((IIngredientType<Object>) typed.getType())) {
            order.putIfAbsent(helper.getUniqueId(ingredient, UidContext.Ingredient), index++);
        }
        return order;
    }

    @SuppressWarnings("unchecked")
    private static String cacheKey(IJeiRuntime runtime, ITypedIngredient<?> typed) {
        IIngredientHelper<Object> helper = (IIngredientHelper<Object>) runtime.getIngredientManager()
                .getIngredientHelper((IIngredientType<Object>) typed.getType());
        return typed.getType().getUid() + "|" +
                helper.getUniqueId(typed.getIngredient(), UidContext.Ingredient);
    }
}
