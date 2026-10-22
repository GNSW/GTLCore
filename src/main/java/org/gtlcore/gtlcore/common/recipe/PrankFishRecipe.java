package org.gtlcore.gtlcore.common.recipe;

import org.gtlcore.gtlcore.common.data.GTLRecipes;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;

import com.google.gson.JsonObject;

/** Keeps the real crafting output intact; only JEI disguises the result. */
public class PrankFishRecipe extends ShapedRecipe {

    private PrankFishRecipe(ShapedRecipe recipe) {
        super(recipe.getId(), recipe.getGroup(), recipe.category(), recipe.getWidth(), recipe.getHeight(),
                recipe.getIngredients(), recipe.getResultItem(RegistryAccess.EMPTY), recipe.showNotification());
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return GTLRecipes.PRANK_FISH.get();
    }

    public static class Serializer implements RecipeSerializer<PrankFishRecipe> {

        private final ShapedRecipe.Serializer delegate = new ShapedRecipe.Serializer();

        @Override
        public PrankFishRecipe fromJson(ResourceLocation id, JsonObject json) {
            return new PrankFishRecipe(delegate.fromJson(id, json));
        }

        @Override
        public PrankFishRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
            return new PrankFishRecipe(delegate.fromNetwork(id, buffer));
        }

        @Override
        public void toNetwork(FriendlyByteBuf buffer, PrankFishRecipe recipe) {
            delegate.toNetwork(buffer, recipe);
        }
    }
}
