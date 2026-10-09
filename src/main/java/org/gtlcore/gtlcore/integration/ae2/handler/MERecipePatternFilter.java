package org.gtlcore.gtlcore.integration.ae2.handler;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.IntProviderIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.*;

/** Cached recipe metadata, shared by blacklist/whitelist rules and the paged recipe search. */
public final class MERecipePatternFilter {

    public static final int MAX_EXPRESSION_LENGTH = 256;
    public static final int MAX_RULES = 128;

    private MERecipePatternFilter() {}

    public enum Target {

        RECIPE_ID,
        INPUT,
        OUTPUT;

        public String translationKey() {
            return "gtceu.machine.me_recipe_pattern_buffer.filter." + name().toLowerCase(Locale.ROOT);
        }
    }

    public record Rule(Target target, String expression) {

        public Rule {
            expression = expression.strip();
        }

        public boolean matches(Descriptor descriptor) {
            List<String> values = switch (target) {
                case RECIPE_ID -> descriptor.recipeIds();
                case INPUT -> descriptor.inputs();
                case OUTPUT -> descriptor.outputs();
            };
            return values.stream().anyMatch(value -> matchesText(value, expression));
        }
    }

    public record Descriptor(List<String> recipeIds, List<String> inputs, List<String> outputs, Set<String> materialIds) {

        public boolean matchesSearch(String query, Set<String> localizedMaterials) {
            if (query.isBlank()) return true;
            return recipeIds.stream().anyMatch(value -> matchesText(value, query)) ||
                    inputs.stream().anyMatch(value -> matchesText(value, query)) ||
                    outputs.stream().anyMatch(value -> matchesText(value, query)) ||
                    materialIds.stream().anyMatch(localizedMaterials::contains);
        }
    }

    public static Descriptor describe(GTRecipe recipe) {
        Set<String> ids = new HashSet<>();
        Set<String> inputs = new LinkedHashSet<>();
        Set<String> outputs = new LinkedHashSet<>();
        // Match the original recipe, including catalysts, circuits, chance outputs and all tag alternatives.
        appendItems(recipe.getInputContents(ItemRecipeCapability.CAP), inputs, ids);
        appendItems(recipe.getTickInputContents(ItemRecipeCapability.CAP), inputs, ids);
        appendFluids(recipe.getInputContents(FluidRecipeCapability.CAP), inputs, ids);
        appendFluids(recipe.getTickInputContents(FluidRecipeCapability.CAP), inputs, ids);
        appendItems(recipe.getOutputContents(ItemRecipeCapability.CAP), outputs, ids);
        appendItems(recipe.getTickOutputContents(ItemRecipeCapability.CAP), outputs, ids);
        appendFluids(recipe.getOutputContents(FluidRecipeCapability.CAP), outputs, ids);
        appendFluids(recipe.getTickOutputContents(FluidRecipeCapability.CAP), outputs, ids);
        return new Descriptor(List.of(normalize(recipe.id.toString()), normalize(recipe.recipeType.registryName + "/" + recipe.id)),
                List.copyOf(inputs), List.copyOf(outputs), Set.copyOf(ids));
    }

    private static void appendItems(List<Content> contents, Set<String> names, Set<String> ids) {
        for (Content content : contents) {
            Ingredient ingredient = ItemRecipeCapability.CAP.of(content.content);
            // Inspect the inner ingredient without sampling or changing a random-count ingredient's cache.
            while (true) {
                if (ingredient instanceof SizedIngredient sized) ingredient = sized.getInner();
                else if (ingredient instanceof IntProviderIngredient provider) ingredient = provider.getInner();
                else break;
            }
            for (var stack : ingredient.getItems()) {
                if (stack.isEmpty()) continue;
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                ids.add(id);
                names.add(normalize(id));
                names.add(normalize(stack.getHoverName().getString()));
                stack.getTags().forEach(tag -> names.add(normalize(tag.location().toString())));
            }
        }
    }

    private static void appendFluids(List<Content> contents, Set<String> names, Set<String> ids) {
        for (Content content : contents) {
            var ingredient = FluidRecipeCapability.CAP.of(content.content);
            for (var stack : ingredient.getStacks()) {
                if (stack.isEmpty()) continue;
                String id = BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString();
                ids.add(id);
                names.add(normalize(id));
                names.add(normalize(stack.getFluid().getFluidType().getDescription().getString()));
                stack.getFluid().defaultFluidState().getTags().forEach(tag -> names.add(normalize(tag.location().toString())));
            }
        }
    }

    public static boolean matchesText(String value, String expression) {
        String text = normalize(value);
        String glob = normalize(expression.strip());
        if (glob.isEmpty()) return false;
        if (glob.indexOf('*') < 0 && glob.indexOf('?') < 0) return text.contains(glob);
        // Full glob matching with bounded backtracking, rather than accepting arbitrary regular expressions.
        int at = 0, patternAt = 0, star = -1, retryAt = 0;
        while (at < text.length()) {
            if (patternAt < glob.length() && (glob.charAt(patternAt) == '?' || glob.charAt(patternAt) == text.charAt(at))) {
                at++;
                patternAt++;
            } else if (patternAt < glob.length() && glob.charAt(patternAt) == '*') {
                star = patternAt++;
                retryAt = at;
            } else if (star >= 0) {
                patternAt = star + 1;
                at = ++retryAt;
            } else return false;
        }
        while (patternAt < glob.length() && glob.charAt(patternAt) == '*') patternAt++;
        return patternAt == glob.length();
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
