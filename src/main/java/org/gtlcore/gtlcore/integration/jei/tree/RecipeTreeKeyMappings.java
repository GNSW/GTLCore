package org.gtlcore.gtlcore.integration.jei.tree;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/**
 * EMI-style crafting shortcuts for the recipe tree and its inventory overlay.
 */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RecipeTreeKeyMappings {

    public static final String CATEGORY = "key.categories.gtlcore.recipe_tree";

    public static final KeyMapping CRAFT_ONE_TO_INVENTORY = new KeyMapping(
            "key.gtlcore.recipe_tree.craft_one_to_inventory", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyMapping CRAFT_ALL_TO_INVENTORY = new KeyMapping(
            "key.gtlcore.recipe_tree.craft_all_to_inventory", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyMapping CRAFT_ONE_TO_CURSOR = new KeyMapping(
            "key.gtlcore.recipe_tree.craft_one_to_cursor", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyMapping FILL_ONE = new KeyMapping(
            "key.gtlcore.recipe_tree.fill_one", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyMapping FILL_ALL = new KeyMapping(
            "key.gtlcore.recipe_tree.fill_all", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyMapping SHOW_RECIPE = new KeyMapping(
            "key.gtlcore.recipe_tree.show_recipe", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

    private RecipeTreeKeyMappings() {}

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(CRAFT_ONE_TO_INVENTORY);
        event.register(CRAFT_ALL_TO_INVENTORY);
        event.register(CRAFT_ONE_TO_CURSOR);
        event.register(FILL_ONE);
        event.register(FILL_ALL);
        event.register(SHOW_RECIPE);
    }

    static boolean matches(KeyMapping mapping, int keyCode, int scanCode) {
        return mapping.matches(keyCode, scanCode);
    }

    static String label(KeyMapping mapping) {
        return mapping.getTranslatedKeyMessage().getString();
    }
}
