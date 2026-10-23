package org.gtlcore.gtlcore.common.data;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.recipe.PlushieConversionRecipe;
import org.gtlcore.gtlcore.common.recipe.PrankFishRecipe;

import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.HashSet;

public class GTLRecipes {

    public static final HashSet<String> DISASSEMBLY_RECORD = new HashSet<>();

    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS = DeferredRegister.create(
            ForgeRegistries.RECIPE_SERIALIZERS, GTLCore.MOD_ID);
    public static final RegistryObject<PrankFishRecipe.Serializer> PRANK_FISH = SERIALIZERS.register(
            "prank_fish", PrankFishRecipe.Serializer::new);
    public static final RegistryObject<PlushieConversionRecipe.Serializer> PLUSHIE_CONVERSION = SERIALIZERS.register(
            "plushie_conversion", PlushieConversionRecipe.Serializer::new);

    public static void register(IEventBus eventBus) {
        SERIALIZERS.register(eventBus);
    }
}
