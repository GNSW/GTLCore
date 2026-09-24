package org.gtlcore.gtlcore.client.ae2.graph;

import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.gtlcore.gtlcore.integration.ae2.graph.core.*;
import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAePackets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.AESubScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.me.crafting.CraftConfirmScreen;
import appeng.menu.me.crafting.CraftConfirmMenu;
import com.mojang.math.Axis;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Paged read-only selected plan. Opening or navigating this screen never submits a job. */
public final class CraftingRingScreen extends AESubScreen<CraftConfirmMenu, CraftConfirmScreen> {

    private final UUID planId;
    private final List<GraphRingView.Row> rows = new ArrayList<>();
    private GenericStack target;
    private int total = -1, requested = -1, mode, page, nodePage;
    private long lastRequest;
    private CompletableFuture<Model> loading;
    private Model model;
    private String error;
    private final List<Hit> hits = new ArrayList<>();
    private final Button[] tabs = new Button[4];
    private final Button previous, next, reset;
    private static final int INK = 0xFF40404C, MUTED = 0xFF606070, ACCENT = 0xFF625586;
    private static final int VIEW_X = 12, VIEW_Y = 73, VIEW_W = 336, VIEW_H = 155;
    private double zoom = 1, panX, panY;
    private boolean dragging;
    private int hoveredNode = -1;

    private record Hit(int x, int y, int width, int height, List<Component> tooltip) {}

    private record Model(PlanTopology<AEKey> topology, List<PlanTopology.Group> rings,
                         List<GraphRingView.Row> resources, List<GraphRingView.Row> recipes,
                         List<GraphRingView.Row> steps, Map<String, GraphRingView.Row> byRecipe,
                         Map<AEKey, GraphRingView.Row> byResource, PlanGraphLayout<AEKey> layout) {}

    public CraftingRingScreen(CraftConfirmScreen parent) {
        super(parent, "/screens/gtl_crafting_ring.json");
        planId = ((GraphPlanSummaryView) parent.getMenu().getPlan()).gtlcore$graphPlanId();
        widgets.addButton("back", Component.translatable("gui.back"), this::returnToParent);
        tabs[0] = widgets.addButton("overview", text("overview"), () -> select(0));
        tabs[1] = widgets.addButton("rings", text("rings"), () -> select(1));
        tabs[2] = widgets.addButton("recipes", text("recipes"), () -> select(2));
        tabs[3] = widgets.addButton("steps", text("steps"), () -> select(3));
        previous = widgets.addButton("previous", Component.literal("<"), () -> turn(-1));
        next = widgets.addButton("next", Component.literal(">"), () -> turn(1));
        reset = widgets.addButton("reset", text("reset"), this::resetView);
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("gtlcore.ae.ring." + key, args);
    }

    private void select(int selected) {
        mode = selected;
        page = 0;
        nodePage = 0;
        resetView();
    }

    private void turn(int direction) {
        page = Math.max(0, Math.min(page + direction, pageCount() - 1));
        nodePage = 0;
        resetView();
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        for (int i = 0; i < tabs.length; i++) tabs[i].active = i != mode;
        previous.active = model != null && page > 0;
        next.active = model != null && page + 1 < pageCount();
        reset.visible = mode == 1;
        reset.active = model != null && mode == 1;
        if (planId == null || error != null) return;
        if (total < 0 || rows.size() < total) {
            long now = System.nanoTime();
            if (requested != rows.size() || now - lastRequest > 2_000_000_000L) {
                requested = rows.size();
                lastRequest = now;
                WirelessAePackets.CHANNEL.sendToServer(new GraphRingPackets.Request(menu.containerId, planId, requested));
            }
        } else if (loading == null) {
            List<GraphRingView.Row> snapshot = List.copyOf(rows);
            loading = CompletableFuture.supplyAsync(() -> build(snapshot));
        } else if (model == null && loading.isDone()) {
            try {
                model = loading.join();
                mode = 1;
                resetView();
            } catch (RuntimeException e) {
                error = e.getClass().getSimpleName();
            }
        }
    }

    public static void receive(GraphRingPackets.Response response) {
        if (Minecraft.getInstance().screen instanceof CraftingRingScreen screen && screen.menu.containerId == response.container() &&
                response.page().id().equals(screen.planId)) {
            var data = response.page();
            if (data.offset() != screen.rows.size()) return;
            if (screen.total >= 0 && data.total() != screen.total) {
                screen.error = "VIEW_CHANGED";
                return;
            }
            screen.target = data.target();
            screen.total = data.total();
            screen.rows.addAll(data.rows());
        }
    }

    public static void fail(GraphRingPackets.Failure failure) {
        if (Minecraft.getInstance().screen instanceof CraftingRingScreen screen && screen.menu.containerId == failure.container() &&
                failure.plan().equals(screen.planId))
            screen.error = failure.reason();
    }

    private static Model build(List<GraphRingView.Row> rows) {
        List<GraphRingView.Row> resources = new ArrayList<>(), recipes = new ArrayList<>(), steps = new ArrayList<>();
        List<GraphRecipe<AEKey>> selected = new ArrayList<>();
        Map<String, GraphRingView.Row> byRecipe = new LinkedHashMap<>();
        Map<AEKey, GraphRingView.Row> byResource = new LinkedHashMap<>();
        for (var row : rows) switch (row.kind()) {
            case RESOURCE -> {
                resources.add(row);
                byResource.put(row.icon().what(), row);
            }
            case RECIPE -> {
                recipes.add(row);
                byRecipe.put(row.id(), row);
                Map<AEKey, Long> outputs = new LinkedHashMap<>();
                row.outputs().forEach(stack -> outputs.merge(stack.what(), stack.amount(), CheckedAmounts::add));
                selected.add(new GraphRecipe<>(row.id(), row.id(), row.inputs().stream()
                        .map(stack -> new GraphRecipe.Slot<>(stack.what(), stack.amount())).toList(), outputs));
            }
            default -> steps.add(row);
        }
        var topology = new PlanTopology<>(selected);
        return new Model(topology, topology.groups().stream().filter(PlanTopology.Group::cyclic).toList(),
                List.copyOf(resources), List.copyOf(recipes), List.copyOf(steps), Map.copyOf(byRecipe), Map.copyOf(byResource), new PlanGraphLayout<>(topology));
    }

    @Override
    public void drawBG(GuiGraphics graphics, int offsetX, int offsetY, int mouseX, int mouseY, float partialTick) {
        super.drawBG(graphics, offsetX, offsetY, mouseX, mouseY, partialTick);
        graphics.fill(offsetX + 11, offsetY + 72, offsetX + 349, offsetY + 229, 0xFF73737D);
        graphics.fill(offsetX + 12, offsetY + 73, offsetX + 348, offsetY + 228, 0xFFDBDBDF);
    }

    @Override
    public void drawFG(GuiGraphics graphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        super.drawFG(graphics, offsetX, offsetY, mouseX, mouseY);
        hits.clear();
        graphics.drawString(font, text("title"), 12, 12, INK, false);
        if (target != null) {
            AEKeyRendering.drawInGui(minecraft, graphics, 134, 8, target.what());
            graphics.drawString(font, font.plainSubstrByWidth(target.what().getDisplayName().getString(), 116), 154, 12, INK, false);
            hits.add(new Hit(130, 7, 152, 18, List.of(target.what().getDisplayName(), text("exact", Long.toString(target.amount())))));
        }
        if (error != null) graphics.drawString(font, text("error", error), 16, 68, 0xFFAE3030, false);
        else if (model == null) graphics.drawString(font, text("loading", rows.size(), Math.max(0, total)), 16, 68, INK, false);
        else {
            switch (mode) {
                case 0 -> drawResources(graphics);
                case 1 -> drawRing(graphics, mouseX, mouseY);
                case 2 -> drawRecipe(graphics);
                default -> drawSteps(graphics);
            }
            Component footer = mode == 1 ? (page == 0 ? text("all_graph") : text("cycle_index", page, model.rings().size())) : text("page", page + 1, pageCount());
            graphics.drawString(font, footer, (360 - font.width(footer)) / 2, 242, INK, false);
        }
        int mx = mouseX - getGuiLeft(), my = mouseY - getGuiTop();
        for (var hit : hits) if (mx >= hit.x() && my >= hit.y() && mx < hit.x() + hit.width() && my < hit.y() + hit.height()) {
            drawTooltipWithHeader(graphics, mx, my, hit.tooltip());
            break;
        }
    }

    private int pageCount() {
        if (model == null) return 1;
        return Math.max(1, switch (mode) {
            case 0 -> (model.resources().size() + 7) / 8;
            case 1 -> model.rings().size() + 1;
            case 2 -> model.recipes().size();
            default -> (model.steps().size() + 8) / 9;
        });
    }

    private void drawResources(GuiGraphics graphics) {
        graphics.drawString(font, text("column_material"), 16, 58, MUTED, false);
        graphics.drawString(font, text("column_initial"), 157, 58, MUTED, false);
        graphics.drawString(font, text("column_seed"), 236, 58, MUTED, false);
        graphics.drawString(font, text("column_missing"), 303, 58, MUTED, false);
        int end = Math.min(model.resources().size(), page * 8 + 8);
        for (int i = page * 8; i < end; i++) {
            var row = model.resources().get(i);
            int y = 77 + (i % 8) * 19;
            icon(graphics, row.icon().what(), 14, y, resourceTooltip(row.icon().what()));
            graphics.drawString(font, font.plainSubstrByWidth(row.icon().what().getDisplayName().getString(), 119), 34, y + 4, INK, false);
            graphics.drawString(font, compact(row.icon().amount()), 157, y + 4, INK, false);
            graphics.drawString(font, compact(row.seed()), 236, y + 4, ACCENT, false);
            graphics.drawString(font, compact(row.missing()), 303, y + 4, row.missing() > 0 ? 0xFFAE3030 : MUTED, false);
            hits.add(new Hit(34, y, 314, 17, resourceTooltip(row.icon().what())));
        }
    }

    private List<Component> resourceTooltip(AEKey key) {
        List<Component> tooltip = new ArrayList<>();
        tooltip.add(key.getDisplayName());
        var resource = model.byResource().get(key);
        if (resource != null) {
            tooltip.add(text("initial", Long.toString(resource.icon().amount())));
            tooltip.add(text("seed", Long.toString(resource.seed())));
            tooltip.add(text("missing", Long.toString(resource.missing())));
        }
        tooltip.add(text("shared"));
        return tooltip;
    }

    private void resetView() {
        if (model == null || mode != 1) return;
        PlanGraphLayout.Box box = page == 0 ? model.layout().bounds() : model.layout().rings().get(page - 1).bounds();
        zoom = Math.max(0.25, Math.min(1.3, Math.min((VIEW_W - 24) / box.width(), (VIEW_H - 22) / box.height())));
        double cx = box.x() + box.width() / 2, cy = box.y() + box.height() / 2;
        if (page == 0 && model.topology().nodes().size() > 80 && target != null) {
            for (var node : model.topology().nodes()) if (target.what().equals(node.resource())) {
                var point = model.layout().points().get(node.id());
                cx = point.x();
                cy = point.y();
                break;
            }
            zoom = 1;
        }
        panX = VIEW_W / 2.0 - cx * zoom;
        panY = VIEW_H / 2.0 - cy * zoom;
    }

    private boolean inGraph(double x, double y) {
        return mode == 1 && model != null && x >= getGuiLeft() + VIEW_X && x < getGuiLeft() + VIEW_X + VIEW_W &&
                y >= getGuiTop() + VIEW_Y && y < getGuiTop() + VIEW_Y + VIEW_H;
    }

    private void drawRing(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, font.plainSubstrByWidth(text("graph_hint").getString(), 258), 14, 58, MUTED, false);
        hoveredNode = -1;
        double mx = (mouseX - getGuiLeft() - VIEW_X - panX) / zoom;
        double my = (mouseY - getGuiTop() - VIEW_Y - panY) / zoom;
        var viewport = new PlanGraphLayout.Box(-panX / zoom, -panY / zoom, VIEW_W / zoom, VIEW_H / zoom);
        var layout = model.layout();
        graphics.enableScissor(getGuiLeft() + VIEW_X, getGuiTop() + VIEW_Y, getGuiLeft() + VIEW_X + VIEW_W, getGuiTop() + VIEW_Y + VIEW_H);
        graphics.pose().pushPose();
        graphics.pose().translate(VIEW_X + panX, VIEW_Y + panY, 0);
        graphics.pose().scale((float) zoom, (float) zoom, 1);
        for (int id : layout.visibleRings(viewport)) {
            var box = layout.rings().get(id).bounds();
            int x = (int) box.x(), y = (int) box.y(), w = (int) box.width(), h = (int) box.height();
            graphics.fill(x, y, x + w, y + h, 0xFFE0DAE8);
            graphics.renderOutline(x, y, w, h, 0xFFA99BB9);
            graphics.drawString(font, text("cycle_label", id + 1), x + 5, y + 4, ACCENT, false);
        }
        for (int id : layout.visibleLinks(viewport)) {
            var link = layout.links().get(id);
            var points = link.path();
            int color = link.cyclic() ? ACCENT : 0xFF777782;
            for (int i = 1; i < points.size(); i++) line(graphics, points.get(i - 1), points.get(i), color, i == points.size() - 1);
            if (zoom >= 0.7) {
                var middle = points.get(points.size() / 2);
                String quantity = compact(link.edge().perRun());
                int x = (int) middle.x(), y = (int) middle.y() - 6;
                graphics.fill(x - 1, y - 1, x + font.width(quantity) + 1, y + 9, 0xFFDBDBDF);
                graphics.drawString(font, quantity, x, y, color, false);
            }
        }
        for (int id : layout.visibleNodes(viewport)) {
            var point = layout.points().get(id);
            var node = model.topology().nodes().get(id);
            int x = (int) point.x() - 8, y = (int) point.y() - 8;
            boolean hovered = inGraph(mouseX, mouseY) && Math.abs(mx - point.x()) < 12 && Math.abs(my - point.y()) < 12;
            graphics.fill(x - 3, y - 3, x + 19, y + 19, hovered ? 0xFFF1E9FF : 0xFFC2BDCF);
            graphics.renderOutline(x - 3, y - 3, 22, 22, hovered ? ACCENT : 0xFF777183);
            if (node.resource() != null) AEKeyRendering.drawInGui(minecraft, graphics, x, y, node.resource());
            else Icon.CRAFT_HAMMER.getBlitter().dest(x, y).blit(graphics);
            if (hovered) hoveredNode = id;
            if (zoom >= 0.65) {
                String label = node.resource() == null ? text("recipe_node").getString() : node.resource().getDisplayName().getString();
                label = font.plainSubstrByWidth(label, 46);
                graphics.drawString(font, label, x + 8 - font.width(label) / 2, y + 21, INK, false);
            }
        }
        graphics.pose().popPose();
        graphics.disableScissor();
        if (hoveredNode >= 0) {
            var node = model.topology().nodes().get(hoveredNode);
            var tooltip = node.resource() == null ? recipeTooltip(model.byRecipe().get(node.recipe())) : resourceTooltip(node.resource());
            hits.add(new Hit(mouseX - getGuiLeft(), mouseY - getGuiTop(), 1, 1, tooltip));
        }
    }

    private static void line(GuiGraphics graphics, PlanGraphLayout.Point from, PlanGraphLayout.Point to, int color, boolean arrow) {
        double length = Math.hypot(to.x() - from.x(), to.y() - from.y());
        if (length < 0.01) return;
        graphics.pose().pushPose();
        graphics.pose().translate(from.x(), from.y(), 0);
        graphics.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(to.y() - from.y(), to.x() - from.x())));
        graphics.fill(0, 0, (int) Math.ceil(length), 1, color);
        if (arrow) {
            graphics.pose().translate(length, 0, 0);
            graphics.pose().mulPose(Axis.ZP.rotationDegrees(45));
            graphics.fill(-5, 0, 1, 1, color);
            graphics.pose().mulPose(Axis.ZP.rotationDegrees(-90));
            graphics.fill(-5, 0, 1, 1, color);
        }
        graphics.pose().popPose();
    }

    private void drawRecipe(GuiGraphics graphics) {
        if (model.recipes().isEmpty()) {
            graphics.drawString(font, text("no_recipes"), 16, 76, INK, false);
            return;
        }
        var row = model.recipes().get(page);
        graphics.drawString(font, text("runs", Long.toString(row.count())), 14, 56, ACCENT, false);
        int pages = Math.max(1, (Math.max(row.inputs().size(), row.outputs().size()) + 7) / 8);
        nodePage = Math.max(0, Math.min(nodePage, pages - 1));
        graphics.drawString(font, text("io", nodePage + 1, pages), 14, 74, MUTED, false);
        drawStacks(graphics, row.inputs(), row.count(), 14);
        drawStacks(graphics, row.outputs(), row.count(), 190);
    }

    private void drawStacks(GuiGraphics graphics, List<GenericStack> stacks, long runs, int x) {
        for (int i = nodePage * 8; i < Math.min(stacks.size(), nodePage * 8 + 8); i++) {
            var stack = stacks.get(i);
            int y = 87 + (i % 8) * 17;
            icon(graphics, stack.what(), x, y, List.of(stack.what().getDisplayName(), text("per_run", Long.toString(stack.amount())),
                    text("total", BigInteger.valueOf(stack.amount()).multiply(BigInteger.valueOf(runs)).toString())));
            graphics.drawString(font, font.plainSubstrByWidth(stack.what().getDisplayName().getString(), 91), x + 20, y + 4, INK, false);
            graphics.drawString(font, compact(stack.amount()), x + 114, y + 4, ACCENT, false);
        }
    }

    private List<Component> recipeTooltip(GraphRingView.Row row) {
        return List.of(text("recipe_for", row.icon().what().getDisplayName()), text("runs", Long.toString(row.count())), text("open_recipe"));
    }

    private static String compact(long amount) {
        return amount < 10_000 ? Long.toString(amount) : java.math.BigDecimal.valueOf(amount)
                .round(new java.math.MathContext(3, java.math.RoundingMode.DOWN)).stripTrailingZeros().toEngineeringString();
    }

    private void drawSteps(GuiGraphics graphics) {
        graphics.drawString(font, text("compressed"), 14, 56, MUTED, false);
        for (int i = page * 9; i < Math.min(model.steps().size(), page * 9 + 9); i++) {
            var row = model.steps().get(i);
            int y = 75 + (i % 9) * 16;
            Component line = switch (row.kind()) {
                case REPEAT -> text("repeat", Long.toString(row.count()));
                case SEQUENCE -> text("sequence");
                default -> {
                    var recipe = model.byRecipe().get(row.id());
                    yield text("batch", recipe == null ? row.id() : recipe.icon().what().getDisplayName(), Long.toString(row.count()));
                }
            };
            int depth = 0, parent = row.parent();
            while (parent >= 0 && depth < 7) {
                depth++;
                parent = rows.get(parent).parent();
            }
            graphics.drawString(font, font.plainSubstrByWidth(line.getString(), 328 - depth * 8), 14 + depth * 8, y, INK, false);
            hits.add(new Hit(14, y, 330, 15, List.of(line)));
        }
    }

    private void icon(GuiGraphics graphics, AEKey key, int x, int y, List<Component> tooltip) {
        Icon.SLOT_BACKGROUND.getBlitter().dest(x - 1, y - 1).blit(graphics);
        AEKeyRendering.drawInGui(minecraft, graphics, x, y, key);
        hits.add(new Hit(x - 2, y - 2, 20, 20, tooltip));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (inGraph(x, y)) {
            double nextZoom = Math.max(0.15, Math.min(3, zoom * Math.pow(1.2, delta)));
            double mx = x - getGuiLeft() - VIEW_X, my = y - getGuiTop() - VIEW_Y;
            panX = mx - (mx - panX) * nextZoom / zoom;
            panY = my - (my - panY) * nextZoom / zoom;
            zoom = nextZoom;
            return true;
        }
        if (model != null && mode != 1) {
            if (mode == 2) nodePage = Math.max(0, nodePage + (delta < 0 ? 1 : -1));
            else turn(delta < 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(x, y, delta);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (inGraph(x, y) && button == 0) {
            if (hoveredNode >= 0 && hasShiftDown()) {
                var node = model.topology().nodes().get(hoveredNode);
                if (node.recipe() != null) {
                    select(2);
                    page = model.recipes().indexOf(model.byRecipe().get(node.recipe()));
                    return true;
                }
            }
            dragging = true;
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (dragging && button == 0) {
            panX += dx;
            panY += dy;
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (dragging && button == 0) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(x, y, button);
    }
}
