package org.gtlcore.gtlcore.integration.jei;

import org.gtlcore.gtlcore.common.recipe.PrankFishRecipe;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.ingredient.ICraftingGridHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension;

import java.util.Arrays;
import java.util.List;

/** The joke is confined to JEI: crafting, recipe synchronization and automation use the actual output. */
public record PrankFishRecipeExtension(PrankFishRecipe recipe) implements ICraftingCategoryExtension {

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, ICraftingGridHelper grid, IFocusGroup focuses) {
        grid.createAndSetInputs(builder, recipe.getIngredients().stream()
                .map(ingredient -> Arrays.asList(ingredient.getItems())).toList(), getWidth(), getHeight());
        grid.createAndSetOutputs(builder, List.of(new ItemStack(
                BuiltInRegistries.ITEM.get(new ResourceLocation("expatternprovider", "fishbig")))))
                // JEI adds these before slot callbacks. Keep the real recipe ID for transfers and bookmarks.
                .addRichTooltipCallback((slot, tooltip) -> tooltip.getLines().removeIf(line -> line.left().orElse(null) instanceof Component component &&
                        component.getContents() instanceof TranslatableContents contents &&
                        (contents.getKey().equals("jei.tooltip.recipe.by") ||
                                contents.getKey().equals("jei.tooltip.recipe.id"))));
    }

    @Override
    public ResourceLocation getRegistryName() {
        return recipe.getId();
    }

    @Override
    public int getWidth() {
        return recipe.getWidth();
    }

    @Override
    public int getHeight() {
        return recipe.getHeight();
    }
}
