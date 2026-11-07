package org.gtlcore.gtlcore.integration.jei.tree;

import org.gtlcore.gtlcore.integration.jei.tree.JeiRecipeTreeModel.CostEntry;
import org.gtlcore.gtlcore.integration.jei.tree.JeiRecipeTreeModel.CostSummary;
import org.gtlcore.gtlcore.integration.jei.tree.JeiRecipeTreeModel.IngredientStack;
import org.gtlcore.gtlcore.integration.jei.tree.JeiRecipeTreeModel.RecipeNode;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Full-screen recipe tree inspired by EMI's BoM screen, implemented without replacing JEI's GUI.
 */
final class JeiRecipeTreeScreen extends Screen {

    private static final int COST_PANEL_HEIGHT = 158;
    private static final int TREE_TOP = 38;
    private static final int NODE_SPACING_X = 38;
    private static final int NODE_SPACING_Y = 48;
    private static final int NODE_RADIUS = 11;

    private final Screen parent;
    private final IJeiRuntime runtime;
    private final JeiRecipeTreeModel model;
    private final Map<String, Integer> selections = new HashMap<>();
    private final Set<String> collapsed = new HashSet<>();

    private RecipeNode root;
    private CostSummary costs;
    private List<LayoutNode> layoutNodes = List.of();
    private List<CostCell> costCells = List.of();
    private long batches = 1;
    private double panX;
    private double panY = 14;
    private float zoom = 1.0F;
    private boolean dragging;
    private boolean initialized;
    private int costScroll;
    private @Nullable CandidatePopup popup;

    JeiRecipeTreeScreen(@Nullable Screen parent, IJeiRuntime runtime, IRecipeLayoutDrawable<?> layout) {
        super(Component.translatable("gtlcore.jei.recipe_tree.title"));
        this.parent = parent;
        this.runtime = runtime;
        this.model = new JeiRecipeTreeModel(runtime, layout, selections, collapsed);
        rebuild();
    }

    @Override
    protected void init() {
        super.init();
        if (!initialized) {
            initialized = true;
            recenter();
        }
    }

    private void rebuild() {
        root = model.build(batches);
        costs = model.summarize(root);
        layoutNodes = layoutTree(root);
    }

    private void recenter() {
        panX = 0;
        panY = 14;
        if (layoutNodes.isEmpty()) {
            zoom = 1.0F;
            return;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = 0;
        for (LayoutNode node : layoutNodes) {
            minX = Math.min(minX, node.x);
            maxX = Math.max(maxX, node.x);
            maxY = Math.max(maxY, node.y);
        }
        int viewportHeight = Math.max(40, getCostTop() - TREE_TOP);
        float widthFit = Math.max(1, width - 80) / (float) Math.max(1, maxX - minX + 40);
        float heightFit = Math.max(1, viewportHeight - 32) / (float) Math.max(1, maxY + 40);
        zoom = Mth.clamp(Math.min(1.0F, Math.min(widthFit, heightFit)), 0.42F, 1.0F);
    }

    private int getCostTop() {
        return Math.max(TREE_TOP + 80, height - Math.min(COST_PANEL_HEIGHT, Math.max(118, height / 3)));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(0, 0, width, height, 0xE20B0F15);

        drawHeader(graphics, mouseX, mouseY);
        drawTree(graphics, mouseX, mouseY, partialTick);
        drawCostPanel(graphics, mouseX, mouseY);
        if (popup != null) {
            drawPopup(graphics, mouseX, mouseY);
        }
        drawTooltip(graphics, mouseX, mouseY);
    }

    private void drawHeader(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 12, 9, 0xFFF3F5F7, false);
        graphics.drawString(font, Component.translatable("gtlcore.jei.recipe_tree.subtitle"),
                12, 22, 0xFF8E99A8, false);

        ButtonBounds reset = resetButton();
        drawButton(graphics, reset, mouseX, mouseY,
                Component.translatable("gtlcore.jei.recipe_tree.reset"));
        ButtonBounds minus = batchMinusButton();
        ButtonBounds plus = batchPlusButton();
        drawButton(graphics, minus, mouseX, mouseY, Component.literal("-"));
        drawButton(graphics, plus, mouseX, mouseY, Component.literal("+"));
        String batchText = Component.translatable("gtlcore.jei.recipe_tree.batches", batches).getString();
        graphics.drawString(font, batchText,
                (minus.x() + minus.width() + plus.x()) / 2 - font.width(batchText) / 2,
                12, 0xFFFFD36A, false);
    }

    private void drawButton(GuiGraphics graphics, ButtonBounds bounds, int mouseX, int mouseY,
                            Component label) {
        boolean hovered = bounds.contains(mouseX, mouseY);
        int fill = hovered ? 0xFF344253 : 0xFF202A36;
        int border = hovered ? 0xFF7BB9E8 : 0xFF4A5B70;
        drawPanel(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), fill, border);
        graphics.drawString(font, label,
                bounds.x() + (bounds.width() - font.width(label)) / 2,
                bounds.y() + (bounds.height() - font.lineHeight) / 2 + 1,
                0xFFE7EDF5, false);
    }

    private void drawTree(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int bottom = getCostTop() - 5;
        graphics.enableScissor(0, TREE_TOP, width, bottom);
        graphics.fill(0, TREE_TOP, width, bottom, 0x241F2935);

        graphics.pose().pushPose();
        graphics.pose().translate(width / 2.0 + panX, TREE_TOP + panY, 0);
        graphics.pose().scale(zoom, zoom, 1.0F);

        for (LayoutNode node : layoutNodes) {
            for (LayoutNode child : node.children) {
                drawEdge(graphics, node, child);
            }
        }

        LayoutNode hovered = nodeAt(mouseX, mouseY);
        for (LayoutNode node : layoutNodes) {
            drawNode(graphics, node, hovered == node);
        }
        graphics.pose().popPose();
        graphics.disableScissor();
    }

    private void drawEdge(GuiGraphics graphics, LayoutNode parent, LayoutNode child) {
        int color = child.node.cycle ? 0xFFB65353 : 0xFF55708D;
        int startY = parent.y + NODE_RADIUS;
        int endY = child.y - NODE_RADIUS;
        int middleY = (startY + endY) / 2;
        graphics.fill(parent.x, startY, parent.x + 1, middleY + 1, color);
        graphics.fill(Math.min(parent.x, child.x), middleY, Math.max(parent.x, child.x) + 1,
                middleY + 1, color);
        graphics.fill(child.x, middleY, child.x + 1, endY + 1, color);
    }

    private void drawNode(GuiGraphics graphics, LayoutNode layout, boolean hovered) {
        RecipeNode node = layout.node;
        int left = layout.x - NODE_RADIUS;
        int top = layout.y - NODE_RADIUS;
        int right = layout.x + NODE_RADIUS;
        int bottom = layout.y + NODE_RADIUS;
        int border = hovered ? 0xFFFFD36A : nodeBorder(node);
        int fill = hovered ? 0xFF2C3B4C : 0xFF18212C;
        drawPanel(graphics, left, top, NODE_RADIUS * 2, NODE_RADIUS * 2, fill, border);
        node.ingredient.render(graphics, left + 3, top + 3);

        if (node.recipe != null) {
            graphics.fill(right - 4, top + 2, right - 2, top + 4, 0xFF69D79B);
        }
        if (node.collapsed && !node.children.isEmpty()) {
            graphics.fill(layout.x - 3, bottom - 4, layout.x + 3, bottom - 2, 0xFFFFD36A);
        }

        String amount = (node.ingredient.chance() < 1.0 ? "≈" : "") +
                compact(node.ingredient.amount());
        graphics.drawString(font, amount, right + 3, top + 2, 0xFFE8EDF4, false);
        if (node.crafts > 1) {
            Component crafts = Component.translatable("gtlcore.jei.recipe_tree.crafts", node.crafts)
                    .withStyle(ChatFormatting.AQUA);
            graphics.drawString(font, crafts, right + 3, top + 12, 0xFF8BD7E8, false);
        }
    }

    private int nodeBorder(RecipeNode node) {
        if (node.depth == 0) {
            return 0xFFFFC857;
        }
        if (node.cycle || node.budgetExceeded) {
            return 0xFFC85A5A;
        }
        if (node.recipe != null) {
            return 0xFF5FB78A;
        }
        return 0xFF708AA8;
    }

    private void drawCostPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int top = getCostTop();
        drawPanel(graphics, 6, top, width - 12, height - top - 6, 0xF01A222D, 0xFF41556B);
        Component heading = Component.translatable("gtlcore.jei.recipe_tree.total_cost");
        graphics.drawString(font, heading, 14, top + 8, 0xFFFFD36A, false);
        Component summary = Component.translatable("gtlcore.jei.recipe_tree.summary",
                costs.entries().size(), costs.nodeCount(), root.recipe != null ? 1 : 0);
        graphics.drawString(font, summary, 14 + font.width(heading) + 10, top + 8, 0xFF94A5B8, false);

        costCells = buildCostCells(top + 24);
        for (CostCell cell : costCells) {
            boolean hovered = cell.contains(mouseX, mouseY);
            if (hovered) {
                graphics.fill(cell.x() - 2, cell.y() - 2, cell.right() + 2, cell.bottom() + 2,
                        0x664E769B);
            }
            cell.entry().ingredient().render(graphics, cell.x(), cell.y() + 2);
            graphics.drawString(font, compact(cell.entry().amount()), cell.x() + 18,
                    cell.y() + 6, 0xFFDCE5EF, false);
        }

        int columns = costColumns();
        int rows = (costs.entries().size() + columns - 1) / columns;
        int visibleRows = Math.max(1, (height - (top + 24) - 8) / 22);
        if (rows > visibleRows) {
            String scroll = Component.translatable("gtlcore.jei.recipe_tree.scroll", costScroll + 1, rows)
                    .getString();
            graphics.drawString(font, scroll, width - font.width(scroll) - 14, top + 8,
                    0xFF77899D, false);
        }
    }

    private List<CostCell> buildCostCells(int startY) {
        int columns = costColumns();
        int visibleRows = Math.max(1, (height - startY - 8) / 22);
        int maxScroll = Math.max(0, (costs.entries().size() + columns - 1) / columns - visibleRows);
        costScroll = Mth.clamp(costScroll, 0, maxScroll);
        int start = costScroll * columns;
        List<CostCell> cells = new ArrayList<>(Math.min(costs.entries().size(), columns * visibleRows));
        for (int index = start; index < costs.entries().size(); index++) {
            int relative = index - start;
            int column = relative % columns;
            int row = relative / columns;
            if (row >= visibleRows) {
                break;
            }
            int cellX = 14 + column * 82;
            int cellY = startY + row * 22;
            cells.add(new CostCell(costs.entries().get(index), cellX, cellY, 78, 20));
        }
        return cells;
    }

    private int costColumns() {
        return Math.max(1, (width - 28) / 82);
    }

    private void drawPopup(GuiGraphics graphics, int mouseX, int mouseY) {
        CandidatePopup current = popup;
        if (current == null) {
            return;
        }
        PopupBounds bounds = popupBounds(current);
        drawPanel(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                0xFF151D27, 0xFFFFD36A);
        graphics.drawString(font, Component.translatable("gtlcore.jei.recipe_tree.choose"),
                bounds.x() + 7, bounds.y() + 5, 0xFFFFD36A, false);

        int selected = selections.getOrDefault(current.node().ingredient.key(), 0);
        int rowY = bounds.y() + 18;
        boolean autoHovered = mouseX >= bounds.x() && mouseX < bounds.right() &&
                mouseY >= rowY && mouseY < rowY + 14;
        if (autoHovered) {
            graphics.fill(bounds.x() + 2, rowY, bounds.right() - 2, rowY + 14, 0x554E769B);
        }
        String auto = Component.translatable("gtlcore.jei.recipe_tree.auto").getString();
        graphics.drawString(font, auto, bounds.x() + 7, rowY + 3,
                selections.containsKey(current.node().ingredient.key()) ? 0xFFC8D1DB : 0xFF7DE0A8, false);
        rowY += 14;

        for (int index = 0; index < current.node().alternatives.size(); index++) {
            boolean hovered = mouseX >= bounds.x() && mouseX < bounds.right() &&
                    mouseY >= rowY && mouseY < rowY + 14;
            if (hovered) {
                graphics.fill(bounds.x() + 2, rowY, bounds.right() - 2, rowY + 14, 0x554E769B);
            }
            String label = font.plainSubstrByWidth(
                    current.node().alternatives.get(index).label(index), bounds.width() - 18);
            int color = selected == index ? 0xFFFFD36A : 0xFFDCE5EF;
            graphics.drawString(font, label, bounds.x() + 7, rowY + 3, color, false);
            rowY += 14;
        }
    }

    private PopupBounds popupBounds(CandidatePopup current) {
        int popupWidth = Math.min(286, Math.max(180, width - 16));
        int popupHeight = 18 + 14 * (current.node().alternatives.size() + 1) + 4;
        int popupX = Mth.clamp(current.anchorX(), 6, Math.max(6, width - popupWidth - 6));
        int popupY = Mth.clamp(current.anchorY(), TREE_TOP, Math.max(TREE_TOP, height - popupHeight - 6));
        return new PopupBounds(popupX, popupY, popupWidth, popupHeight);
    }

    private void drawTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (popup != null) {
            return;
        }

        LayoutNode node = nodeAt(mouseX, mouseY);
        if (node != null) {
            List<Component> lines = new ArrayList<>(node.node.ingredient.tooltip());
            if (lines.isEmpty()) {
                lines.add(Component.literal(node.node.ingredient.name()));
            }
            if (!node.node.recipeLabel.isBlank()) {
                lines.add(Component.literal(node.node.recipeLabel).withStyle(ChatFormatting.GREEN));
            }
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.node.amount",
                    node.node.ingredient.amount(), node.node.crafts).withStyle(ChatFormatting.GRAY));
            if (node.node.ingredient.chance() < 1.0) {
                lines.add(Component.translatable("gtlcore.jei.recipe_tree.chance",
                        Math.round(node.node.ingredient.chance() * 10000.0) / 100.0)
                        .withStyle(ChatFormatting.GOLD));
            }
            if (node.node.cycle) {
                lines.add(Component.translatable("gtlcore.jei.recipe_tree.cycle")
                        .withStyle(ChatFormatting.RED));
            } else if (node.node.depthLimited || node.node.budgetExceeded) {
                lines.add(Component.translatable("gtlcore.jei.recipe_tree.truncated")
                        .withStyle(ChatFormatting.RED));
            }
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.node.left")
                    .withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.node.right")
                    .withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.translatable("gtlcore.jei.recipe_tree.node.show")
                    .withStyle(ChatFormatting.DARK_GRAY));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }

        for (CostCell cell : costCells) {
            if (cell.contains(mouseX, mouseY)) {
                List<Component> lines = new ArrayList<>(cell.entry().ingredient().tooltip());
                if (lines.isEmpty()) {
                    lines.add(Component.literal(cell.entry().ingredient().name()));
                }
                String marker = cell.entry().ingredient().chance() < 1.0 ? "≈" : "";
                lines.add(Component.translatable("gtlcore.jei.recipe_tree.cost.total",
                        marker + cell.entry().amount()).withStyle(ChatFormatting.GRAY));
                lines.add(Component.translatable("gtlcore.jei.recipe_tree.cost.per_batch",
                        marker + divideCeiling(cell.entry().amount(), batches))
                        .withStyle(ChatFormatting.GOLD));
                lines.add(Component.translatable("gtlcore.jei.recipe_tree.cost.show")
                        .withStyle(ChatFormatting.DARK_GRAY));
                graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
                return;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (popup != null) {
            if (handlePopupClick(mouseX, mouseY)) {
                return true;
            }
            popup = null;
        }

        if (resetButton().contains(mouseX, mouseY)) {
            selections.clear();
            collapsed.clear();
            rebuild();
            recenter();
            return true;
        }
        if (batchMinusButton().contains(mouseX, mouseY)) {
            adjustBatches(Screen.hasShiftDown() ? -16 : -1);
            return true;
        }
        if (batchPlusButton().contains(mouseX, mouseY)) {
            adjustBatches(Screen.hasShiftDown() ? 16 : 1);
            return true;
        }

        for (CostCell cell : costCells) {
            if (cell.contains(mouseX, mouseY)) {
                showRecipes(cell.entry().ingredient());
                return true;
            }
        }

        if (mouseY >= TREE_TOP && mouseY < getCostTop()) {
            LayoutNode node = nodeAt(mouseX, mouseY);
            if (node != null) {
                if (button == 1) {
                    if (Screen.hasShiftDown()) {
                        selections.remove(node.node.ingredient.key());
                        rebuild();
                    } else if (!node.node.children.isEmpty()) {
                        if (!collapsed.remove(node.node.path)) {
                            collapsed.add(node.node.path);
                        }
                        rebuild();
                    }
                } else if (button == 0) {
                    if (Screen.hasControlDown()) {
                        showRecipes(node.node.ingredient);
                    } else if (Screen.hasShiftDown()) {
                        selections.remove(node.node.ingredient.key());
                        rebuild();
                    } else {
                        popup = new CandidatePopup(node.node, (int) mouseX + 14, (int) mouseY + 8);
                    }
                }
                return true;
            }
            if (button == 0 || button == 2) {
                dragging = true;
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean handlePopupClick(double mouseX, double mouseY) {
        CandidatePopup current = popup;
        if (current == null) {
            return false;
        }
        PopupBounds bounds = popupBounds(current);
        if (!bounds.contains(mouseX, mouseY)) {
            return false;
        }
        int row = (int) ((mouseY - bounds.y() - 18) / 14);
        if (row < 0) {
            return true;
        }
        if (row == 0) {
            selections.remove(current.node().ingredient.key());
        } else if (row <= current.node().alternatives.size()) {
            selections.put(current.node().ingredient.key(), row - 1);
        }
        popup = null;
        rebuild();
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && (button == 0 || button == 2)) {
            panX += dragX;
            panY += dragY;
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
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (popup != null) {
            return true;
        }
        if (mouseY >= getCostTop()) {
            int columns = costColumns();
            int visibleRows = Math.max(1, (height - getCostTop() - 8) / 22);
            int maxScroll = Math.max(0, (costs.entries().size() + columns - 1) / columns - visibleRows);
            costScroll = Mth.clamp(costScroll - (int) Math.signum(delta), 0, maxScroll);
            return true;
        }
        if (mouseY >= TREE_TOP && mouseY < getCostTop()) {
            double worldX = (mouseX - width / 2.0 - panX) / zoom;
            double worldY = (mouseY - TREE_TOP - panY) / zoom;
            float oldZoom = zoom;
            zoom = Mth.clamp(zoom * (delta > 0 ? 1.12F : 0.89F), 0.35F, 2.8F);
            if (zoom != oldZoom) {
                panX = mouseX - width / 2.0 - worldX * zoom;
                panY = mouseY - TREE_TOP - worldY * zoom;
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_HOME) {
            recenter();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_R && Screen.hasControlDown()) {
            showRecipes(root.ingredient);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_EQUAL) {
            adjustBatches(Screen.hasShiftDown() ? 16 : 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_MINUS) {
            adjustBatches(Screen.hasShiftDown() ? -16 : -1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void adjustBatches(long delta) {
        batches = Math.max(1L, Math.min(1_000_000L, batches + delta));
        popup = null;
        rebuild();
    }

    private void showRecipes(IngredientStack ingredient) {
        if (ingredient.typed() == null) {
            return;
        }
        popup = null;
        showRecipesTyped(ingredient.typed());
    }

    private <T> void showRecipesTyped(ITypedIngredient<T> typed) {
        IFocus<T> focus = runtime.getJeiHelpers().getFocusFactory()
                .createFocus(RecipeIngredientRole.OUTPUT, typed);
        runtime.getRecipesGui().show(List.of(focus));
    }

    private LayoutNode nodeAt(double mouseX, double mouseY) {
        if (mouseY < TREE_TOP || mouseY >= getCostTop()) {
            return null;
        }
        double worldX = (mouseX - width / 2.0 - panX) / zoom;
        double worldY = (mouseY - TREE_TOP - panY) / zoom;
        double radius = NODE_RADIUS + 7.0 / zoom;
        for (LayoutNode node : layoutNodes) {
            if (worldX >= node.x - radius && worldX <= node.x + radius &&
                    worldY >= node.y - radius && worldY <= node.y + radius) {
                return node;
            }
        }
        return null;
    }

    private ButtonBounds resetButton() {
        int buttonWidth = Math.max(64, font.width(
                Component.translatable("gtlcore.jei.recipe_tree.reset")) + 12);
        return new ButtonBounds(width - buttonWidth - 12, 7, buttonWidth, 16);
    }

    private ButtonBounds batchMinusButton() {
        ButtonBounds plus = batchPlusButton();
        return new ButtonBounds(plus.x() - 68, plus.y(), plus.width(), plus.height());
    }

    private ButtonBounds batchPlusButton() {
        ButtonBounds reset = resetButton();
        return new ButtonBounds(reset.x() - 22, reset.y(), reset.width(), reset.height());
    }

    private List<LayoutNode> layoutTree(RecipeNode tree) {
        List<LayoutNode> nodes = new ArrayList<>();
        LayoutNode rootLayout = placeTree(tree, 0, 0, nodes);
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        for (LayoutNode node : nodes) {
            minX = Math.min(minX, node.x);
            maxX = Math.max(maxX, node.x);
        }
        int center = (minX + maxX) / 2;
        for (LayoutNode node : nodes) {
            node.x -= center;
        }
        return List.copyOf(nodes);
    }

    private LayoutNode placeTree(RecipeNode node, int depth, int startX, List<LayoutNode> nodes) {
        LayoutNode layout = new LayoutNode(node, depth * NODE_SPACING_Y);
        nodes.add(layout);
        if (!node.children.isEmpty() && !node.collapsed) {
            int nextX = startX;
            for (RecipeNode child : node.children) {
                LayoutNode childLayout = placeTree(child, depth + 1, nextX, nodes);
                layout.children.add(childLayout);
                nextX = childLayout.rightEdge + NODE_SPACING_X;
            }
            layout.x = (layout.children.get(0).x + layout.children.get(layout.children.size() - 1).x) / 2;
            layout.rightEdge = Math.max(nextX - NODE_SPACING_X, layout.x + NODE_RADIUS);
        } else {
            layout.x = startX;
            layout.rightEdge = startX + NODE_RADIUS;
        }
        return layout;
    }

    private static void drawPanel(GuiGraphics graphics, int x, int y, int width, int height,
                                  int fill, int border) {
        graphics.fill(x, y, x + width, y + height, fill);
        graphics.fill(x, y, x + width, y + 1, border);
        graphics.fill(x, y + height - 1, x + width, y + height, border);
        graphics.fill(x, y, x + 1, y + height, border);
        graphics.fill(x + width - 1, y, x + width, y + height, border);
    }

    private static String compact(long amount) {
        if (amount >= 1_000_000_000_000L) {
            return String.format("%.2fT", amount / 1_000_000_000_000.0);
        }
        if (amount >= 1_000_000_000L) {
            return String.format("%.2fB", amount / 1_000_000_000.0);
        }
        if (amount >= 1_000_000L) {
            return String.format("%.2fM", amount / 1_000_000.0);
        }
        return Long.toString(amount);
    }

    private static long divideCeiling(long amount, long divisor) {
        return amount / divisor + (amount % divisor == 0 ? 0 : 1);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    private record ButtonBounds(int x, int y, int width, int height) {

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    private record PopupBounds(int x, int y, int width, int height) {

        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < right() && mouseY >= y && mouseY < bottom();
        }
    }

    private record CandidatePopup(RecipeNode node, int anchorX, int anchorY) {}

    private record CostCell(CostEntry entry, int x, int y, int width, int height) {

        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < right() && mouseY >= y && mouseY < bottom();
        }
    }

    private static final class LayoutNode {

        private final RecipeNode node;
        private final List<LayoutNode> children = new ArrayList<>();
        private int x;
        private final int y;
        private int rightEdge;

        private LayoutNode(RecipeNode node, int y) {
            this.node = node;
            this.y = y;
        }
    }
}
