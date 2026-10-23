package org.gtlcore.gtlcore.common.recipe;

import org.gtlcore.gtlcore.common.data.GTLRecipes;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import com.google.gson.JsonObject;

/** A single-item shape conversion that keeps dye, custom name and other item NBT. */
public final class PlushieConversionRecipe extends ShapelessRecipe {

    private PlushieConversionRecipe(ShapelessRecipe recipe) {
        super(recipe.getId(), recipe.getGroup(), recipe.category(), recipe.getResultItem(RegistryAccess.EMPTY),
                recipe.getIngredients());
        if (recipe.getIngredients().size() != 1 || recipe.getResultItem(RegistryAccess.EMPTY).getCount() != 1) {
            throw new IllegalArgumentException("Plushie conversion requires one ingredient and one output");
        }
    }

    @Override
    public ItemStack assemble(CraftingContainer inventory, RegistryAccess registries) {
        ItemStack result = super.assemble(inventory, registries);
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack input = inventory.getItem(slot);
            if (!input.isEmpty()) {
                if (input.hasTag()) result.setTag(input.getTag().copy());
                break;
            }
        }
        return result;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return GTLRecipes.PLUSHIE_CONVERSION.get();
    }

    public static final class Serializer implements RecipeSerializer<PlushieConversionRecipe> {

        private final ShapelessRecipe.Serializer delegate = new ShapelessRecipe.Serializer();

        @Override
        public PlushieConversionRecipe fromJson(ResourceLocation id, JsonObject json) {
            return new PlushieConversionRecipe(delegate.fromJson(id, json));
        }

        @Override
        public PlushieConversionRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
            return new PlushieConversionRecipe(delegate.fromNetwork(id, buffer));
        }

        @Override
        public void toNetwork(FriendlyByteBuf buffer, PlushieConversionRecipe recipe) {
            delegate.toNetwork(buffer, recipe);
        }
    }
}
