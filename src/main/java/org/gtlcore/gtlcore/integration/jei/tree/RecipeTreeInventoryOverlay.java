package org.gtlcore.gtlcore.integration.jei.tree;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.runtime.IJeiRuntime;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EMI-style synthetic craft list shown on the left of an open container screen.
 *
 * <p>
 * It mirrors the synthetic favorites EMI adds to its sidebar while the recipe tree is in
 * crafting mode: remaining recipe steps plus missing base materials, with the same
 * craftability colors.
 * </p>
 */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RecipeTreeInventoryOverlay {

    private static final int COLUMNS = 4;
    private static final int CELL = 20;
    private static final int WIDTH = COLUMNS * CELL + 8;
    private static final int TOP = 120;
    private static final int LEFT = 4;
    private static final long REFRESH_TICKS = 20;

    private static @Nullable AbstractContainerScreen<?> cachedScreen;
    private static long cachedTick = Long.MIN_VALUE;
    private static List<Entry> cachedEntries = List.of();

    private RecipeTreeInventoryOverlay() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (!RecipeTreeState.craftingMode() || RecipeTreeState.tree() == null) {
            return;
        }
        IJeiRuntime runtime = RecipeTreeState.runtime();
        if (runtime == null) {
            return;
        }
        List<Entry> entries = entries(runtime, screen);
        if (entries.isEmpty()) {
            drawOutline(event.getGuiGraphics(), LEFT, TOP, WIDTH, 24, 0xFF41556B);
            event.getGuiGraphics().drawString(Minecraft.getInstance().font,
                    Component.translatable("gtlcore.jei.recipe_tree.craft.all_ready"),
                    LEFT + 8, TOP + 8, 0xFF8E99A8, false);
            return;
        }

        int rowsVisible = Math.max(1, (screen.height - TOP - 6) / CELL);
        int rows = Math.min(rowsVisible, (entries.size() + COLUMNS - 1) / COLUMNS);
        int height = rows * CELL + 8;
        GuiGraphics graphics = event.getGuiGraphics();
        drawOutline(graphics, LEFT, TOP, WIDTH, height, 0xFF41556B);

        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int index = row * COLUMNS + column;
                if (index >= entries.size()) {
                    break;
                }
                Entry entry = entries.get(index);
                int x = LEFT + 4 + column * CELL;
                int y = TOP + 4 + row * CELL;
                entry.ingredient.render(graphics, x + 2, y + 2);
                int color = CraftTextRenderer.stateColor(entry.state);
                CraftTextRenderer.drawMicroAmount(graphics,
                        CraftTextRenderer.amountText(entry.amount), x + CELL - 1,
                        y + CELL - 1, color);
                if (mouseInside(event.getScreen(), x, y, CELL, CELL)) {
                    drawOutline(graphics, x, y, CELL, CELL, 0xFFFFD36A);
                    drawTooltip(graphics, entry, screen);
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 0) {
            return;
        }
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (!RecipeTreeState.craftingMode() || RecipeTreeState.tree() == null) {
            return;
        }
        IJeiRuntime runtime = RecipeTreeState.runtime();
        if (runtime == null) {
            return;
        }
        List<Entry> entries = entries(runtime, screen);
        int rowsVisible = Math.max(1, (screen.height - TOP - 6) / CELL);
        int rows = Math.min(rowsVisible, (entries.size() + COLUMNS - 1) / COLUMNS);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int index = row * COLUMNS + column;
                if (index >= entries.size()) {
                    break;
                }
                int x = LEFT + 4 + column * CELL;
                int y = TOP + 4 + row * CELL;
                if (!inside(event.getMouseX(), event.getMouseY(), x, y, CELL, CELL)) {
                    continue;
                }
                Entry entry = entries.get(index);
                event.setCanceled(true);
                if (entry.recipe != null) {
                    MaterialTree tree = RecipeTreeState.tree();
                    if (tree != null) {
                        if (RecipeTreeCrafting.isRunning()) {
                            message("gtlcore.jei.recipe_tree.craft.busy", ChatFormatting.RED);
                        } else if (isFinal(entry, tree)) {
                            RecipeTreeCrafting.startChain(screen, runtime, tree,
                                    Screen.hasShiftDown());
                        } else {
                            RecipeTreeCrafting.startOne(screen, runtime, entry.recipe,
                                    entry.ingredient, Screen.hasShiftDown());
                        }
                    }
                } else {
                    showRecipes(runtime, entry.ingredient);
                }
                return;
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (!RecipeTreeState.craftingMode() || RecipeTreeState.tree() == null) {
            return;
        }
        IJeiRuntime runtime = RecipeTreeState.runtime();
        if (runtime == null) {
            return;
        }
        List<Entry> entries = entries(runtime, screen);
        int rowsVisible = Math.max(1, (screen.height - TOP - 6) / CELL);
        int rows = Math.min(rowsVisible, (entries.size() + COLUMNS - 1) / COLUMNS);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int index = row * COLUMNS + column;
                if (index >= entries.size()) {
                    break;
                }
                int x = LEFT + 4 + column * CELL;
                int y = TOP + 4 + row * CELL;
                if (!inside(mouseX(screen), mouseY(screen), x, y, CELL, CELL)) {
                    continue;
                }
                Entry entry = entries.get(index);
                if (RecipeTreeKeyMappings.matches(RecipeTreeKeyMappings.SHOW_RECIPE,
                        event.getKeyCode(), event.getScanCode())) {
                    showRecipes(runtime, entry.ingredient);
                    event.setCanceled(true);
                    return;
                }
                if (entry.recipe == null) {
                    return;
                }
                MaterialTree tree = RecipeTreeState.tree();
                if (tree == null || RecipeTreeCrafting.isRunning()) {
                    return;
                }
                if (RecipeTreeKeyMappings.matches(RecipeTreeKeyMappings.CRAFT_ONE_TO_INVENTORY,
                        event.getKeyCode(), event.getScanCode())) {
                    RecipeTreeCrafting.startOne(screen, runtime, entry.recipe,
                            entry.ingredient, false);
                    event.setCanceled(true);
                    return;
                }
                if (RecipeTreeKeyMappings.matches(RecipeTreeKeyMappings.CRAFT_ONE_TO_CURSOR,
                        event.getKeyCode(), event.getScanCode())) {
                    RecipeTreeCrafting.startOne(screen, runtime, entry.recipe,
                            entry.ingredient, true);
                    event.setCanceled(true);
                    return;
                }
                if (RecipeTreeKeyMappings.matches(RecipeTreeKeyMappings.CRAFT_ALL_TO_INVENTORY,
                        event.getKeyCode(), event.getScanCode())) {
                    if (isFinal(entry, tree)) {
                        RecipeTreeCrafting.startChain(screen, runtime, tree, false);
                    } else {
                        RecipeTreeCrafting.startStep(screen, runtime, entry.recipe,
                                entry.ingredient, entry.batches, false);
                    }
                    event.setCanceled(true);
                    return;
                }
                if (RecipeTreeKeyMappings.matches(RecipeTreeKeyMappings.FILL_ONE,
                        event.getKeyCode(), event.getScanCode()) ||
                        RecipeTreeKeyMappings.matches(RecipeTreeKeyMappings.FILL_ALL,
                                event.getKeyCode(), event.getScanCode())) {
                    boolean all = RecipeTreeKeyMappings.matches(
                            RecipeTreeKeyMappings.FILL_ALL, event.getKeyCode(), event.getScanCode());
                    fillRecipe(runtime, screen, entry.recipe, all);
                    event.setCanceled(true);
                    return;
                }
            }
        }
    }

    private static boolean isFinal(Entry entry, MaterialTree tree) {
        if (entry.recipe == null) {
            return false;
        }
        if (entry.recipe.equals(tree.goal.recipe)) {
            return true;
        }
        return entry.ingredient.key().equals(tree.goal.ingredient.key());
    }

    private static List<Entry> entries(IJeiRuntime runtime, AbstractContainerScreen<?> screen) {
        Minecraft minecraft = Minecraft.getInstance();
        long tick = minecraft.level == null ? 0 : minecraft.level.getGameTime();
        if (screen == cachedScreen && cachedTick != Long.MIN_VALUE &&
                tick - cachedTick < REFRESH_TICKS) {
            return cachedEntries;
        }
        MaterialTree tree = RecipeTreeState.tree();
        if (tree == null || minecraft.player == null) {
            return List.of();
        }
        Map<String, TreeIngredient> inventory = snapshot(runtime, minecraft.player);
        tree.calculateProgress(inventory);
        List<Entry> entries = new ArrayList<>();
        collect(tree.goal, entries, inventory);
        for (FlatMaterialCost cost : tree.cost.costs.values()) {
            if (cost.amount > 0) {
                entries.add(new Entry(cost.ingredient, null, cost.amount, 0, -1));
            }
        }
        for (ChanceMaterialCost cost : tree.cost.chanceCosts.values()) {
            long needed = cost.effectiveAmount();
            if (needed > 0) {
                entries.add(new Entry(cost.ingredient, null, needed, 0, -1));
            }
        }
        tree.calculateCost();

        cachedScreen = screen;
        cachedTick = tick;
        cachedEntries = List.copyOf(entries);
        return cachedEntries;
    }

    private static Map<String, TreeIngredient> snapshot(IJeiRuntime runtime, Player player) {
        Map<String, TreeIngredient> inventory = new HashMap<>();
        for (ItemStack stack : player.getInventory().items) {
            add(runtime, inventory, stack);
        }
        for (ItemStack stack : player.getInventory().offhand) {
            add(runtime, inventory, stack);
        }
        if (player.containerMenu != null) {
            add(runtime, inventory, player.containerMenu.getCarried());
        }
        return inventory;
    }

    private static void add(IJeiRuntime runtime, Map<String, TreeIngredient> inventory, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ITypedIngredient<ItemStack> typed = runtime.getIngredientManager()
                .createTypedIngredient(VanillaTypes.ITEM_STACK, stack).orElse(null);
        if (typed == null) {
            return;
        }
        TreeIngredient.Variant variant = TreeIngredient.Variant.of(runtime.getIngredientManager(),
                typed, false);
        if (variant == null) {
            return;
        }
        TreeIngredient existing = inventory.get(variant.uid());
        long amount = (existing == null ? 0 : existing.amount()) + stack.getCount();
        inventory.put(variant.uid(), TreeIngredient.single(variant).setAmount(amount));
    }

    private static void collect(MaterialNode node, List<Entry> entries,
                                Map<String, TreeIngredient> inventory) {
        if (node.recipe != null && !node.recipe.isResolution() && node.totalNeeded > 0) {
            int state = canCraft(node.recipe, node.neededBatches, inventory) ? 2 :
                    canCraft(node.recipe, 1, inventory) ? 1 : 0;
            entries.add(new Entry(node.ingredient, node.recipe, node.totalNeeded,
                    node.neededBatches, state));
        }
        if (node.recipe != null && node.recipe.isResolution()) {
            if (node.children != null && !node.children.isEmpty()) {
                collect(node.children.get(0), entries, inventory);
            }
            return;
        }
        if (node.children != null) {
            for (MaterialNode child : node.children) {
                collect(child, entries, inventory);
            }
        }
    }

    private static boolean canCraft(TreeRecipe recipe, long batches,
                                    Map<String, TreeIngredient> inventory) {
        if (batches <= 0) {
            return true;
        }
        Map<String, Long> available = new HashMap<>();
        inventory.forEach((key, stack) -> available.put(key, stack.amount()));
        for (TreeIngredient input : recipe.inputs()) {
            long need = Math.max(1, input.amount()) * batches;
            boolean satisfied = false;
            for (TreeIngredient.Variant variant : input.variants()) {
                long have = available.getOrDefault(variant.uid(), 0L);
                if (have >= need) {
                    available.put(variant.uid(), have - need);
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

    private static void fillRecipe(IJeiRuntime runtime,
                                   AbstractContainerScreen<?> screen,
                                   TreeRecipe recipe,
                                   boolean maxTransfer) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || recipe.category() == null || recipe.recipe() == null) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        IRecipeCategory<Object> category = recipe.category();
        IRecipeTransferHandler handler = runtime.getRecipeTransferManager()
                .getRecipeTransferHandler(menu, category).orElse(null);
        if (handler == null) {
            return;
        }
        IRecipeLayoutDrawable<Object> layout = runtime.getRecipeManager()
                .createRecipeLayoutDrawable(category, recipe.recipe(),
                        runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup())
                .orElse(null);
        if (layout == null) {
            return;
        }
        transfer(handler, menu, recipe.recipe(), layout, player, maxTransfer);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void transfer(IRecipeTransferHandler handler,
                                 AbstractContainerMenu menu,
                                 Object recipe,
                                 IRecipeLayoutDrawable<Object> layout,
                                 Player player,
                                 boolean maxTransfer) {
        IRecipeTransferError error = handler.transferRecipe(menu, recipe,
                layout.getRecipeSlotsView(), player, maxTransfer, true);
        if (error == null) {
            cachedTick = Long.MIN_VALUE;
        }
    }

    private static void showRecipes(IJeiRuntime runtime, TreeIngredient ingredient) {
        ITypedIngredient<?> typed = ingredient.displayTyped();
        if (typed == null) {
            return;
        }
        IFocus<?> focus = runtime.getJeiHelpers().getFocusFactory()
                .createFocus(RecipeIngredientRole.OUTPUT, typed);
        runtime.getRecipesGui().show(List.of(focus));
    }

    private static void drawTooltip(GuiGraphics graphics, Entry entry,
                                    AbstractContainerScreen<?> screen) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(entry.ingredient.displayVariant().name())
                .withStyle(ChatFormatting.WHITE));
        if (entry.recipe != null) {
            lines.add(entry.recipe.label().copy().withStyle(ChatFormatting.GREEN));
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.craft.step.remaining",
                    entry.amount).withStyle(ChatFormatting.GRAY));
            if (entry.batches > 0) {
                lines.add(Component.translatable("gtlcore.jei.recipe_tree.craft.step.batches",
                        entry.batches).withStyle(ChatFormatting.GRAY));
            }
            switch (entry.state) {
                case 2 -> lines.add(Component.translatable(
                        "gtlcore.jei.recipe_tree.craft.fully").withStyle(ChatFormatting.AQUA));
                case 1 -> lines.add(Component.translatable(
                        "gtlcore.jei.recipe_tree.craft.partially")
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
                default -> lines.add(Component.translatable(
                        "gtlcore.jei.recipe_tree.craft.uncraftable").withStyle(ChatFormatting.GOLD));
            }
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.craft.click_chain")
                    .withStyle(ChatFormatting.DARK_GRAY));
            addKeyHint(lines, RecipeTreeKeyMappings.CRAFT_ONE_TO_INVENTORY,
                    "gtlcore.jei.recipe_tree.craft.key.one_inv");
            addKeyHint(lines, RecipeTreeKeyMappings.CRAFT_ALL_TO_INVENTORY,
                    "gtlcore.jei.recipe_tree.craft.key.all_inv");
            addKeyHint(lines, RecipeTreeKeyMappings.CRAFT_ONE_TO_CURSOR,
                    "gtlcore.jei.recipe_tree.craft.key.one_cursor");
            addKeyHint(lines, RecipeTreeKeyMappings.FILL_ONE,
                    "gtlcore.jei.recipe_tree.craft.key.fill_one");
            addKeyHint(lines, RecipeTreeKeyMappings.FILL_ALL,
                    "gtlcore.jei.recipe_tree.craft.key.fill_all");
            addKeyHint(lines, RecipeTreeKeyMappings.SHOW_RECIPE,
                    "gtlcore.jei.recipe_tree.craft.key.show");
        } else {
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.craft.material.missing")
                    .withStyle(ChatFormatting.RED));
        }
        lines.add(Component.empty());
        lines.addAll(entry.ingredient.tooltip());
        Minecraft minecraft = Minecraft.getInstance();
        graphics.renderComponentTooltip(minecraft.font, lines,
                (int) mouseX(screen), (int) mouseY(screen));
    }

    private static void addKeyHint(List<Component> lines, net.minecraft.client.KeyMapping mapping,
                                   String translationKey) {
        if (!mapping.isUnbound()) {
            lines.add(Component.translatable(translationKey,
                    mapping.getTranslatedKeyMessage().getString())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static void message(String key, ChatFormatting color) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.translatable(key).withStyle(color), true);
        }
    }

    private static boolean mouseInside(net.minecraft.client.gui.screens.Screen screen,
                                       int x, int y, int width, int height) {
        return inside(mouseX(screen), mouseY(screen), x, y, width, height);
    }

    private static double mouseX(net.minecraft.client.gui.screens.Screen screen) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.mouseHandler.xpos() * screen.width /
                (double) minecraft.getWindow().getScreenWidth();
    }

    private static double mouseY(net.minecraft.client.gui.screens.Screen screen) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.mouseHandler.ypos() * screen.height /
                (double) minecraft.getWindow().getScreenHeight();
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y,
                                  int width, int height) {
        return mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
    }

    private static void drawOutline(GuiGraphics graphics, int x, int y, int width, int height,
                                    int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    private record Entry(TreeIngredient ingredient,
                         @Nullable TreeRecipe recipe,
                         long amount,
                         long batches,
                         int state) {}
}
