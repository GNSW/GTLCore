package org.gtlcore.gtlcore.integration.jei.tree;

import org.gtlcore.gtlcore.GTLCore;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeWrapper;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fluids.FluidStack;

import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A recipe as seen by the recipe tree.
 *
 * <p>
 * Regular recipes are backed by a JEI category + recipe pair; their inputs and outputs are read from
 * the same JEI slots that JEI draws. Resolution recipes are synthetic one-to-one mappings that pin a
 * tag ingredient to one of its alternatives, mirroring EMI's {@code EmiResolutionRecipe}.
 * </p>
 */
final class TreeRecipe {

    private final IJeiRuntime runtime;
    private final @Nullable IRecipeCategory<Object> category;
    private final @Nullable Object recipe;
    private final String identity;
    private final @Nullable IRecipeLayoutDrawable<?> knownLayout;
    private final @Nullable TreeIngredient resolutionIngredient;
    private final @Nullable TreeIngredient resolutionOutput;
    private @Nullable RecipeShape shape;

    private TreeRecipe(IJeiRuntime runtime,
                       @Nullable IRecipeCategory<Object> category,
                       @Nullable Object recipe,
                       String identity,
                       @Nullable IRecipeLayoutDrawable<?> knownLayout,
                       @Nullable TreeIngredient resolutionIngredient,
                       @Nullable TreeIngredient resolutionOutput,
                       @Nullable RecipeShape shape) {
        this.runtime = runtime;
        this.category = category;
        this.recipe = recipe;
        this.identity = identity;
        this.knownLayout = knownLayout;
        this.resolutionIngredient = resolutionIngredient;
        this.resolutionOutput = resolutionOutput;
        this.shape = shape;
    }

    @SuppressWarnings("unchecked")
    static TreeRecipe of(IJeiRuntime runtime, IRecipeLayoutDrawable<?> layout) {
        IRecipeCategory<Object> category = (IRecipeCategory<Object>) layout.getRecipeCategory();
        Object recipe = layout.getRecipe();
        return new TreeRecipe(runtime, category, recipe, identity(category, recipe), layout, null, null, null);
    }

    @SuppressWarnings("unchecked")
    static TreeRecipe of(IJeiRuntime runtime, IRecipeCategory<?> category, Object recipe) {
        IRecipeCategory<Object> typedCategory = (IRecipeCategory<Object>) category;
        return new TreeRecipe(runtime, typedCategory, recipe,
                identity(typedCategory, recipe), null, null, null, null);
    }

    static TreeRecipe resolution(IJeiRuntime runtime,
                                 TreeIngredient ingredient,
                                 TreeIngredient.Variant target) {
        TreeIngredient output = TreeIngredient.single(target);
        RecipeShape shape = new RecipeShape(List.of(output), List.of(output));
        return new TreeRecipe(runtime, null, null,
                "resolution§" + ingredient.key() + "§" + target.uid(), null,
                ingredient, output, shape);
    }

    private static String identity(IRecipeCategory<?> category, Object recipe) {
        String categoryId = category.getRecipeType().getUid().toString();
        if (recipe instanceof GTRecipeWrapper wrapper && wrapper.recipe.id != null) {
            return categoryId + "|" + wrapper.recipe.id;
        }
        if (recipe instanceof Recipe<?> vanillaRecipe && vanillaRecipe.getId() != null) {
            return categoryId + "|" + vanillaRecipe.getId();
        }
        String detail = String.valueOf(recipe);
        return categoryId + "|" + recipe.getClass().getName() + "|" +
                Integer.toUnsignedString(detail.hashCode(), 16);
    }

    static String recipeId(Object recipe) {
        if (recipe instanceof GTRecipeWrapper wrapper && wrapper.recipe.id != null) {
            return wrapper.recipe.id.toString();
        }
        if (recipe instanceof Recipe<?> vanillaRecipe && vanillaRecipe.getId() != null) {
            return vanillaRecipe.getId().toString();
        }
        return recipe.getClass().getName() + "@" + System.identityHashCode(recipe);
    }

    IJeiRuntime runtime() {
        return runtime;
    }

    @Nullable
    IRecipeCategory<Object> category() {
        return category;
    }

    @Nullable
    Object recipe() {
        return recipe;
    }

    String identity() {
        return identity;
    }

    boolean isResolution() {
        return resolutionOutput != null;
    }

    @Nullable
    TreeIngredient resolutionIngredient() {
        return resolutionIngredient;
    }

    @Nullable
    TreeIngredient resolutionOutput() {
        return resolutionOutput;
    }

    List<TreeIngredient> inputs() {
        return shape().inputs();
    }

    List<TreeIngredient> outputs() {
        return shape().outputs();
    }

    Component label() {
        if (isResolution()) {
            TreeIngredient output = resolutionOutput;
            return output == null ? Component.empty() : Component.literal(output.displayVariant().name());
        }
        if (category == null) {
            return Component.empty();
        }
        String recipeName = recipe == null ? "" : recipeId(recipe);
        if (recipeName.isBlank()) {
            recipeName = "?";
        }
        return Component.literal(category.getTitle().getString() + " · " + shortRecipeName(recipeName));
    }

    private static String shortRecipeName(String value) {
        int slash = value.lastIndexOf('/');
        return slash >= 0 && slash + 1 < value.length() ? value.substring(slash + 1) : value;
    }

    private RecipeShape shape() {
        if (shape == null) {
            shape = extractShape();
        }
        return shape;
    }

    private RecipeShape extractShape() {
        if (isResolution()) {
            TreeIngredient output = resolutionOutput;
            return output == null ? RecipeShape.EMPTY :
                    new RecipeShape(List.of(output), List.of(output));
        }
        if (category == null || recipe == null) {
            return RecipeShape.EMPTY;
        }
        if (knownLayout != null) {
            return extractShape(runtime.getIngredientManager(), knownLayout);
        }
        try {
            Optional<IRecipeLayoutDrawable<Object>> layout = runtime.getRecipeManager()
                    .createRecipeLayoutDrawable(category, recipe,
                            runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup());
            return layout.map(drawable -> extractShape(runtime.getIngredientManager(), drawable))
                    .orElse(RecipeShape.EMPTY);
        } catch (RuntimeException exception) {
            GTLCore.LOGGER.debug("Could not inspect JEI recipe tree layout for {}",
                    identity, exception);
            return RecipeShape.EMPTY;
        }
    }

    private static RecipeShape extractShape(IIngredientManager ingredientManager,
                                            IRecipeLayoutDrawable<?> layout) {
        List<TreeIngredient> inputs = new ArrayList<>();
        List<TreeIngredient> outputs = new ArrayList<>();
        for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews()) {
            List<ITypedIngredient<?>> ingredients = slot.getAllIngredientsList().stream()
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (ingredients.isEmpty()) {
                continue;
            }
            float chance = chanceFor(layout.getRecipe(), slot.getRole(), ingredients.get(0));
            TreeIngredient ingredient = TreeIngredient.of(ingredientManager, ingredients, chance,
                    slot.getRole() == RecipeIngredientRole.INPUT);
            if (ingredient.isEmpty()) {
                continue;
            }
            if (slot.getRole() == RecipeIngredientRole.INPUT) {
                inputs.add(ingredient);
            } else if (slot.getRole() == RecipeIngredientRole.OUTPUT) {
                outputs.add(ingredient);
            }
        }
        return new RecipeShape(List.copyOf(inputs), List.copyOf(outputs));
    }

    private static float chanceFor(Object recipe,
                                   RecipeIngredientRole role,
                                   ITypedIngredient<?> ingredient) {
        if (!(recipe instanceof GTRecipeWrapper wrapper)) {
            return 1.0F;
        }
        Optional<ItemStack> item = ingredient.getItemStack();
        if (item.isPresent()) {
            var contents = role == RecipeIngredientRole.INPUT ?
                    wrapper.recipe.getInputContents(ItemRecipeCapability.CAP) :
                    wrapper.recipe.getOutputContents(ItemRecipeCapability.CAP);
            for (var content : contents) {
                if (ItemRecipeCapability.CAP.of(content.getContent()).test(item.get())) {
                    return chance(content.chance, content.maxChance);
                }
            }
        }

        Optional<FluidStack> fluid = ingredient.getIngredient(ForgeTypes.FLUID_STACK);
        if (fluid.isPresent()) {
            var contents = role == RecipeIngredientRole.INPUT ?
                    wrapper.recipe.getInputContents(FluidRecipeCapability.CAP) :
                    wrapper.recipe.getOutputContents(FluidRecipeCapability.CAP);
            for (var content : contents) {
                for (var candidate : FluidRecipeCapability.CAP.of(content.getContent()).getStacks()) {
                    if (candidate.getFluid() == fluid.get().getFluid() &&
                            java.util.Objects.equals(candidate.getTag(), fluid.get().getTag())) {
                        return chance(content.chance, content.maxChance);
                    }
                }
            }
        }
        return 1.0F;
    }

    private static float chance(int chance, int maxChance) {
        if (maxChance <= 0 || chance >= maxChance) {
            return 1.0F;
        }
        return Math.max(0.0F, chance / (float) maxChance);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TreeRecipe recipe && identity.equals(recipe.identity);
    }

    @Override
    public int hashCode() {
        return identity.hashCode();
    }

    @Override
    public String toString() {
        return "TreeRecipe[" + identity + "]";
    }

    record RecipeShape(List<TreeIngredient> inputs, List<TreeIngredient> outputs) {

        private static final RecipeShape EMPTY = new RecipeShape(List.of(), List.of());

        TreeIngredient outputFor(TreeIngredient ingredient) {
            for (TreeIngredient output : outputs) {
                if (output.key().equals(ingredient.key())) {
                    return output;
                }
            }
            return outputs.isEmpty() ? TreeIngredient.EMPTY : outputs.get(0);
        }
    }
}
