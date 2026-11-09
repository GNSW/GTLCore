package org.gtlcore.gtlcore.integration.jei.tree;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * JEI port of SFTCore's enhanced EMI autocraft: pressing TAB while a crafting table or a matching
 * GT machine is open fills the next craftable step of the recipe tree, crafts it into the inventory
 * and immediately continues with the next step until the tree has nothing left to craft.
 *
 * <p>
 * SFTCore hooks EMI's screen manager for the key press and the on-screen hint; JEI has no such
 * hook, so the same behaviour is implemented with a Forge key event, a render event and a client
 * tick that re-arms the chain after every finished step.
 * </p>
 */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class JeiTreeAutocraft {

    /**
     * SFTCore aborts after a short while when a fill never finishes; the same guard is used here.
     */
    private static final int MAX_FAILED_STEPS = 5;

    private static boolean chaining;
    private static int failedSteps;

    private JeiTreeAutocraft() {}

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS || event.getKey() != GLFW.GLFW_KEY_TAB) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof AbstractContainerScreen<?> screen) {
            autoFillAvailableRecipes(screen);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !chaining || RecipeTreeCrafting.isRunning()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof AbstractContainerScreen<?> screen)) {
            stopChain();
            return;
        }
        if (!prepareNextStep(screen)) {
            stopChain();
            message("gtlcore.jei.recipe_tree.craft.completed_tree", ChatFormatting.GREEN);
        }
    }

    private static void autoFillAvailableRecipes(AbstractContainerScreen<?> screen) {
        if (chaining || RecipeTreeCrafting.isRunning()) {
            return;
        }
        if (RecipeTreeState.tree() == null || !RecipeTreeState.craftingMode() ||
                RecipeTreeState.runtime() == null) {
            return;
        }
        failedSteps = 0;
        if (!prepareNextStep(screen)) {
            return;
        }
        chaining = true;
        message("gtlcore.jei.recipe_tree.craft.started", ChatFormatting.GRAY);
    }

    /**
     * Fills the next craftable step of the tree. Crafting-table steps chain the whole table plan;
     * machine steps only hand their ingredients over, exactly like EMI autocraft does.
     */
    private static boolean prepareNextStep(AbstractContainerScreen<?> screen) {
        IJeiRuntime runtime = RecipeTreeState.runtime();
        MaterialTree tree = RecipeTreeState.tree();
        if (runtime == null || tree == null) {
            return false;
        }
        RecipeTreeInventoryOverlay.Entry focus = findFocus(runtime, screen);
        if (focus == null) {
            return false;
        }
        boolean started = RecipeTreeCrafting.startStep(screen, runtime, focus.recipe(),
                focus.ingredient(), Math.max(1, focus.batches()), false);
        if (!started) {
            failedSteps++;
            return failedSteps < MAX_FAILED_STEPS;
        }
        failedSteps = 0;
        return true;
    }

    /**
     * Mirrors EMI autocraft's {@code findFocus}: the first partially or fully craftable tree step
     * that the currently opened container can accept.
     */
    static @Nullable RecipeTreeInventoryOverlay.Entry findFocus(IJeiRuntime runtime,
                                                                AbstractContainerScreen<?> screen) {
        for (RecipeTreeInventoryOverlay.Entry entry : RecipeTreeInventoryOverlay.remainingSteps(runtime, screen)) {
            if (entry.recipe() == null || entry.recipe().category() == null || entry.state() < 1) {
                continue;
            }
            if (screen instanceof CraftingScreen) {
                if (RecipeTypes.CRAFTING.equals(entry.recipe().category().getRecipeType())) {
                    return entry;
                }
            } else if (RecipeTreeCrafting.fillsMachine(screen, entry.recipe())) {
                return entry;
            }
        }
        return null;
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (RecipeTreeState.tree() == null || !RecipeTreeState.craftingMode()) {
            return;
        }
        IJeiRuntime runtime = RecipeTreeState.runtime();
        if (runtime == null) {
            return;
        }
        String key;
        if (RecipeTreeCrafting.isRunning()) {
            key = "gtlcore.jei.recipe_tree.craft.busy";
        } else if (findFocus(runtime, screen) != null) {
            key = "gtlcore.jei.recipe_tree.autocraft.tab";
        } else {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        graphics.drawString(Minecraft.getInstance().font,
                Component.translatable(key), 4, screen.height - 16, 0xFFFFFFFF, true);
    }

    private static void stopChain() {
        chaining = false;
        failedSteps = 0;
    }

    private static void message(String key, ChatFormatting color) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(key).withStyle(color), true);
        }
    }
}
