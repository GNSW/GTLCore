package org.gtlcore.gtlcore.integration.jei.tree;

import org.gtlcore.gtlcore.integration.jei.tree.TreeIngredient.Variant;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * EMI's BoM (recipe tree) screen, ported to JEI.
 *
 * <p>
 * The layout, controls and cost/progress behaviour intentionally follow EMI's {@code BoMScreen}:
 * the same node boxes, total cost/leftovers rows, batch control, crafting-mode toggle, fold/resolve
 * interactions and inventory progress coloring.
 * </p>
 */
final class JeiRecipeTreeScreen extends Screen {

    private static final int NODE_WIDTH = 30;
    private static final int NODE_HORIZONTAL_SPACING = 8;
    private static final int NODE_VERTICAL_SPACING = 20;
    private static final int COST_HORIZONTAL_SPACING = 8;
    private static final DecimalFormat AMOUNT_FORMAT = new DecimalFormat("#,###.##");

    private static int zoom = 0;

    private final Screen old;
    private final IJeiRuntime runtime;
    private final @Nullable AbstractContainerScreen<?> containerScreen;

    private Bounds batches = new Bounds(-24, -50, 48, 26);
    private Bounds mode = new Bounds(-24, -50, 16, 16);
    private Bounds help = new Bounds(0, 0, 16, 16);
    private double offX;
    private double offY;
    private List<Node> nodes = List.of();
    private List<Cost> costs = List.of();
    private List<CraftEntry> craftEntries = List.of();
    private List<CraftCell> craftCells = List.of();
    private int craftScroll;
    private @Nullable Bounds costBounds;
    private @Nullable Bounds remainderBounds;
    private Map<String, TreeIngredient> playerInventory = Map.of();
    private final Map<String, Optional<TreeRecipe>> preferredRecipes = new HashMap<>();
    private boolean hasRemainders;
    private int nodeWidth;
    private int nodeHeight;
    private int lastMouseX;
    private int lastMouseY;
    private double scrollAcc;
    private int craftRefreshTicks;

    JeiRecipeTreeScreen(@Nullable Screen old, IJeiRuntime runtime) {
        super(Component.translatable("gtlcore.jei.recipe_tree.title"));
        this.old = old;
        this.runtime = runtime;
        this.containerScreen = old instanceof AbstractContainerScreen<?> screen ? screen :
                runtime.getRecipesGui().getParentScreen()
                        .filter(AbstractContainerScreen.class::isInstance)
                        .map(screen -> (AbstractContainerScreen<?>) screen)
                        .orElse(null);
    }

    @Override
    protected void init() {
        super.init();
        MaterialTree tree = RecipeTreeState.tree();
        if (tree != null) {
            offY = height / -3.0;
        } else {
            offY = 0;
        }
        recalculateTree();
    }

    private void recalculateTree() {
        help = new Bounds(width - 18, height - 18, 16, 16);
        preferredRecipes.clear();
        MaterialTree tree = RecipeTreeState.tree();
        if (tree == null) {
            nodes = List.of();
            costs = List.of();
            craftEntries = List.of();
            craftCells = List.of();
            craftScroll = 0;
            costBounds = null;
            remainderBounds = null;
            hasRemainders = false;
            return;
        }

        TreeVolume volume = addNewNodes(tree.goal, tree.batches, 1, 0, ChanceState.DEFAULT);
        nodes = volume.nodes;
        int horizontalOffset = (volume.maxRight() + volume.minLeft()) / 2;
        for (Node node : nodes) {
            node.x -= horizontalOffset;
        }
        if (!nodes.isEmpty()) {
            Node node = nodes.get(0);
            int width = font.width("x" + tree.batches);
            batches = new Bounds(node.x + node.width / 2 + 6, node.y - 10, width + 12, 22);
        }

        nodeWidth = volume.maxRight() - volume.minLeft();
        nodeHeight = getNodeHeight(tree.goal);
        playerInventory = snapshotInventory();

        tree.calculateProgress(Map.of());
        Map<TreeRecipe, RecipeCount> totalRecipes = new LinkedHashMap<>();
        countRecipes(tree.goal, totalRecipes);
        tree.calculateCost();
        Map<String, FlatMaterialCost> originalCosts = new HashMap<>(tree.cost.costs);
        Map<String, ChanceMaterialCost> originalChanceCosts = new HashMap<>(tree.cost.chanceCosts);

        tree.calculateProgress(playerInventory);
        Map<TreeRecipe, RecipeCount> remainingRecipes = new LinkedHashMap<>();
        countRecipes(tree.goal, remainingRecipes);

        Map<String, FlatMaterialCost> progressCosts = new HashMap<>();
        for (FlatMaterialCost cost : tree.cost.costs.values()) {
            progressCosts.put(cost.ingredient.key(), cost);
        }
        Map<String, ChanceMaterialCost> chanceProgressCosts = new HashMap<>();
        for (ChanceMaterialCost cost : tree.cost.chanceCosts.values()) {
            chanceProgressCosts.put(cost.ingredient.key(), cost);
        }
        craftEntries = buildCraftEntries(remainingRecipes, totalRecipes, progressCosts,
                chanceProgressCosts, originalCosts, originalChanceCosts);

        costs = new ArrayList<>();
        tree.calculateCost();

        List<FlatMaterialCost> treeCosts = new ArrayList<>();
        treeCosts.addAll(tree.cost.costs.values());
        treeCosts.addAll(tree.cost.chanceCosts.values());
        treeCosts.sort(this::compareCosts);

        int cy = nodeHeight * NODE_VERTICAL_SPACING * 2;
        int costX = 0;
        for (FlatMaterialCost material : treeCosts) {
            Cost cost = new Cost(material, costX, cy, false);
            if (RecipeTreeState.craftingMode()) {
                if (material instanceof ChanceMaterialCost chanceCost) {
                    ChanceMaterialCost progress = chanceProgressCosts.get(material.ingredient.key());
                    if (progress == null) {
                        cost.alreadyDone = material.effectiveAmount();
                    } else {
                        cost.alreadyDone = (long) Math.ceil(
                                chanceCost.amount * chanceCost.chance -
                                        progress.amount * progress.chance);
                    }
                } else {
                    FlatMaterialCost progress = progressCosts.get(material.ingredient.key());
                    cost.alreadyDone = progress == null ? material.amount :
                            material.amount - progress.amount;
                }
            }
            costs.add(cost);
            costX += 16 + COST_HORIZONTAL_SPACING + amountOverflow(cost.amountText());
        }
        int costOffset = (costX - COST_HORIZONTAL_SPACING) / 2;
        for (Cost cost : costs) {
            cost.x -= costOffset;
        }
        costBounds = rowBounds(costs, cy - 24, 32);

        int totalCostWidth = font.width(Component.translatable("gtlcore.jei.recipe_tree.total_cost"));
        mode = new Bounds(totalCostWidth / 2 + 4, cy - 20, 16, 16);

        List<Cost> leftovers = new ArrayList<>();
        List<FlatMaterialCost> remainderCosts = new ArrayList<>();
        remainderCosts.addAll(tree.cost.remainders.values());
        remainderCosts.addAll(tree.cost.chanceRemainders.values());
        remainderCosts.sort(this::compareCosts);
        cy += 40;
        int remainderX = 0;
        for (FlatMaterialCost material : remainderCosts) {
            if (material.effectiveAmount() <= 0) {
                continue;
            }
            Cost cost = new Cost(material, remainderX, cy, true);
            leftovers.add(cost);
            remainderX += 16 + COST_HORIZONTAL_SPACING + amountOverflow(cost.amountText());
        }
        costOffset = (remainderX - COST_HORIZONTAL_SPACING) / 2;
        for (Cost cost : leftovers) {
            cost.x -= costOffset;
        }
        costs.addAll(leftovers);
        remainderBounds = rowBounds(leftovers, cy - 24, 32);
        hasRemainders = !leftovers.isEmpty();
    }

    private static @Nullable Bounds rowBounds(List<Cost> cells, int top, int height) {
        if (cells.isEmpty()) {
            return null;
        }
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        for (Cost cell : cells) {
            minimum = Math.min(minimum, cell.x);
            maximum = Math.max(maximum, cell.x + 16);
        }
        return new Bounds(minimum - 10, top, maximum - minimum + 20, height);
    }

    private int compareCosts(FlatMaterialCost first, FlatMaterialCost second) {
        int result = Integer.compare(RecipeTreeLookup.order(runtime, first.ingredient),
                RecipeTreeLookup.order(runtime, second.ingredient));
        if (result != 0) {
            return result;
        }
        return first.ingredient.displayVariant().name()
                .compareToIgnoreCase(second.ingredient.displayVariant().name());
    }

    private Map<String, TreeIngredient> snapshotInventory() {
        if (minecraft == null || minecraft.player == null) {
            return Map.of();
        }
        IIngredientManager ingredientManager = runtime.getIngredientManager();
        Map<String, TreeIngredient> inventory = new HashMap<>();
        for (ItemStack stack : minecraft.player.getInventory().items) {
            addInventoryStack(ingredientManager, inventory, stack);
        }
        for (ItemStack stack : minecraft.player.getInventory().offhand) {
            addInventoryStack(ingredientManager, inventory, stack);
        }
        if (minecraft.player.containerMenu != null) {
            addInventoryStack(ingredientManager, inventory,
                    minecraft.player.containerMenu.getCarried());
        }
        return inventory;
    }

    private void addInventoryStack(IIngredientManager ingredientManager,
                                   Map<String, TreeIngredient> inventory,
                                   ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ITypedIngredient<ItemStack> typed = ingredientManager
                .createTypedIngredient(VanillaTypes.ITEM_STACK, stack).orElse(null);
        if (typed == null) {
            return;
        }
        Variant variant = Variant.of(ingredientManager, typed, false);
        if (variant == null) {
            return;
        }
        TreeIngredient existing = inventory.get(variant.uid());
        long amount = (existing == null ? 0 : existing.amount()) + stack.getCount();
        inventory.put(variant.uid(), TreeIngredient.single(variant).setAmount(amount));
    }

    private static final class RecipeCount {

        long batches;
        long amount;
    }

    private static void countRecipes(MaterialNode node, Map<TreeRecipe, RecipeCount> counts) {
        if (node.recipe != null && !node.recipe.isResolution()) {
            RecipeCount count = counts.computeIfAbsent(node.recipe, key -> new RecipeCount());
            count.batches += node.neededBatches;
            count.amount += node.totalNeeded;
        }
        if (node.recipe != null && node.recipe.isResolution()) {
            if (node.children != null && !node.children.isEmpty()) {
                countRecipes(node.children.get(0), counts);
            }
            return;
        }
        if (node.children != null) {
            for (MaterialNode child : node.children) {
                countRecipes(child, counts);
            }
        }
    }

    private List<CraftEntry> buildCraftEntries(Map<TreeRecipe, RecipeCount> remainingRecipes,
                                               Map<TreeRecipe, RecipeCount> totalRecipes,
                                               Map<String, FlatMaterialCost> remainingCosts,
                                               Map<String, ChanceMaterialCost> remainingChanceCosts,
                                               Map<String, FlatMaterialCost> originalCosts,
                                               Map<String, ChanceMaterialCost> originalChanceCosts) {
        List<CraftEntry> entries = new ArrayList<>();
        for (Map.Entry<TreeRecipe, RecipeCount> entry : remainingRecipes.entrySet()) {
            TreeRecipe recipe = entry.getKey();
            RecipeCount count = entry.getValue();
            if (count.amount <= 0) {
                continue;
            }
            RecipeCount total = totalRecipes.getOrDefault(recipe, count);
            int state = 0;
            if (canCraft(recipe, count.batches)) {
                state = 2;
            } else if (canCraft(recipe, 1)) {
                state = 1;
            }
            TreeIngredient icon = recipe.outputs().isEmpty() ?
                    TreeIngredient.EMPTY : recipe.outputs().get(0);
            entries.add(CraftEntry.ofRecipe(recipe, icon, count.amount, total.amount,
                    count.batches, state));
        }
        if (entries.isEmpty()) {
            return List.of();
        }
        for (FlatMaterialCost cost : remainingCosts.values()) {
            if (cost.amount > 0) {
                FlatMaterialCost original = originalCosts.getOrDefault(cost.ingredient.key(), cost);
                entries.add(CraftEntry.ofMaterial(cost.ingredient, cost.amount, original.amount));
            }
        }
        for (ChanceMaterialCost cost : remainingChanceCosts.values()) {
            if (cost.effectiveAmount() <= 0) {
                continue;
            }
            long needed = cost.effectiveAmount();
            ChanceMaterialCost original = originalChanceCosts.get(cost.ingredient.key());
            if (original != null) {
                long done = (long) Math.ceil(
                        original.amount * original.chance - cost.amount * cost.chance);
                needed = original.effectiveAmount() - done;
            }
            if (needed > 0) {
                entries.add(CraftEntry.ofMaterial(cost.ingredient, needed, needed));
            }
        }
        return List.copyOf(entries);
    }

    private boolean canCraft(TreeRecipe recipe, long batches) {
        if (batches <= 0) {
            return true;
        }
        Map<String, Long> available = new HashMap<>();
        playerInventory.forEach((key, stack) -> available.put(key, stack.amount()));
        for (TreeIngredient input : recipe.inputs()) {
            long need = Math.max(1, input.amount()) * batches;
            boolean satisfied = false;
            for (Variant variant : input.variants()) {
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

    private static final int CRAFT_CELL_SIZE = 20;
    private static final int CRAFT_COLUMNS = 8;

    private boolean craftGridVisible() {
        return RecipeTreeState.craftingMode() && RecipeTreeState.tree() != null;
    }

    private int craftGridPanelHeight() {
        if (craftEntries.isEmpty()) {
            return 24;
        }
        int totalRows = (craftEntries.size() + CRAFT_COLUMNS - 1) / CRAFT_COLUMNS;
        int visibleRows = Math.min(craftGridRowsVisible(), totalRows - craftScroll);
        return Math.max(0, visibleRows) * CRAFT_CELL_SIZE + 8;
    }

    private int craftGridLeft() {
        return 8;
    }

    private int craftGridTop() {
        return 38;
    }

    private int craftGridWidth() {
        return CRAFT_COLUMNS * CRAFT_CELL_SIZE + 8;
    }

    private int craftGridRowsVisible() {
        return Math.max(1, (height - craftGridTop() - 10) / CRAFT_CELL_SIZE);
    }

    private double treeCenterX() {
        return width / 2.0;
    }

    private int getNodeHeight(MaterialNode node) {
        if (node.recipe != null && node.state == FoldState.EXPANDED &&
                node.children != null && !node.children.isEmpty()) {
            int height = 1;
            for (MaterialNode child : node.children) {
                height = Math.max(height, getNodeHeight(child));
            }
            if (node.recipe.isResolution()) {
                return height;
            }
            return height + 1;
        }
        return 1;
    }

    private TreeVolume addNewNodes(MaterialNode node,
                                   long multiplier,
                                   long divisor,
                                   int depth,
                                   ChanceState chance) {
        if (node.catalyst) {
            multiplier = node.amount;
        } else {
            multiplier = node.amount * (long) Math.ceil(multiplier / (float) divisor);
        }
        if (node.recipe != null && node.children != null && !node.children.isEmpty() &&
                node.state == FoldState.EXPANDED) {
            ChanceState produced = chance.produce(node.produceChance);
            if (node.recipe.isResolution()) {
                TreeVolume volume = addNewNodes(node.children.get(0), multiplier, node.divisor,
                        depth, produced);
                volume.nodes.get(0).resolution = node;
                return volume;
            }
            TreeVolume left = null;
            for (MaterialNode child : node.children) {
                ChanceState consumed = produced.consume(child.consumeChance);
                TreeVolume volume = addNewNodes(child, multiplier, node.divisor, depth + 1,
                        consumed);
                if (left == null) {
                    left = volume;
                } else {
                    left.addToRight(volume);
                }
            }
            if (left == null) {
                left = new TreeVolume(node, multiplier, depth * NODE_VERTICAL_SPACING, chance);
            } else {
                left.addHead(node, multiplier, depth * NODE_VERTICAL_SPACING, chance);
            }
            return left;
        }
        return new TreeVolume(node, multiplier, depth * NODE_VERTICAL_SPACING, chance);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF0B0F15);
        drawHeader(graphics);
        drawOutline(graphics, 6, 34, width - 12, height - 40, 0xFF41556B);
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        float scale = getScale();
        int scaledWidth = (int) (width / scale);
        int scaledHeight = (int) (height / scale);
        int contentWidth = nodeWidth * NODE_WIDTH;
        int contentHeight = nodeHeight * NODE_VERTICAL_SPACING + 80;
        int xBound = scaledWidth / 2 + contentWidth - 100;
        int topBound = scaledHeight / -2 + 20;
        int bottomBound = contentHeight + scaledHeight / 2 - 20;
        offX = Mth.clamp(offX, -xBound, xBound);
        offY = Mth.clamp(offY, -bottomBound, -topBound);

        int mx = (int) ((mouseX - treeCenterX()) / scale - offX);
        int my = (int) ((mouseY - height / 2.0) / scale - offY);

        graphics.pose().pushPose();
        graphics.pose().translate(treeCenterX(), height / 2.0, 0);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.pose().translate((float) offX, (float) offY, 0);

        MaterialTree tree = RecipeTreeState.tree();
        if (tree != null) {
            if (costBounds != null) {
                drawOutline(graphics, costBounds.x(), costBounds.y(), costBounds.width(),
                        costBounds.height(), 0xFF41556B);
            }
            if (remainderBounds != null) {
                drawOutline(graphics, remainderBounds.x(), remainderBounds.y(),
                        remainderBounds.width(), remainderBounds.height(), 0xFF41556B);
            }
            int cy = nodeHeight * NODE_VERTICAL_SPACING * 2;
            graphics.drawCenteredString(font,
                    Component.translatable("gtlcore.jei.recipe_tree.total_cost"), 0, cy - 16,
                    0xFFFFD36A);
            if (hasRemainders) {
                graphics.drawCenteredString(font,
                        Component.translatable("gtlcore.jei.recipe_tree.leftovers"), 0, cy - 16 + 40,
                        0xFFFFD36A);
            }
            for (Cost cost : costs) {
                cost.render(graphics);
            }
            for (Node node : nodes) {
                node.render(graphics, mx, my);
            }
            int batchColor = batches.contains(mx, my) ? 0xFFFFF2B0 : 0xFFFFD36A;
            graphics.drawString(font, "x" + tree.batches, batches.x() + 6,
                    batches.y() + batches.height() / 2 - 4, batchColor, true);

            boolean modeHovered = mode.contains(mx, my);
            drawModeIcon(graphics, mode, modeHovered);
        } else {
            graphics.drawCenteredString(font,
                    Component.translatable("gtlcore.jei.recipe_tree.empty.welcome"), 0, -72,
                    0xFFFFD36A);
            graphics.drawCenteredString(font,
                    Component.translatable("gtlcore.jei.recipe_tree.empty.no_tree"), 0, -48,
                    0xFFE7EDF5);
            graphics.drawCenteredString(font,
                    Component.translatable("gtlcore.jei.recipe_tree.empty.random_tree"), 0, -24,
                    0xFF8E99A8);
            graphics.drawCenteredString(font,
                    Component.translatable("gtlcore.jei.recipe_tree.empty.random_tree_input"), 0, 0,
                    0xFF8E99A8);
        }

        graphics.pose().popPose();

        if (craftGridVisible()) {
            drawCraftGrid(graphics, mouseX, mouseY);
        } else {
            craftCells = List.of();
        }

        boolean helpHovered = help.contains(mouseX, mouseY);
        drawHelpIcon(graphics, help, helpHovered);

        Hover hover = getHoveredStack(mouseX, mouseY);
        CraftCell craftHover = craftCellAt(mouseX, mouseY);
        if (craftHover != null) {
            drawCraftTooltip(graphics, craftHover, mouseX, mouseY);
        } else if (hover != null) {
            drawHoverTooltip(graphics, hover, mouseX, mouseY);
        } else if (tree != null && batches.contains(mx, my)) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.batch_size", tree.batches)
                    .withStyle(ChatFormatting.YELLOW));
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.batch_size.ideal")
                    .withStyle(ChatFormatting.GRAY));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        } else if (tree != null && mode.contains(mx, my)) {
            Component text = Component.translatable(RecipeTreeState.craftingMode() ?
                    "gtlcore.jei.recipe_tree.mode.craft" :
                    "gtlcore.jei.recipe_tree.mode.view");
            graphics.renderComponentTooltip(font, List.of(text), mouseX, mouseY);
        } else if (helpHovered) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.help.title")
                    .withStyle(ChatFormatting.YELLOW));
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.help.pan"));
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.help.zoom"));
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.help.fold"));
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.help.resolve"));
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.help.stack"));
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.help.batch"));
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.help.mode"));
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.help.craft_list"));
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.help.tools"));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    private void drawHeader(GuiGraphics graphics) {
        graphics.fill(0, 0, width, 33, 0xF0151D27);
        graphics.fill(0, 32, width, 33, 0xFF41556B);
        graphics.drawString(font, title, 12, 8, 0xFFF3F5F7, false);
        Component subtitle = Component.translatable("gtlcore.jei.recipe_tree.subtitle");
        graphics.drawString(font, font.plainSubstrByWidth(subtitle.getString(), width - 24),
                12, 20, 0xFF8E99A8, false);
    }

    private void drawCraftGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = craftGridLeft();
        int top = craftGridTop();
        int columns = CRAFT_COLUMNS;
        int cellSize = CRAFT_CELL_SIZE;
        int panelWidth = craftGridWidth();
        if (craftEntries.isEmpty()) {
            drawOutline(graphics, left, top, panelWidth, 24, 0xFF41556B);
            graphics.drawString(font,
                    Component.translatable("gtlcore.jei.recipe_tree.craft.all_ready"),
                    left + 8, top + 8, 0xFF8E99A8, false);
            craftCells = List.of();
            return;
        }
        int visibleRows = craftGridRowsVisible();
        int totalRows = (craftEntries.size() + columns - 1) / columns;
        int maxScroll = Math.max(0, totalRows - visibleRows);
        craftScroll = Mth.clamp(craftScroll, 0, maxScroll);
        int rows = Math.min(visibleRows, totalRows - craftScroll);
        int panelHeight = rows * cellSize + 8;

        drawOutline(graphics, left, top, panelWidth, panelHeight, 0xFF41556B);
        List<CraftCell> cells = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int index = (craftScroll + row) * columns + column;
                if (index >= craftEntries.size()) {
                    break;
                }
                int x = left + 4 + column * cellSize;
                int y = top + 4 + row * cellSize;
                CraftEntry entry = craftEntries.get(index);
                CraftCell cell = new CraftCell(entry, x, y, cellSize, cellSize);
                cells.add(cell);
                if (cell.contains(mouseX, mouseY)) {
                    drawOutline(graphics, x, y, cellSize, cellSize, 0xFFFFD36A);
                }
                entry.ingredient().render(graphics, x + 2, y + 2);
                int color = CraftTextRenderer.stateColor(entry.state());
                CraftTextRenderer.drawMicroAmount(graphics,
                        CraftTextRenderer.amountText(entry.amount()), x + cellSize - 1,
                        y + cellSize - 1, color);
                graphics.fill(x + 3, y + cellSize - 1, x + cellSize - 3, y + cellSize, color);
            }
        }
        craftCells = List.copyOf(cells);
        if (totalRows > visibleRows) {
            String scroll = Component.translatable("gtlcore.jei.recipe_tree.craft.scroll",
                    craftScroll + 1, totalRows).getString();
            graphics.drawString(font, scroll, left + 4, top + panelHeight + 2, 0xFF77899D, false);
        }
    }

    private @Nullable CraftCell craftCellAt(double mouseX, double mouseY) {
        for (CraftCell cell : craftCells) {
            if (cell.contains(mouseX, mouseY)) {
                return cell;
            }
        }
        return null;
    }

    private void drawCraftTooltip(GuiGraphics graphics, CraftCell cell,
                                  int mouseX, int mouseY) {
        CraftEntry entry = cell.entry();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(entry.ingredient().displayVariant().name())
                .withStyle(ChatFormatting.WHITE));
        if (entry.recipe() != null) {
            lines.add(entry.recipe().label().copy().withStyle(ChatFormatting.GREEN));
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.craft.step.remaining",
                    entry.amount()).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.craft.step.obtained",
                    Math.max(0, entry.total() - entry.amount()), entry.total())
                    .withStyle(ChatFormatting.GRAY));
            if (entry.batches() != entry.amount()) {
                lines.add(Component.translatable("gtlcore.jei.recipe_tree.craft.step.batches",
                        entry.batches()).withStyle(ChatFormatting.GRAY));
            }
            switch (entry.state()) {
                case 2 -> lines.add(Component.translatable(
                        "gtlcore.jei.recipe_tree.craft.fully").withStyle(ChatFormatting.AQUA));
                case 1 -> lines.add(Component.translatable(
                        "gtlcore.jei.recipe_tree.craft.partially").withStyle(ChatFormatting.LIGHT_PURPLE));
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
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.craft.step.remaining",
                    entry.amount()).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.craft.step.obtained",
                    Math.max(0, entry.total() - entry.amount()), entry.total())
                    .withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.empty());
        lines.addAll(entry.ingredient().tooltip());
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    private void drawModeIcon(GuiGraphics graphics, Bounds bounds, boolean hovered) {
        int border = hovered ? 0xFFFFD36A : 0xFF4A5B70;
        drawOutline(graphics, bounds.x(), bounds.y(), 16, 16, border);
        int color = RecipeTreeState.craftingMode() ? 0xFF69D79B : 0xFF7BB9E8;
        graphics.fill(bounds.x() + 3, bounds.y() + 3, bounds.x() + 13, bounds.y() + 4, color);
        graphics.fill(bounds.x() + 3, bounds.y() + 7, bounds.x() + 13, bounds.y() + 8, color);
        graphics.fill(bounds.x() + 3, bounds.y() + 11, bounds.x() + 13, bounds.y() + 12, color);
        if (RecipeTreeState.craftingMode()) {
            graphics.fill(bounds.x() + 6, bounds.y() + 3, bounds.x() + 10, bounds.y() + 13, color);
        }
    }

    private void drawHelpIcon(GuiGraphics graphics, Bounds bounds, boolean hovered) {
        int border = hovered ? 0xFFFFD36A : 0xFF4A5B70;
        drawOutline(graphics, bounds.x(), bounds.y(), 16, 16, border);
        Component question = Component.literal("?");
        graphics.drawString(font, question, bounds.x() + 6, bounds.y() + 4, 0xFFE7EDF5, true);
    }

    private void drawHoverTooltip(GuiGraphics graphics, Hover hover, int mouseX, int mouseY) {
        if (hover.stack != null) {
            List<Component> lines = new ArrayList<>(hover.stack.tooltip());
            if (Screen.hasShiftDown()) {
                addAutoResolutionLines(hover, lines);
            } else if (hover.node != null && hover.node.recipe != null) {
                lines.add(hover.node.recipe.label().copy().withStyle(ChatFormatting.GREEN));
            }
            if (hover.node != null) {
                if (hover.node.consumeChance != 1) {
                    lines.add(Component.translatable("gtlcore.jei.recipe_tree.chance.consume",
                            percent(hover.node.consumeChance)).withStyle(ChatFormatting.GOLD));
                } else if (hover.resolve != null && hover.resolve.consumeChance != 1) {
                    lines.add(Component.translatable("gtlcore.jei.recipe_tree.chance.consume",
                            percent(hover.resolve.consumeChance)).withStyle(ChatFormatting.GOLD));
                }
                if (hover.node.produceChance != 1) {
                    lines.add(Component.translatable("gtlcore.jei.recipe_tree.chance.produce",
                            percent(hover.node.produceChance)).withStyle(ChatFormatting.GOLD));
                }
            }
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }
        if (hover.category != null) {
            graphics.renderComponentTooltip(font, List.of(hover.category.getTitle()), mouseX, mouseY);
            return;
        }
        if (hover.node != null) {
            List<Component> lines = new ArrayList<>();
            if (hover.node.recipe != null) {
                lines.add(hover.node.recipe.label().copy().withStyle(ChatFormatting.GREEN));
            }
            if (hover.node.recipe != null && !hover.node.recipe.isResolution()) {
                lines.add(Component.translatable("gtlcore.jei.recipe_tree.help.fold")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    private void addAutoResolutionLines(Hover hover, List<Component> lines) {
        if (hover.stack == null) {
            return;
        }
        if (hover.stack.variants().size() > 1) {
            Variant target = autoResolveVariant(hover.stack);
            if (target != null) {
                lines.add(Component.translatable("gtlcore.jei.recipe_tree.resolve.preview",
                        target.name()).withStyle(ChatFormatting.GOLD));
            }
        } else {
            TreeRecipe recipe = preferredRecipe(hover.stack);
            if (recipe != null) {
                lines.add(recipe.label().copy().withStyle(ChatFormatting.GOLD));
            }
        }
    }

    private @Nullable TreeRecipe preferredRecipe(TreeIngredient ingredient) {
        return preferredRecipes.computeIfAbsent(ingredient.key(), key -> Optional.ofNullable(RecipeTreeState.preferredRecipe(ingredient, playerInventory)))
                .orElse(null);
    }

    private @Nullable Variant autoResolveVariant(TreeIngredient ingredient) {
        for (Variant variant : ingredient.variants()) {
            if (playerInventory.containsKey(variant.uid())) {
                return variant;
            }
        }
        for (Cost cost : costs) {
            for (Variant variant : ingredient.variants()) {
                if (cost.cost.ingredient.key().equals(variant.uid())) {
                    return variant;
                }
            }
        }
        return ingredient.variants().isEmpty() ? null : ingredient.variants().get(0);
    }

    private static void drawOutline(GuiGraphics graphics, int x, int y, int width, int height,
                                    int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    private static void drawLine(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        if (x1 == x2) {
            int start = Math.min(y1, y2);
            int end = Math.max(y1, y2);
            for (int y = start; y <= end; y++) {
                graphics.fill(x1, y, x1 + 1, y + 1, color);
            }
            return;
        }
        if (y1 == y2) {
            int start = Math.min(x1, x2);
            int end = Math.max(x1, x2);
            for (int x = start; x <= end; x++) {
                graphics.fill(x, y1, x + 1, y1 + 1, color);
            }
            return;
        }
        int dx = Math.abs(x2 - x1);
        int sx = x1 < x2 ? 1 : -1;
        int dy = -Math.abs(y2 - y1);
        int sy = y1 < y2 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            graphics.fill(x1, y1, x1 + 1, y1 + 1, color);
            if (x1 == x2 && y1 == y2) {
                return;
            }
            int twice = 2 * error;
            if (twice >= dy) {
                error += dy;
                x1 += sx;
            }
            if (twice <= dx) {
                error += dx;
                y1 += sy;
            }
        }
    }

    private Hover getHoveredStack(int mouseX, int mouseY) {
        float scale = getScale();
        int mx = (int) ((mouseX - treeCenterX()) / scale - offX);
        int my = (int) ((mouseY - height / 2.0) / scale - offY);
        for (Cost cost : costs) {
            if (mx >= cost.x && mx < cost.x + 16 && my >= cost.y && my < cost.y + 16) {
                return new Hover(cost.cost.ingredient);
            }
        }
        for (Node node : nodes) {
            Hover hover = node.getHover(mx, my);
            if (hover != null) {
                return hover;
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        CraftCell craftCell = craftCellAt(mouseX, mouseY);
        if (craftCell != null) {
            if (button == 0) {
                CraftEntry entry = craftCell.entry();
                if (entry.recipe() != null) {
                    AbstractContainerScreen<?> container = activeContainerScreen();
                    MaterialTree tree = RecipeTreeState.tree();
                    if (container == null || tree == null) {
                        message("gtlcore.jei.recipe_tree.craft.no_container", ChatFormatting.RED);
                    } else if (RecipeTreeCrafting.isRunning()) {
                        message("gtlcore.jei.recipe_tree.craft.busy", ChatFormatting.RED);
                    } else if (isFinalEntry(entry, tree)) {
                        RecipeTreeCrafting.startChain(container, runtime, tree, Screen.hasShiftDown());
                    } else {
                        RecipeTreeCrafting.startOne(container, runtime, entry.recipe(),
                                entry.ingredient(), Screen.hasShiftDown());
                    }
                } else {
                    showRecipesFor(entry.ingredient());
                }
            }
            return true;
        }
        if (craftGridVisible()) {
            int panelBottom = craftGridTop() + craftGridPanelHeight();
            if (mouseX >= craftGridLeft() &&
                    mouseX < craftGridLeft() + craftGridWidth() &&
                    mouseY >= craftGridTop() && mouseY < panelBottom) {
                return true;
            }
        }
        Hover hover = getHoveredStack((int) mouseX, (int) mouseY);
        float scale = getScale();
        int mx = (int) ((mouseX - treeCenterX()) / scale - offX);
        int my = (int) ((mouseY - height / 2.0) / scale - offY);
        MaterialTree tree = RecipeTreeState.tree();

        if (hover != null) {
            if (button == 1 && hover.node != null && hover.node.recipe != null) {
                if (Screen.hasShiftDown()) {
                    if (tree != null) {
                        tree.addResolution(hover.node.ingredient, null);
                    }
                } else if (!hover.node.recipe.isResolution() && tree != null) {
                    hover.node.state = hover.node.state == FoldState.EXPANDED ?
                            FoldState.COLLAPSED : FoldState.EXPANDED;
                    tree.recalculate();
                }
                recalculateTree();
                return true;
            }
            if (hover.stack != null) {
                if (Screen.hasShiftDown() && button == 0) {
                    if (tree != null && autoResolve(hover.stack)) {
                        recalculateTree();
                    }
                    return true;
                }
                if (button == 0) {
                    openRecipes(hover.node == null ? null : hover.node.recipe, hover.stack);
                    return true;
                }
            }
        } else if (tree != null && mode.contains(mx, my)) {
            RecipeTreeState.setCraftingMode(!RecipeTreeState.craftingMode());
            playClick();
            recalculateTree();
            return true;
        } else if (tree != null && batches.contains(mx, my)) {
            long ideal = tree.cost.getIdealBatch(tree.goal, 1, 1);
            if (ideal != tree.batches) {
                playClick();
                tree.batches = Math.max(1, ideal);
                recalculateTree();
            }
            return true;
        }
        if (button == 0 || button == 2) {
            dragging = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean dragging;

    private boolean autoResolve(TreeIngredient ingredient) {
        MaterialTree tree = RecipeTreeState.tree();
        if (tree == null) {
            return false;
        }
        if (ingredient.variants().size() > 1) {
            Variant target = autoResolveVariant(ingredient);
            if (target == null) {
                return false;
            }
            tree.addResolution(ingredient, TreeRecipe.resolution(runtime, ingredient, target));
            return true;
        }
        TreeRecipe recipe = preferredRecipe(ingredient);
        if (recipe == null) {
            return false;
        }
        tree.addResolution(ingredient, recipe);
        return true;
    }

    private void openRecipes(@Nullable TreeRecipe recipe, TreeIngredient ingredient) {
        if (recipe != null && !recipe.isResolution() &&
                recipe.category() != null && recipe.recipe() != null) {
            showRecipe(recipe);
        } else {
            showRecipesFor(ingredient);
        }
    }

    private void showRecipesFor(TreeIngredient ingredient) {
        ITypedIngredient<?> typed = ingredient.displayTyped();
        if (typed == null) {
            return;
        }
        IFocus<?> focus = runtime.getJeiHelpers().getFocusFactory()
                .createFocus(RecipeIngredientRole.OUTPUT, typed);
        runtime.getRecipesGui().show(List.of(focus));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void showRecipe(TreeRecipe recipe) {
        if (recipe.category() == null || recipe.recipe() == null) {
            return;
        }
        runtime.getRecipesGui().showRecipes((IRecipeCategory) recipe.category(),
                List.of(recipe.recipe()), List.of());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scrollAcc += delta;
        int amount = (int) scrollAcc;
        scrollAcc %= 1;
        float scale = getScale();
        int mx = (int) ((mouseX - treeCenterX()) / scale - offX);
        int my = (int) ((mouseY - height / 2.0) / scale - offY);
        MaterialTree tree = RecipeTreeState.tree();
        if (craftGridVisible()) {
            int totalRows = (craftEntries.size() + CRAFT_COLUMNS - 1) / CRAFT_COLUMNS;
            int panelBottom = craftGridTop() + craftGridPanelHeight();
            if (mouseX >= craftGridLeft() &&
                    mouseX < craftGridLeft() + craftGridWidth() &&
                    mouseY >= craftGridTop() && mouseY < panelBottom) {
                int maxScroll = Math.max(0, totalRows - craftGridRowsVisible());
                craftScroll = Mth.clamp(craftScroll - (int) Math.signum(delta), 0, maxScroll);
                return true;
            }
        }
        if (tree != null && batches.contains(mx, my)) {
            long adjustment = amount;
            if (Screen.hasShiftDown()) {
                adjustment *= 16;
            } else if (Screen.hasControlDown()) {
                if (amount > 0) {
                    adjustment = tree.batches;
                } else {
                    adjustment = -tree.batches / 2;
                }
            }
            if (tree.batches == 1 && adjustment > 1) {
                tree.batches = adjustment;
            } else {
                tree.batches += adjustment;
            }
            tree.batches = Math.max(1, tree.batches);
            recalculateTree();
            return true;
        }
        zoom += amount;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 || button == 2) {
            float scale = getScale();
            offX += dragX / scale;
            offY += dragY / scale;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        CraftCell craftCell = craftCellAt(lastMouseX, lastMouseY);
        if (craftCell != null) {
            CraftEntry entry = craftCell.entry();
            AbstractContainerScreen<?> container = activeContainerScreen();
            MaterialTree tree = RecipeTreeState.tree();
            if (RecipeTreeKeyMappings.matches(RecipeTreeKeyMappings.SHOW_RECIPE, keyCode, scanCode)) {
                showRecipesFor(entry.ingredient());
                return true;
            }
            if (entry.recipe() != null && container != null && tree != null) {
                if (RecipeTreeCrafting.isRunning()) {
                    message("gtlcore.jei.recipe_tree.craft.busy", ChatFormatting.RED);
                    return true;
                }
                if (RecipeTreeKeyMappings.matches(RecipeTreeKeyMappings.CRAFT_ONE_TO_INVENTORY,
                        keyCode, scanCode)) {
                    RecipeTreeCrafting.startOne(container, runtime, entry.recipe(),
                            entry.ingredient(), false);
                    return true;
                }
                if (RecipeTreeKeyMappings.matches(RecipeTreeKeyMappings.CRAFT_ONE_TO_CURSOR,
                        keyCode, scanCode)) {
                    RecipeTreeCrafting.startOne(container, runtime, entry.recipe(),
                            entry.ingredient(), true);
                    return true;
                }
                if (RecipeTreeKeyMappings.matches(RecipeTreeKeyMappings.CRAFT_ALL_TO_INVENTORY,
                        keyCode, scanCode)) {
                    if (isFinalEntry(entry, tree)) {
                        RecipeTreeCrafting.startChain(container, runtime, tree, false);
                    } else {
                        RecipeTreeCrafting.startStep(container, runtime, entry.recipe(),
                                entry.ingredient(), entry.batches(), false);
                    }
                    return true;
                }
                if (RecipeTreeKeyMappings.matches(RecipeTreeKeyMappings.FILL_ONE, keyCode, scanCode) ||
                        RecipeTreeKeyMappings.matches(RecipeTreeKeyMappings.FILL_ALL, keyCode, scanCode)) {
                    boolean all = RecipeTreeKeyMappings.matches(RecipeTreeKeyMappings.FILL_ALL,
                            keyCode, scanCode);
                    fillRecipe(entry.recipe(), all, true);
                    return true;
                }
            }
        }
        if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_R) {
            TreeRecipe recipe = RecipeTreeLookup.randomRecipe(runtime);
            if (recipe != null) {
                RecipeTreeState.setGoal(recipe);
                init();
                return true;
            }
        }
        if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_C) {
            RecipeTreeState.clearTree();
            init();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static boolean isFinalEntry(CraftEntry entry, MaterialTree tree) {
        if (entry.recipe() == null) {
            return false;
        }
        if (entry.recipe().equals(tree.goal.recipe)) {
            return true;
        }
        return entry.ingredient().key().equals(tree.goal.ingredient.key());
    }

    private void addKeyHint(List<Component> lines, net.minecraft.client.KeyMapping mapping,
                            String translationKey) {
        if (!mapping.isUnbound()) {
            lines.add(Component.translatable(translationKey,
                    mapping.getTranslatedKeyMessage().getString())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (RecipeTreeCrafting.isRunning()) {
            if (++craftRefreshTicks % 10 == 0) {
                recalculateTree();
            }
        } else if (craftRefreshTicks > 0) {
            craftRefreshTicks = 0;
            recalculateTree();
        }
    }

    private void playClick() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                    .forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    private @Nullable AbstractContainerScreen<?> activeContainerScreen() {
        if (containerScreen != null) {
            return containerScreen;
        }
        return runtime.getRecipesGui().getParentScreen()
                .filter(AbstractContainerScreen.class::isInstance)
                .map(screen -> (AbstractContainerScreen<?>) screen)
                .orElse(null);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private boolean fillRecipe(@Nullable TreeRecipe recipe, boolean maxTransfer, boolean feedback) {
        Player player = minecraft == null ? null : minecraft.player;
        AbstractContainerScreen<?> screen = activeContainerScreen();
        if (recipe == null || recipe.isResolution() || recipe.category() == null ||
                recipe.recipe() == null || player == null || screen == null) {
            if (feedback) {
                message("gtlcore.jei.recipe_tree.craft.no_container", ChatFormatting.RED);
            }
            return false;
        }
        AbstractContainerMenu menu = screen.getMenu();
        IRecipeCategory<Object> category = recipe.category();
        IRecipeTransferHandler handler = runtime.getRecipeTransferManager()
                .getRecipeTransferHandler(menu, category)
                .orElse(null);
        if (handler == null) {
            if (feedback) {
                message("gtlcore.jei.recipe_tree.craft.unsupported", ChatFormatting.RED);
            }
            return false;
        }
        IRecipeLayoutDrawable<Object> layout = runtime.getRecipeManager()
                .createRecipeLayoutDrawable(category, recipe.recipe(),
                        runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup())
                .orElse(null);
        if (layout == null) {
            if (feedback) {
                message("gtlcore.jei.recipe_tree.craft.unsupported", ChatFormatting.RED);
            }
            return false;
        }
        IRecipeTransferError error = handler.transferRecipe(menu, recipe.recipe(),
                layout.getRecipeSlotsView(), player, maxTransfer, true);
        if (error != null) {
            if (feedback) {
                message("gtlcore.jei.recipe_tree.craft.failed", ChatFormatting.RED);
            }
            return false;
        }
        if (feedback) {
            message("gtlcore.jei.recipe_tree.craft.filled", ChatFormatting.GREEN);
        }
        return true;
    }

    private void message(String translationKey, ChatFormatting color) {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.translatable(translationKey).withStyle(color), true);
        }
    }

    private float getScale() {
        zoom = Mth.clamp(zoom, -6, 4);
        int scale = (int) (minecraft == null ? 2 : minecraft.getWindow().getGuiScale());
        int desired = scale + zoom;
        if (desired < 1) {
            zoom -= desired - 1;
            desired = 1;
        }
        return (float) desired / scale;
    }

    private int amountOverflow(Component amount) {
        int width = font.width(amount);
        return width > 14 ? width - 14 : 0;
    }

    private static Component percent(float chance) {
        return Component.literal(String.format("%.2f%%", chance * 100.0));
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(old);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private class Cost {

        final FlatMaterialCost cost;
        int x;
        final int y;
        long alreadyDone;
        final boolean remainder;

        Cost(FlatMaterialCost cost, int x, int y, boolean remainder) {
            this.cost = cost;
            this.x = x;
            this.y = y;
            this.remainder = remainder;
        }

        void render(GuiGraphics graphics) {
            cost.ingredient.render(graphics, x, y);
            Component text = amountText();
            int tx = x + 17 - Math.min(14, font.width(text));
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(0, 0, 200);
            graphics.drawString(font, text, tx, y + 9, 0xFFFFFFFF, true);
            pose.popPose();
        }

        Component amountText() {
            long adjusted = cost.effectiveAmount();
            Component total;
            if (cost instanceof ChanceMaterialCost) {
                total = TreeIngredient.amountComponent(cost.ingredient, adjusted, true);
            } else {
                total = TreeIngredient.amountComponent(cost.ingredient, adjusted, false);
            }
            if (!remainder && RecipeTreeState.craftingMode()) {
                long amount = alreadyDone;
                if (amount < adjusted) {
                    Component done = Component.literal(AMOUNT_FORMAT.format(amount))
                            .withStyle(ChatFormatting.RED);
                    return Component.empty().append(done).append(Component.literal("/")).append(total);
                }
            }
            return total;
        }
    }

    private class Hover {

        final @Nullable TreeIngredient stack;
        final @Nullable MaterialNode node;
        final @Nullable MaterialNode resolve;
        final @Nullable IRecipeCategory<?> category;

        Hover(TreeIngredient stack) {
            this(stack, null, null, null);
        }

        Hover(IRecipeCategory<?> category, MaterialNode node) {
            this(null, node, null, category);
        }

        Hover(MaterialNode node) {
            this(null, node, null, null);
        }

        Hover(@Nullable TreeIngredient stack,
              @Nullable MaterialNode node,
              @Nullable MaterialNode resolve) {
            this(stack, node, resolve, null);
        }

        Hover(@Nullable TreeIngredient stack,
              @Nullable MaterialNode node,
              @Nullable MaterialNode resolve,
              @Nullable IRecipeCategory<?> category) {
            this.stack = stack;
            this.node = node;
            this.resolve = resolve;
            this.category = category;
        }
    }

    private class Node {

        @Nullable
        Node parent;
        @Nullable
        MaterialNode resolution;
        final MaterialNode node;
        int width;
        int x;
        int y;
        final int midOffset;
        final long amount;
        final ChanceState chance;

        Node(MaterialNode node, long amount, int x, int y, ChanceState chance) {
            this.node = node;
            this.width = node.recipe != null ? 42 : 16;
            this.amount = amount;
            this.x = x;
            this.y = y;
            this.chance = chance;
            int over = amountOverflow(amountText());
            this.width += over;
            this.midOffset = over / -2;
        }

        void render(GuiGraphics graphics, int mouseX, int mouseY) {
            if (parent != null) {
                int color = colorFor(parent.node,
                        node.consumeChance != 1 ||
                                (resolution != null && resolution.consumeChance != 1),
                        false);
                int nx = x;
                int ny = y;
                int px = parent.x;
                int py = parent.y;
                int off = NODE_VERTICAL_SPACING - 1;
                if (resolution != null) {
                    drawResolutionMarker(graphics, x - 3, y - 19);
                    drawLine(graphics, nx, y - 12, nx, ny - 11, color);
                    drawLine(graphics, nx, py + off, nx, y - 19, color);
                } else {
                    drawLine(graphics, nx, ny - 11, nx, py + off, color);
                }
                drawLine(graphics, px, py + off, nx, py + off,
                        colorFor(parent.node, false, false));
            } else if (resolution != null) {
                drawResolutionMarker(graphics, x - 3, y - 19);
            }
            int xo = 0;
            if (node.recipe != null) {
                int lx = x - width / 2;
                int ly = y - 11;
                int hx = x + width / 2;
                int hy = y + 10;
                int color = colorFor(node, node.produceChance != 1, false);
                if (node.state != FoldState.EXPANDED) {
                    drawLine(graphics, x, hy + 1, x, hy + 3, color);
                } else {
                    drawLine(graphics, x, hy + 1, x, hy + 8, color);
                }

                boolean hovered = mouseX >= lx && mouseY >= ly && mouseX <= hx && mouseY <= hy;
                color = colorFor(node, node.produceChance != 1, hovered);
                drawOutline(graphics, lx, ly, hx - lx + 1, hy - ly + 1, color);

                IRecipeCategory<?> category = node.recipe.category();
                if (category != null && category.getIcon() != null) {
                    IDrawable icon = category.getIcon();
                    icon.draw(graphics, x - 18 + midOffset, y - 8);
                }
                xo = 11;
            } else {
                int panelX = x - 10 + midOffset;
                boolean hovered = mouseX >= panelX - 3 && mouseX <= panelX + 23 &&
                        mouseY >= y - 13 && mouseY <= y + 11;
                drawOutline(graphics, panelX, y - 10, 20, 20,
                        colorFor(node, false, hovered));
            }
            node.ingredient.render(graphics, x + xo - 8 + midOffset, y - 8);
            Component text = amountText();
            int tx = x + xo - 8 + midOffset + 17 - Math.min(14, font.width(text));
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(0, 0, 200);
            graphics.drawString(font, text, tx, y - 8 + 9, 0xFFFFFFFF, true);
            pose.popPose();
        }

        private void drawResolutionMarker(GuiGraphics graphics, int x, int y) {
            int color = 0xFF7FD1AE;
            drawOutline(graphics, x, y, 7, 7, color);
            graphics.fill(x + 2, y + 2, x + 5, y + 3, color);
            graphics.fill(x + 2, y + 4, x + 5, y + 5, color);
        }

        private int colorFor(MaterialNode node, boolean chanced, boolean hovered) {
            int color = 0xFF55708D;
            if (chanced) {
                color = 0xFFCC9919;
            }
            if (RecipeTreeState.craftingMode()) {
                if (node.progress == ProgressState.COMPLETED) {
                    color = 0xFF1ACC80;
                } else if (node.progress == ProgressState.PARTIAL) {
                    color = 0xFFCC33E6;
                }
            }
            if (hovered) {
                color = 0xFFFFD36A;
            }
            return color;
        }

        Component amountText() {
            if (chance.chanced()) {
                long adjusted = Math.round(amount * chance.chance());
                adjusted = Math.max(adjusted, node.amount);
                return TreeIngredient.amountComponent(node.ingredient, adjusted, true);
            }
            return TreeIngredient.amountComponent(node.ingredient, amount, false);
        }

        @Nullable
        Hover getHover(int mouseX, int mouseY) {
            if (resolution != null &&
                    mouseX >= x - 4 && mouseX < x + 4 &&
                    mouseY >= y - 19 && mouseY < y - 11) {
                return new Hover(resolution.ingredient, resolution, null);
            }
            int ingredientMouseX = mouseX;
            if (node.recipe != null) {
                if (mouseX >= x - 18 + midOffset && mouseX < x - 2 + midOffset &&
                        mouseY >= y - 8 && mouseY < y + 8) {
                    return new Hover(node.recipe.category(), node);
                }
                ingredientMouseX -= 11;
            }
            if (ingredientMouseX >= x - 8 + midOffset && ingredientMouseX < x + 8 + midOffset &&
                    mouseY >= y - 8 && mouseY < y + 8) {
                return new Hover(node.ingredient, node, resolution, null);
            }
            int lx = x - width / 2;
            int ly = y - 11;
            int hx = x + width / 2;
            int hy = y + 10;
            if (mouseX >= lx && mouseY >= ly && mouseX <= hx && mouseY <= hy) {
                return new Hover(node);
            }
            return null;
        }
    }

    private class TreeVolume {

        final List<Width> widths = new ArrayList<>();
        final List<Node> nodes = new ArrayList<>();

        TreeVolume(MaterialNode node, long amount, int y, ChanceState chance) {
            Node head = new Node(node, amount, 0, y, chance);
            int left = head.width / 2;
            widths.add(new Width(-left, head.width - left));
            nodes.add(head);
        }

        void addHead(MaterialNode node, long amount, int y, ChanceState chance) {
            int x = (getLeft(0) + getRight(0)) / 2;
            Node newNode = new Node(node, amount, x, y, chance);
            for (Node nodeInVolume : nodes) {
                if (nodeInVolume.parent == null) {
                    nodeInVolume.parent = newNode;
                }
                nodeInVolume.y += NODE_VERTICAL_SPACING;
            }
            int left = newNode.width / 2;
            widths.add(0, new Width(x - left, x + newNode.width - left));
            nodes.add(0, newNode);
        }

        int getDepth() {
            return widths.size();
        }

        int minLeft() {
            int minimum = getLeft(0);
            for (int i = 1; i < getDepth(); i++) {
                minimum = Math.min(minimum, getLeft(i));
            }
            return minimum;
        }

        int maxRight() {
            int maximum = getRight(0);
            for (int i = 1; i < getDepth(); i++) {
                maximum = Math.max(maximum, getRight(i));
            }
            return maximum;
        }

        int getLeft(int depth) {
            return widths.get(depth).left;
        }

        int getRight(int depth) {
            return widths.get(depth).right;
        }

        void addToRight(TreeVolume other) {
            int rightOffset = getRight(0) - other.getLeft(0) + NODE_HORIZONTAL_SPACING;
            for (int i = 1; i < getDepth() && i < other.getDepth(); i++) {
                rightOffset = Math.max(rightOffset,
                        getRight(i) - other.getLeft(i) + NODE_HORIZONTAL_SPACING);
            }
            for (int i = 0; i < other.getDepth(); i++) {
                if (i < getDepth()) {
                    widths.get(i).right = other.getRight(i) + rightOffset;
                } else {
                    widths.add(new Width(other.getLeft(i) + rightOffset,
                            other.getRight(i) + rightOffset));
                }
            }
            for (Node node : other.nodes) {
                node.x += rightOffset;
                nodes.add(node);
            }
        }

        private static class Width {

            int left;
            int right;

            Width(int left, int right) {
                this.left = left;
                this.right = right;
            }
        }
    }

    private record CraftEntry(@Nullable TreeRecipe recipe,
                              TreeIngredient ingredient,
                              long amount,
                              long total,
                              long batches,
                              int state) {

        static CraftEntry ofRecipe(TreeRecipe recipe,
                                   TreeIngredient ingredient,
                                   long amount,
                                   long total,
                                   long batches,
                                   int state) {
            return new CraftEntry(recipe, ingredient, amount, total, batches, state);
        }

        static CraftEntry ofMaterial(TreeIngredient ingredient, long amount, long total) {
            return new CraftEntry(null, ingredient, amount, total, 0, -1);
        }
    }

    private record CraftCell(CraftEntry entry, int x, int y, int width, int height) {

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
        }
    }

    record Bounds(int x, int y, int width, int height) {

        boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
        }
    }
}
