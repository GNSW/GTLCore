package org.gtlcore.gtlcore.client.ae2.graph;

import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAePackets;
import org.gtlcore.gtlcore.integration.jei.JeiMissingIngredientBookmarks;

import com.lowdragmc.lowdraglib.LDLib;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.AESubScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.StackWithBounds;
import appeng.client.gui.me.crafting.CraftConfirmScreen;
import appeng.client.gui.widgets.IconButton;
import appeng.client.gui.widgets.TabButton;
import appeng.menu.me.crafting.CraftConfirmMenu;
import org.cgse.core.*;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Read-only plan browser. Navigation changes the projection, never the execution plan. */
public final class CraftingRingScreen extends AESubScreen<CraftConfirmMenu, CraftConfirmScreen> {

    private static final int INK = 0xFF40404C, MUTED = 0xFF707078, ACCENT = 0xFF786296, SEED = 0xFF168F99, INITIAL = 0xFFAD6518;
    private static final int VIEW_X = 10, VIEW_Y = 48;
    private static final int MAX_LIVE_NODES = 256, RESUME_LIVE_NODES = 192;
    private static Cache cached;
    private UUID planId;
    private GraphPlan.SeedOptimality seedProof;
    private boolean waitingForPlan;
    private final List<GraphRingView.Row> rows = new ArrayList<>();
    private GenericStack target;
    private int total = -1, graphRows = -1, requested = -1, mode = 1, page, nodePage;
    private long lastRequest, retryAfter;
    private CompletableFuture<Model> loading;
    private CompletableFuture<PlanGraphLayout<AEKey>> fullLoading;
    private CompletableFuture<PlanDependencyLayout.View> treeLoading;
    private Model model;
    private PlanGraphLayout<AEKey> fullLayout;
    private PlanDependencyLayout.View tree;
    private String error;
    private final List<Hit> hits = new ArrayList<>();
    private final Map<AEKey, List<Component>> tooltips = new HashMap<>();
    private final Button[] tabs = new Button[4];
    private final Button previous, next, reset, backFocus;
    private final Button missingButton, screenshotButton;
    private boolean missingOnly = GraphViewSettings.get().missingOnlyByDefault;
    private int selectedEntry;
    private GraphDiagramExporter.Job export;
    private final Deque<Focus> history = new ArrayDeque<>();
    private Focus focus;
    private double zoom = 1, panX, panY, dragDistance;
    private boolean dragging;
    private int dragButton;
    private int hoveredNode = -1, hoveredEntry = -1;
    private final BitSet filteredNodes = new BitSet();
    private final GraphViewportCache diagramCache = new GraphViewportCache();
    private NodeView nodeView;
    private List<Integer> liveNodeIds = List.of();
    private boolean liveNodes;

    private record DiagramContent(Object layout, Object filter, int page, boolean missingOnly, boolean amounts, int detail) {}

    private record NodeView(Object layout, Object filter, int page, boolean missingOnly, PlanGraphLayout.Box area) {

        boolean sameDiagram(NodeView other) {
            return other != null && layout == other.layout && filter == other.filter && page == other.page && missingOnly == other.missingOnly;
        }
    }

    private record Hit(int x, int y, int width, int height, List<Component> tooltip) {}

    private record Focus(int node, int offset) {}

    private record Cache(UUID id, GenericStack target, int graphRows, List<GraphRingView.Row> rows, Model model) {}

    private record Model(PlanTopology<AEKey> topology, List<PlanTopology.Group> rings,
                         List<GraphRingView.Row> resources, List<GraphRingView.Row> recipes,
                         Map<String, GraphRingView.Row> byRecipe, Map<AEKey, GraphRingView.Row> byResource,
                         PlanDependencyLayout<AEKey> dependencies, int root,
                         Map<AEKey, BigInteger> crafted, Map<AEKey, BigInteger> used, Set<AEKey> initialInputs,
                         int[][] incidentLinks) {}

    private static final class ToolButton extends IconButton {

        private final Icon icon;

        ToolButton(Icon icon, Component label, Runnable action) {
            super(button -> action.run());
            this.icon = icon;
            setMessage(label);
        }

        @Override
        protected Icon getIcon() {
            return icon;
        }
    }

    public CraftingRingScreen(CraftConfirmScreen parent) {
        super(parent, "/screens/gtl_crafting_ring.json");
        planId = ((GraphPlanSummaryView) parent.getMenu().getPlan()).gtlcore$graphPlanId();
        seedProof = ((GraphPlanSummaryView) parent.getMenu().getPlan()).gtlcore$seedOptimality();
        var icon = menu.getHost().getMainMenuIcon();
        widgets.add("back", new TabButton(icon, Component.translatable("gui.back"), button -> returnToParent()));
        Icon[] icons = { Icon.STORAGE_FILTER_EXTRACTABLE_ONLY, Icon.SCHEDULING_ROUND_ROBIN, Icon.CRAFT_HAMMER, Icon.ARROW_RIGHT };
        String[] names = { "overview", "rings", "recipes", "steps" };
        for (int i = 0; i < tabs.length; i++) {
            final int selected = i;
            tabs[i] = new ToolButton(icons[i], text(names[i]), () -> select(selected));
            addToLeftToolbar(tabs[i]);
        }
        previous = widgets.addButton("previous", Component.literal("<"), () -> turn(-1));
        next = widgets.addButton("next", Component.literal(">"), () -> turn(1));
        reset = widgets.addButton("reset", text("fit"), this::fitView);
        backFocus = widgets.addButton("focusBack", Component.literal("<"), this::previousFocus);
        missingButton = addToLeftToolbar(new ToolButton(Icon.INVALID, text("missing_only"), () -> {
            missingOnly = !missingOnly;
            tooltips.clear();
            page = 0;
            refreshTree();
        }));
        screenshotButton = addToLeftToolbar(new ToolButton(Icon.STORAGE_FILTER_EXTRACTABLE_ONLY, text("export"), this::exportDiagram));
        addToLeftToolbar(new ToolButton(Icon.WRENCH, text("settings"), () -> switchToScreen(new GraphViewSettingsScreen(this))));
        if (cached != null && cached.id().equals(planId)) {
            target = cached.target();
            graphRows = cached.graphRows();
            rows.addAll(cached.rows());
            total = rows.size();
            model = cached.model();
            home();
        }
    }

    @Override
    protected void init() {
        imageWidth = Math.min(320, Math.max(220, width - (LDLib.isJeiLoaded() ? 120 : 48)));
        imageHeight = Math.min(232, Math.max(172, height - 32));
        style.getGeneratedBackground().setWidth(imageWidth);
        style.getGeneratedBackground().setHeight(imageHeight);
        super.init();
        diagramCache.close();
    }

    @Override
    public void removed() {
        diagramCache.close();
        super.removed();
    }

    private int viewWidth() {
        return imageWidth - VIEW_X * 2;
    }

    private int viewHeight() {
        return imageHeight - VIEW_Y - 30;
    }

    private int listRows() {
        return Math.max(1, (viewHeight() - 4) / 20);
    }

    private int ioRows() {
        return Math.max(1, (viewHeight() - 22) / 18);
    }

    private int stepRows() {
        return Math.max(1, (viewHeight() - 4) / 16);
    }

    @Override
    public List<Rect2i> getExclusionZones() {
        var areas = new ArrayList<>(super.getExclusionZones());
        areas.add(new Rect2i(getGuiLeft() - 26, getGuiTop(), imageWidth + 28, imageHeight));
        return areas;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("gtlcore.ae.ring." + key, args);
    }

    private void select(int selected) {
        mode = selected;
        page = nodePage = 0;
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
        synchronizePlan();
        for (int i = 0; i < tabs.length; i++) tabs[i].active = i != mode;
        previous.active = model != null && page > 0;
        next.active = model != null && page + 1 < pageCount();
        reset.visible = backFocus.visible = mode == 1;
        reset.active = model != null;
        backFocus.active = model != null && page == 0 && !history.isEmpty();
        missingButton.active = model != null;
        missingButton.setMessage(text(missingOnly ? "show_all" : "missing_only"));
        screenshotButton.active = model != null && tree != null && !tree.entries().isEmpty() && export == null;
        if (export != null && export.done()) export = null;
        if (treeLoading != null && treeLoading.isDone()) {
            try {
                tree = treeLoading.join();
                filteredNodes.clear();
                for (var entry : tree.entries()) filteredNodes.set(entry.node());
                selectedEntry = 0;
                resetView();
            } catch (RuntimeException e) {
                error = "LAYOUT_FAILED";
            }
            treeLoading = null;
        }
        if (planId == null || error != null || !((GraphPlanMenu) menu).gtlcore$planningFailure().isEmpty()) return;
        long now = System.nanoTime();
        if ((total < 0 || rows.size() < total) && now >= retryAfter &&
                (requested != rows.size() || now - lastRequest > 2_000_000_000L)) {
            requested = rows.size();
            lastRequest = now;
            WirelessAePackets.CHANNEL.sendToServer(new GraphRingPackets.Request(menu.containerId, planId, requested));
        }
        // Recipe topology is a prefix. The execution program can continue arriving in the background.
        if (model == null && graphRows >= 0 && rows.size() >= graphRows) {
            if (loading == null) {
                var snapshot = List.copyOf(rows.subList(0, graphRows));
                AEKey key = target.what();
                loading = CompletableFuture.supplyAsync(() -> build(snapshot, key));
            } else if (loading.isDone()) {
                try {
                    model = loading.join();
                    home();
                } catch (RuntimeException e) {
                    error = "LAYOUT_FAILED";
                }
            }
        }
        if (model != null && rows.size() == total && total <= 20_000 && (cached == null || !cached.id().equals(planId)))
            cached = new Cache(planId, target, graphRows, List.copyOf(rows), model);
        if (model != null && mode == 1 && page > 0 && fullLayout == null) {
            if (fullLoading == null) {
                var source = model;
                fullLoading = CompletableFuture.supplyAsync(() -> new PlanGraphLayout<>(source.topology()));
            } else if (fullLoading.isDone()) {
                try {
                    fullLayout = fullLoading.join();
                    resetView();
                } catch (RuntimeException e) {
                    error = "LAYOUT_FAILED";
                }
            }
        }
    }

    private void synchronizePlan() {
        if (!(menu.getPlan() instanceof GraphPlanSummaryView summary)) return;
        UUID next = summary.gtlcore$graphPlanId();
        if (Objects.equals(next, planId)) return;
        clearPlan();
        planId = next;
        seedProof = summary.gtlcore$seedOptimality();
        waitingForPlan = false;
        if (next == null) error = "PLAN_UNAVAILABLE";
    }

    private void clearPlan() {
        // All page offsets, layouts and node indices belong to one plan UUID.
        // In-flight responses and old layout completions must never enter its replacement.
        diagramCache.close();
        rows.clear();
        target = null;
        total = graphRows = requested = -1;
        page = nodePage = selectedEntry = 0;
        lastRequest = retryAfter = 0;
        loading = null;
        fullLoading = null;
        treeLoading = null;
        model = null;
        fullLayout = null;
        tree = null;
        error = null;
        hits.clear();
        tooltips.clear();
        history.clear();
        focus = null;
        dragging = false;
        hoveredNode = hoveredEntry = -1;
        filteredNodes.clear();
    }

    public static void receive(GraphRingPackets.Response response) {
        if (Minecraft.getInstance().screen instanceof CraftingRingScreen screen && screen.menu.containerId == response.container()) {
            screen.synchronizePlan();
            if (!response.page().id().equals(screen.planId) || screen.waitingForPlan) return;
            var data = response.page();
            if (data.offset() != screen.rows.size()) return;
            if (screen.total >= 0 && (data.total() != screen.total || data.graphRows() != screen.graphRows)) {
                screen.error = "VIEW_CHANGED";
                return;
            }
            screen.target = data.target();
            screen.total = data.total();
            screen.graphRows = data.graphRows();
            screen.rows.addAll(data.rows());
        }
    }

    public static void fail(GraphRingPackets.Failure failure) {
        if (Minecraft.getInstance().screen instanceof CraftingRingScreen screen && screen.menu.containerId == failure.container()) {
            screen.synchronizePlan();
            if (!failure.plan().equals(screen.planId)) return;
            if (failure.reason().equals("RATE_LIMIT")) {
                screen.requested = -1;
                screen.retryAfter = System.nanoTime() + 100_000_000L;
            } else if (failure.reason().equals("PLAN_CHANGED")) {
                screen.clearPlan();
                screen.waitingForPlan = true;
                screen.retryAfter = System.nanoTime() + 500_000_000L;
            } else screen.error = failure.reason();
        }
    }

    private static Model build(List<GraphRingView.Row> rows, AEKey target) {
        List<GraphRingView.Row> resources = new ArrayList<>(), recipes = new ArrayList<>();
        List<GraphRecipe<AEKey>> selected = new ArrayList<>();
        Map<String, GraphRingView.Row> byRecipe = new LinkedHashMap<>();
        Map<AEKey, GraphRingView.Row> byResource = new LinkedHashMap<>();
        Map<AEKey, BigInteger> crafted = new LinkedHashMap<>(), used = new LinkedHashMap<>();
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
                row.outputs().forEach(stack -> crafted.merge(stack.what(), row.count().multiply(BigInteger.valueOf(stack.amount())), BigInteger::add));
                row.inputs().forEach(stack -> used.merge(stack.what(), row.count().multiply(BigInteger.valueOf(stack.amount())), BigInteger::add));
                selected.add(new GraphRecipe<>(row.id(), row.id(), row.inputs().stream()
                        .map(stack -> new GraphRecipe.Slot<>(stack.what(), stack.amount())).toList(), outputs));
            }
            default -> {}
        }
        Set<AEKey> standalone = new LinkedHashSet<>(byResource.keySet());
        standalone.add(target);
        var topology = new PlanTopology<>(selected, standalone);
        Set<AEKey> initialInputs = new HashSet<>();
        for (var group : topology.groups()) if (group.cyclic()) for (int id : group.nodes()) {
            var key = topology.nodes().get(id).resource();
            var row = byResource.get(key);
            // A retained seed and an initial input are different obligations. Do not
            // label every cyclic intermediate, or its total demand, as a seed.
            if (row != null && row.seed() == 0 && row.count().signum() > 0) initialInputs.add(key);
        }
        int root = topology.nodes().stream().filter(node -> target.equals(node.resource())).mapToInt(PlanTopology.Node::id).findFirst().orElse(-1);
        int[] degree = new int[topology.nodes().size()];
        for (var edge : topology.edges()) {
            degree[edge.from()]++;
            degree[edge.to()]++;
        }
        int[][] incident = new int[degree.length][];
        for (int i = 0; i < degree.length; i++) incident[i] = new int[degree[i]];
        Arrays.fill(degree, 0);
        for (int i = 0; i < topology.edges().size(); i++) {
            var edge = topology.edges().get(i);
            incident[edge.from()][degree[edge.from()]++] = i;
            incident[edge.to()][degree[edge.to()]++] = i;
        }
        return new Model(topology, topology.groups().stream().filter(PlanTopology.Group::cyclic).toList(),
                List.copyOf(resources), List.copyOf(recipes), Map.copyOf(byRecipe), Map.copyOf(byResource), new PlanDependencyLayout<>(topology), root,
                Map.copyOf(crafted), Map.copyOf(used), Set.copyOf(initialInputs), incident);
    }

    @Override
    public void drawBG(GuiGraphics graphics, int offsetX, int offsetY, int mouseX, int mouseY, float partialTick) {
        super.drawBG(graphics, offsetX, offsetY, mouseX, mouseY, partialTick);
        graphics.fill(offsetX + VIEW_X, offsetY + VIEW_Y, offsetX + VIEW_X + viewWidth(), offsetY + VIEW_Y + viewHeight(), 0xFFD4D4D7);
    }

    @Override
    public void drawFG(GuiGraphics graphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        super.drawFG(graphics, offsetX, offsetY, mouseX, mouseY);
        hits.clear();
        String[] names = { "overview", "rings", "recipes", "steps" };
        graphics.drawString(font, text(names[mode]), 12, 10, INK, false);
        if (target != null) {
            int x = imageWidth / 2;
            AEKeyRendering.drawInGui(minecraft, graphics, x, 7, target.what());
            graphics.drawString(font, font.plainSubstrByWidth(target.what().getDisplayName().getString(), imageWidth - x - 56), x + 20, 11, INK, false);
            hits.add(new Hit(x, 6, imageWidth - x - 30, 18, List.of(target.what().getDisplayName(), text("exact", Long.toString(target.amount())))));
        }
        String planningFailure = ((GraphPlanMenu) menu).gtlcore$planningFailure();
        if (!planningFailure.isEmpty()) centered(graphics, Component.translatable(planningFailure), 128, 0xFFAE3030);
        else if (waitingForPlan) centered(graphics, text("updating"), 118, INK);
        else if (error != null) centered(graphics, text("error", error), 128, 0xFFAE3030);
        else if (model == null) {
            centered(graphics, text(loading == null ? "loading" : "arranging", rows.size(), Math.max(0, total)), 118, INK);
            graphics.fill(32, 138, imageWidth - 32, 141, 0xFFB4B4BA);
            int progress = total <= 0 ? 0 : (int) ((imageWidth - 64L) * rows.size() / total);
            graphics.fill(32, 138, 32 + progress, 141, ACCENT);
        } else {
            switch (mode) {
                case 0 -> drawResources(graphics);
                case 1 -> drawGraph(graphics, mouseX, mouseY);
                case 2 -> drawRecipe(graphics);
                default -> drawSteps(graphics);
            }
            Component footer = mode == 1 ? text(page == 0 ? "tree_view" : page == 1 ? "all_graph" : "cycle_index", page - 1, model.rings().size()) :
                    text("page", page + 1, pageCount());
            String caption = font.plainSubstrByWidth(footer.getString(), imageWidth - 72);
            centered(graphics, Component.literal(caption), imageHeight - 18, INK);
            hits.add(new Hit(36, imageHeight - 23, imageWidth - 72, 18, List.of(footer)));
            if (rows.size() < total) graphics.drawString(font, text("loading", rows.size(), total), 12, imageHeight - 29, MUTED, false);
        }
        int mx = mouseX - getGuiLeft(), my = mouseY - getGuiTop();
        for (var hit : hits) if (mx >= hit.x() && my >= hit.y() && mx < hit.x() + hit.width() && my < hit.y() + hit.height()) {
            drawTooltipWithHeader(graphics, mx, my, hit.tooltip());
            break;
        }
    }

    private void centered(GuiGraphics graphics, Component message, int y, int color) {
        graphics.drawString(font, message, (imageWidth - font.width(message)) / 2, y, color, false);
    }

    private int pageCount() {
        if (model == null) return 1;
        return Math.max(1, switch (mode) {
            case 0 -> (resources().size() + listRows() - 1) / listRows();
            case 1 -> model.rings().size() + 2;
            case 2 -> model.recipes().size();
            default -> (Math.max(0, rows.size() - graphRows) + stepRows() - 1) / stepRows();
        });
    }

    private void right(GuiGraphics graphics, String value, int x, int y, int color) {
        graphics.drawString(font, value, x - font.width(value), y, color, false);
    }

    private void drawResources(GuiGraphics graphics) {
        int initialX = imageWidth * 3 / 5, seedX = imageWidth * 4 / 5, missingX = imageWidth - 15;
        graphics.drawString(font, text("column_material"), 14, 31, MUTED, false);
        right(graphics, text("column_initial").getString(), initialX, 31, MUTED);
        right(graphics, text("column_seed").getString(), seedX, 31, SEED);
        right(graphics, text("column_missing").getString(), missingX, 31, MUTED);
        hits.add(new Hit(initialX + 4, 28, seedX - initialX - 4, 14, GraphSeedStatus.tooltip(seedProof)));
        var resources = resources();
        for (int i = page * listRows(); i < Math.min(resources.size(), (page + 1) * listRows()); i++) {
            var row = resources.get(i);
            int y = VIEW_Y + 4 + (i % listRows()) * 20;
            if (i % 2 == 0) graphics.fill(11, y - 2, imageWidth - 11, y + 18, 0xFFE0E0E2);
            icon(graphics, row.icon().what(), 15, y, resourceTooltip(row.icon().what()));
            graphics.drawString(font, font.plainSubstrByWidth(row.icon().what().getDisplayName().getString(), initialX - 96), 36, y + 4, INK, false);
            right(graphics, compact(row.count()), initialX, y + 4, INK);
            right(graphics, compact(row.seed()), seedX, y + 4, row.seed() > 0 ? SEED : MUTED);
            right(graphics, compact(row.missing()), missingX, y + 4, row.missing().signum() > 0 ? 0xFFAE3030 : MUTED);
            hits.add(new Hit(35, y - 1, imageWidth - 50, 19, resourceTooltip(row.icon().what())));
        }
    }

    private List<GraphRingView.Row> resources() {
        return missingOnly ? model.resources().stream().filter(row -> row.missing().signum() > 0).toList() : model.resources();
    }

    private List<Component> resourceTooltip(AEKey key) {
        return tooltips.computeIfAbsent(key, ignored -> {
            List<Component> lines = new ArrayList<>(AEKeyRendering.getTooltip(key));
            var resource = model.byResource().get(key);
            if (target.what().equals(key)) lines.add(text("exact", Long.toString(target.amount())));
            if (resource != null) {
                lines.add(text("initial", resource.count().toString()));
                lines.add(text("stored", resource.count().subtract(resource.missing()).max(BigInteger.ZERO).toString()));
                if (resource.seed() > 0) {
                    lines.add(text("seed", Long.toString(resource.seed())));
                    lines.addAll(GraphSeedStatus.tooltip(seedProof));
                }
                if (model.initialInputs().contains(key)) lines.add(text("initial_input_help"));
                if (resource.missing().signum() > 0) lines.add(text("missing", resource.missing().toString()));
            }
            if (model.crafted().containsKey(key)) lines.add(text("crafted", model.crafted().get(key).toString()));
            if (model.used().containsKey(key)) lines.add(text("used", model.used().get(key).toString()));
            if (!model.crafted().containsKey(key)) lines.add(text("external_leaf"));
            lines.add(text("recipe_click_hint"));
            if (model.crafted().containsKey(key)) lines.add(text("focus_hint"));
            return List.copyOf(lines);
        });
    }

    private void home() {
        if (model == null) return;
        history.clear();
        focus = new Focus(model.root(), 0);
        refreshTree();
    }

    private void focus(Focus next) {
        if (next.equals(focus)) {
            resetView();
            return;
        }
        if (focus != null) history.push(focus);
        if (history.size() > 128) history.removeLast();
        focus = next;
        refreshTree();
    }

    private void previousFocus() {
        if (history.isEmpty()) return;
        focus = history.pop();
        refreshTree();
    }

    void refreshTree() {
        if (model == null || focus == null) return;
        var selected = focus;
        var source = model;
        boolean filtered = missingOnly, compact = GraphViewSettings.get().compact;
        tree = null;
        treeLoading = CompletableFuture.supplyAsync(() -> source.dependencies().complete(selected.node(),
                filtered ? key -> source.byResource().containsKey(key) && source.byResource().get(key).missing().signum() > 0 : null, compact));
    }

    private void resetView() {
        if (model == null || mode != 1) return;
        if (page == 0) {
            if (tree == null || tree.entries().isEmpty()) return;
            zoom = 1;
            panX = viewWidth() / 2.0 - tree.entries().get(0).point().x();
            panY = 22;
        } else if (fullLayout != null) {
            var box = page == 1 ? fullLayout.bounds() : fullLayout.rings().get(page - 2).bounds();
            zoom = Math.max(0.01, Math.min(1, Math.min((viewWidth() - 24) / box.width(), (viewHeight() - 24) / box.height())));
            panX = viewWidth() / 2.0 - (box.x() + box.width() / 2) * zoom;
            panY = viewHeight() / 2.0 - (box.y() + box.height() / 2) * zoom;
        }
    }

    private boolean inGraph(double x, double y) {
        return mode == 1 && model != null && x >= getGuiLeft() + VIEW_X && x < getGuiLeft() + VIEW_X + viewWidth() &&
                y >= getGuiTop() + VIEW_Y && y < getGuiTop() + VIEW_Y + viewHeight();
    }

    private void drawGraph(GuiGraphics graphics, int mouseX, int mouseY) {
        Component hint = text(missingOnly ? "missing_only" : "browse_hint");
        graphics.drawString(font, font.plainSubstrByWidth(hint.getString(), imageWidth - 105), 12, 31, MUTED, false);
        hits.add(new Hit(12, 29, imageWidth - 105, 13, List.of(hint, text("seed_legend"), text("recipe_click_hint"), text("focus_hint"))));
        hoveredNode = hoveredEntry = -1;
        if (model.topology().nodes().isEmpty()) {
            centered(graphics, text("no_recipes"), 128, INK);
            return;
        }
        if ((page == 0 && tree == null) || (page > 0 && fullLayout == null)) {
            centered(graphics, text("arranging"), 128, INK);
            return;
        }
        if (page == 0 && tree.entries().isEmpty()) {
            centered(graphics, text("no_missing"), 115, MUTED);
            return;
        }
        var viewport = new PlanGraphLayout.Box(-panX / zoom, -panY / zoom, viewWidth() / zoom, viewHeight() / zoom);
        double renderZoom = zoom;
        boolean liveNodes = prepareNodeView(viewport);
        int detail = (zoom >= 0.4 ? 1 : 0) | (zoom >= 0.5 ? 2 : 0);
        var content = new DiagramContent(page == 0 ? tree : fullLayout, tree, page, missingOnly, GraphViewSettings.get().showAmounts, detail);
        var drawingBounds = page == 0 ? tree.bounds() : page == 1 ? fullLayout.bounds() : fullLayout.rings().get(page - 2).bounds();
        diagramCache.prepare(graphics, content, viewport, drawingBounds, zoom, liveNodes, (area, pixelsPerUnit) -> new DiagramPainter(area, renderZoom, pixelsPerUnit, liveNodes));
        graphics.flush();
        graphics.enableScissor(getGuiLeft() + VIEW_X, getGuiTop() + VIEW_Y, getGuiLeft() + VIEW_X + viewWidth(), getGuiTop() + VIEW_Y + viewHeight());
        graphics.pose().pushPose();
        graphics.pose().translate(VIEW_X + panX, VIEW_Y + panY, 0);
        graphics.pose().scale((float) zoom, (float) zoom, 1);
        try {
            diagramCache.draw(graphics);
            drawGraphOverlay(graphics, viewport, mouseX, mouseY, liveNodes);
        } finally {
            // GuiGraphics only implicitly flushes scissor changes in managed mode.
            // Our batched quads must be submitted while this clip is still active.
            graphics.flush();
            graphics.pose().popPose();
            graphics.disableScissor();
        }
        if (diagramCache.progress() < 100) {
            graphics.drawString(font, text("drawing", diagramCache.progress()), VIEW_X + 4, VIEW_Y + 4, MUTED, false);
        }
        if (hoveredNode >= 0) {
            var node = model.topology().nodes().get(hoveredNode);
            List<Component> tooltip = new ArrayList<>(node.resource() == null ? recipeTooltip(model.byRecipe().get(node.recipe())) : resourceTooltip(node.resource()));
            if (hoveredEntry >= 0) {
                var entry = tree.entries().get(hoveredEntry);
                if (entry.kind() != PlanDependencyLayout.Kind.NORMAL) tooltip.add(text("reference_" + entry.kind().name().toLowerCase(Locale.ROOT)));
                if (entry.edge() >= 0) tooltip.add(text("per_run", Long.toString(model.topology().edges().get(entry.edge()).perRun())));
            }
            hits.add(new Hit(mouseX - getGuiLeft(), mouseY - getGuiTop(), 1, 1, tooltip));
        }
    }

    /** Cache dense views in full; sparse views keep only backgrounds and connections in tiles. */
    private final class DiagramPainter implements GraphViewportCache.Painter {

        private final PlanGraphLayout.Box area;
        private final double scale, halfStroke;
        private final List<Integer> nodes, links, rings;
        private final int group;
        private final boolean dependency, amounts;
        private int ringCursor, linkCursor, nodeCursor;

        DiagramPainter(PlanGraphLayout.Box area, double scale, double pixelsPerUnit, boolean liveNodes) {
            this.area = area;
            this.scale = scale;
            halfStroke = Math.max(0.35, 0.5 / pixelsPerUnit);
            dependency = page == 0;
            amounts = GraphViewSettings.get().showAmounts;
            group = page < 2 ? -1 : fullLayout.rings().get(page - 2).group();
            // Animated models must not be frozen or split across tiles captured on different frames.
            nodes = liveNodes ? List.of() : dependency ? tree.visibleEntries(area) : fullLayout.visibleNodes(area);
            links = dependency ? tree.visibleConnections(area) : fullLayout.visibleLinks(area);
            rings = dependency ? List.of() : fullLayout.visibleRings(area);
        }

        @Override
        public boolean advance(GuiGraphics graphics, long deadline) {
            int work = 0;
            do {
                if (ringCursor < rings.size()) {
                    var ring = fullLayout.rings().get(rings.get(ringCursor++));
                    if (group < 0 || ring.group() == group) {
                        var box = ring.bounds();
                        graphics.fill((int) box.x(), (int) box.y(), (int) (box.x() + box.width()), (int) (box.y() + box.height()), 0xFFDCD6E4);
                    }
                } else if (linkCursor < links.size()) {
                    int id = links.get(linkCursor++);
                    if (dependency) {
                        var entry = tree.entries().get(id);
                        if (entry.parent() >= 0) {
                            var from = tree.entries().get(entry.parent()).point();
                            var to = entry.point();
                            double middle = (from.y() + to.y()) / 2;
                            int color = entry.kind() == PlanDependencyLayout.Kind.CYCLE ? ACCENT : 0xFF74747C;
                            stroke(graphics, from.x(), from.y() + 11, from.x(), middle, color, area, halfStroke);
                            stroke(graphics, from.x(), middle, to.x(), middle, color, area, halfStroke);
                            stroke(graphics, to.x(), middle, to.x(), to.y() - 11, color, area, halfStroke);
                        }
                    } else if (includeLink(id, group)) drawLink(graphics, id, area, false, scale, halfStroke);
                } else if (nodeCursor < nodes.size()) {
                    int id = nodes.get(nodeCursor++);
                    if (dependency) {
                        var entry = tree.entries().get(id);
                        drawNode(graphics, entry.node(), entry.point(), false, scale);
                        drawTreeDecoration(graphics, entry, scale, amounts);
                    } else if (includeNode(id, group)) drawNode(graphics, id, fullLayout.points().get(id), false, scale);
                } else return true;
            } while (++work < 128 && System.nanoTime() < deadline);
            return ringCursor == rings.size() && linkCursor == links.size() && nodeCursor == nodes.size();
        }

        @Override
        public int progress() {
            int total = rings.size() + links.size() + nodes.size();
            return total == 0 ? 100 : (int) (100L * (ringCursor + linkCursor + nodeCursor) / total);
        }
    }

    private boolean includeNode(int id, int group) {
        return (!missingOnly || filteredNodes.get(id)) && (group < 0 || model.topology().groupOf(id) == group);
    }

    private boolean includeLink(int id, int group) {
        var edge = fullLayout.links().get(id).edge();
        return includeNode(edge.from(), group) && includeNode(edge.to(), group);
    }

    private void drawGraphOverlay(GuiGraphics graphics, PlanGraphLayout.Box viewport, int mouseX, int mouseY, boolean liveNodes) {
        var selection = pick(mouseX, mouseY);
        if (selection != null) {
            hoveredNode = selection.node();
            hoveredEntry = selection.entry();
            if (page > 0 && zoom >= 0.25) {
                int group = page < 2 ? -1 : fullLayout.rings().get(page - 2).group();
                double half = Math.max(0.35, 0.5 / (zoom * minecraft.getWindow().getGuiScale()));
                for (int id : model.incidentLinks()[hoveredNode]) if (includeLink(id, group) && fullLayout.links().get(id).bounds().intersects(viewport))
                    drawLink(graphics, id, viewport, true, zoom, half);
                graphics.flush();
            }
        }
        if (liveNodes) {
            drawLiveNodes(graphics);
        } else if (selection != null) {
            drawNode(graphics, selection.node(), selection.point(), true, zoom);
            if (page == 0) drawTreeDecoration(graphics, tree.entries().get(selection.entry()), zoom, GraphViewSettings.get().showAmounts);
        }
        if (page == 0 && selectedEntry < tree.entries().size()) {
            var p = tree.entries().get(selectedEntry).point();
            if (new PlanGraphLayout.Box(p.x() - 13, p.y() - 13, 26, 26).intersects(viewport))
                graphics.renderOutline((int) p.x() - 13, (int) p.y() - 13, 26, 26, 0xFF468C98);
        }
    }

    private boolean prepareNodeView(PlanGraphLayout.Box viewport) {
        // Cull against the viewport with room for models that extend outside the normal item slot.
        var area = new PlanGraphLayout.Box(viewport.x() - 32, viewport.y() - 32, viewport.width() + 64, viewport.height() + 64);
        var next = new NodeView(page == 0 ? tree : fullLayout, tree, page, missingOnly, area);
        if (!next.equals(nodeView)) {
            int group = page < 2 ? -1 : fullLayout.rings().get(page - 2).group();
            // Stop as soon as the live budget is exceeded, even if the whole graph is visible.
            var visible = page == 0 ? tree.visibleEntries(area, MAX_LIVE_NODES + 1) :
                    fullLayout.visibleNodes(area, MAX_LIVE_NODES + 1, id -> includeNode(id, group));
            // Different thresholds prevent panning near the limit from repeatedly rebuilding tiles.
            int limit = next.sameDiagram(nodeView) && !liveNodes ? RESUME_LIVE_NODES : MAX_LIVE_NODES;
            liveNodes = visible.size() <= limit;
            liveNodeIds = liveNodes ? visible : List.of();
            nodeView = next;
        }
        return liveNodes;
    }

    private void drawLiveNodes(GuiGraphics graphics) {
        // The only clip is the graph viewport, never an individual tile or node rectangle.
        // Reuse the visibility query; a stationary view never scans the graph again.
        if (page == 0) {
            boolean amounts = GraphViewSettings.get().showAmounts;
            for (int id : liveNodeIds) {
                var entry = tree.entries().get(id);
                drawNode(graphics, entry.node(), entry.point(), id == hoveredEntry, zoom);
                drawTreeDecoration(graphics, entry, zoom, amounts);
            }
        } else {
            for (int id : liveNodeIds) {
                drawNode(graphics, id, fullLayout.points().get(id), id == hoveredNode, zoom);
            }
        }
    }

    private void drawTreeDecoration(GuiGraphics graphics, PlanDependencyLayout.Entry entry, double scale, boolean amounts) {
        var p = entry.point();
        String marker = switch (entry.kind()) {
            case NORMAL -> "";
            case CYCLE -> "↩";
            case SHARED -> "↗";
            case COLLAPSED -> "+";
            case MORE -> "…";
        };
        if (!marker.isEmpty()) graphics.drawString(font, marker, (int) p.x() + 7, (int) p.y() - 15, ACCENT, false);
        if (amounts && scale >= 0.4) {
            graphics.pose().pushPose();
            graphics.pose().translate(p.x(), p.y() + 9, 180);
            graphics.pose().scale(0.5f, 0.5f, 0.5f);
            String amount = compact(nodeAmount(entry.node()));
            graphics.drawString(font, amount, -font.width(amount) / 2, 0, INK, false);
            graphics.pose().popPose();
        }
    }

    private void fitView() {
        if (model == null) return;
        if (page == 0 && tree != null && !tree.entries().isEmpty()) {
            var box = tree.bounds();
            zoom = Math.max(0.01, Math.min(1, Math.min((viewWidth() - 20) / box.width(), (viewHeight() - 20) / box.height())));
            panX = viewWidth() / 2.0 - (box.x() + box.width() / 2) * zoom;
            panY = viewHeight() / 2.0 - (box.y() + box.height() / 2) * zoom;
        } else resetView();
    }

    private BigInteger nodeAmount(int id) {
        var node = model.topology().nodes().get(id);
        if (node.recipe() != null) return model.byRecipe().get(node.recipe()).count();
        if (node.resource().equals(target.what())) return BigInteger.valueOf(target.amount());
        return model.used().getOrDefault(node.resource(), model.crafted().getOrDefault(node.resource(), BigInteger.ZERO));
    }

    private void drawLink(GuiGraphics graphics, int id, PlanGraphLayout.Box viewport, boolean selected, double scale, double half) {
        var link = fullLayout.links().get(id);
        int color = selected ? 0xFF237E92 : link.cyclic() ? ACCENT : 0xFF909096;
        var points = link.path();
        for (int i = 1; i < points.size(); i++) stroke(graphics, points.get(i - 1).x(), points.get(i - 1).y(), points.get(i).x(), points.get(i).y(), color, viewport, half);
        if (scale >= 0.5 || selected) {
            var end = points.get(points.size() - 1);
            var before = points.get(points.size() - 2);
            double dx = end.x() - before.x(), dy = end.y() - before.y(), length = Math.hypot(dx, dy);
            if (length > 0.001) {
                double ux = dx / length * 4, uy = dy / length * 4;
                stroke(graphics, end.x(), end.y(), end.x() - ux - uy * 0.7, end.y() - uy + ux * 0.7, color, viewport, half);
                stroke(graphics, end.x(), end.y(), end.x() - ux + uy * 0.7, end.y() - uy - ux * 0.7, color, viewport, half);
            }
        }
        if (selected && scale >= 0.5) {
            var p = points.get(points.size() / 2);
            graphics.drawString(font, compact(link.edge().perRun()), (int) p.x() + 2, (int) p.y() - 9, INK, false);
        }
    }

    private void drawNode(GuiGraphics graphics, int id, PlanGraphLayout.Point p, boolean hovered, double scale) {
        var node = model.topology().nodes().get(id);
        int x = (int) p.x() - 8, y = (int) p.y() - 8;
        var resource = node.resource() == null ? null : model.byResource().get(node.resource());
        boolean seed = resource != null && resource.seed() > 0;
        boolean initial = node.resource() != null && model.initialInputs().contains(node.resource());
        graphics.fill(x - 3, y - 3, x + 19, y + 19, hovered ? 0xFFF3EEF9 : resource != null && resource.missing().signum() > 0 ? 0xFFE7C4C4 : 0xFFBDBCC6);
        graphics.renderOutline(x - 3, y - 3, 22, 22, seed ? SEED : initial ? INITIAL : hovered ? ACCENT : 0xFF777580);
        // Spatial culling limits work. A zoom threshold must not turn materials into empty boxes.
        if (node.resource() != null) AEKeyRendering.drawInGui(minecraft, graphics, x, y, node.resource());
        else Icon.CRAFT_HAMMER.getBlitter().dest(x, y).blit(graphics);
        if (seed || initial) {
            graphics.fill(x - 4, y - 5, x + 8, y + 4, seed ? SEED : INITIAL);
            if (scale >= 0.5) graphics.drawString(font, text(seed ? "seed_badge" : "initial_badge"), x - 3, y - 5, 0xFFFFFFFF, false);
        }
    }

    /** Float quads with overlapping joins; minimum screen width survives arbitrary zoom. */
    private void stroke(GuiGraphics graphics, double ax, double ay, double bx, double by, int color, PlanGraphLayout.Box viewport, double half) {
        if (Math.max(ax, bx) + half < viewport.x() || Math.min(ax, bx) - half > viewport.x() + viewport.width() ||
                Math.max(ay, by) + half < viewport.y() || Math.min(ay, by) - half > viewport.y() + viewport.height())
            return;
        double dx = bx - ax, dy = by - ay, length = Math.hypot(dx, dy);
        if (length < 0.0001) return;
        double ex = dx / length * half, ey = dy / length * half;
        ax -= ex;
        ay -= ey;
        bx += ex;
        by += ey;
        var matrix = graphics.pose().last().pose();
        var buffer = graphics.bufferSource().getBuffer(RenderType.gui());
        buffer.vertex(matrix, (float) (ax - ey), (float) (ay + ex), 0).color(color).endVertex();
        buffer.vertex(matrix, (float) (bx - ey), (float) (by + ex), 0).color(color).endVertex();
        buffer.vertex(matrix, (float) (bx + ey), (float) (by - ex), 0).color(color).endVertex();
        buffer.vertex(matrix, (float) (ax + ey), (float) (ay - ex), 0).color(color).endVertex();
    }

    private void drawRecipe(GuiGraphics graphics) {
        if (model.recipes().isEmpty()) {
            centered(graphics, text("no_recipes"), 128, INK);
            return;
        }
        var row = model.recipes().get(page);
        graphics.drawString(font, text("runs", compact(row.count())), 12, 31, ACCENT, false);
        hits.add(new Hit(12, 27, 180, 14, List.of(text("runs", row.count().toString()))));
        int pages = Math.max(1, (Math.max(row.inputs().size(), row.outputs().size()) + ioRows() - 1) / ioRows());
        nodePage = Math.max(0, Math.min(nodePage, pages - 1));
        graphics.drawString(font, text("inputs"), 14, 48, MUTED, false);
        graphics.drawString(font, text("outputs"), imageWidth / 2 + 6, 48, MUTED, false);
        right(graphics, text("io", nodePage + 1, pages).getString(), imageWidth - 12, 31, MUTED);
        drawStacks(graphics, row.inputs(), row.count(), 14);
        drawStacks(graphics, row.outputs(), row.count(), imageWidth / 2 + 6);
    }

    private void drawStacks(GuiGraphics graphics, List<GenericStack> stacks, BigInteger runs, int x) {
        int columnWidth = imageWidth / 2 - 24;
        for (int i = nodePage * ioRows(); i < Math.min(stacks.size(), nodePage * ioRows() + ioRows()); i++) {
            var stack = stacks.get(i);
            int y = 64 + (i % ioRows()) * 18;
            var tooltip = List.of(stack.what().getDisplayName(), text("per_run", Long.toString(stack.amount())),
                    text("total", BigInteger.valueOf(stack.amount()).multiply(runs).toString()));
            icon(graphics, stack.what(), x, y, tooltip);
            graphics.drawString(font, font.plainSubstrByWidth(stack.what().getDisplayName().getString(), columnWidth - 58), x + 20, y + 4, INK, false);
            right(graphics, compact(stack.amount()), x + columnWidth, y + 4, ACCENT);
            hits.add(new Hit(x + 20, y, columnWidth - 18, 17, tooltip));
        }
    }

    private List<Component> recipeTooltip(GraphRingView.Row row) {
        return List.of(text("recipe_for", row.icon().what().getDisplayName()), text("runs", row.count().toString()), text("open_recipe"));
    }

    private static String compact(long amount) {
        return compact(BigInteger.valueOf(amount));
    }

    private static String compact(BigInteger amount) {
        return amount.compareTo(BigInteger.valueOf(10_000)) < 0 ? amount.toString() : new java.math.BigDecimal(amount)
                .round(new java.math.MathContext(3, java.math.RoundingMode.DOWN)).stripTrailingZeros().toEngineeringString();
    }

    private void drawSteps(GuiGraphics graphics) {
        graphics.drawString(font, text("compressed"), 12, 31, MUTED, false);
        int size = rows.size() - graphRows;
        for (int i = page * stepRows(); i < Math.min(size, page * stepRows() + stepRows()); i++) {
            var row = rows.get(graphRows + i);
            int y = VIEW_Y + 4 + (i % stepRows()) * 16;
            Component line = switch (row.kind()) {
                case REPEAT -> text("repeat", row.count().toString());
                case SEQUENCE -> text("sequence");
                case REFERENCE -> text("program_reference", Integer.parseInt(row.id()) - graphRows + 1);
                default -> {
                    var recipe = model.byRecipe().get(row.id());
                    yield text("batch", recipe == null ? row.id() : recipe.icon().what().getDisplayName(), row.count().toString());
                }
            };
            int depth = 0, parent = row.parent();
            while (parent >= 0 && depth < 7) {
                depth++;
                parent = rows.get(parent).parent();
            }
            right(graphics, Integer.toString(i + 1), 46, y, MUTED);
            graphics.drawString(font, font.plainSubstrByWidth(line.getString(), imageWidth - 67 - depth * 8), 52 + depth * 8, y, INK, false);
            hits.add(new Hit(14, y, imageWidth - 28, 15, List.of(line)));
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
            double nextZoom = Math.max(0.01, Math.min(4, zoom * Math.pow(1.2, delta)));
            double mx = x - getGuiLeft() - VIEW_X, my = y - getGuiTop() - VIEW_Y;
            panX = mx - (mx - panX) * nextZoom / zoom;
            panY = my - (my - panY) * nextZoom / zoom;
            zoom = nextZoom;
            return true;
        }
        if (model != null && mode != 1 && x >= getGuiLeft() + VIEW_X && x < getGuiLeft() + imageWidth - VIEW_X &&
                y >= getGuiTop() + VIEW_Y && y < getGuiTop() + VIEW_Y + viewHeight()) {
            if (mode == 2) nodePage = Math.max(0, nodePage + (delta < 0 ? 1 : -1));
            else turn(delta < 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(x, y, delta);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (mode == 3 && model != null && button == 0 && x >= getGuiLeft() + VIEW_X && x < getGuiLeft() + VIEW_X + viewWidth() &&
                y >= getGuiTop() + VIEW_Y + 4 && y < getGuiTop() + VIEW_Y + viewHeight()) {
            int index = graphRows + page * stepRows() + (int) ((y - getGuiTop() - VIEW_Y - 4) / 16);
            if (index < rows.size() && rows.get(index).kind() == GraphRingView.Kind.REFERENCE) {
                page = (Integer.parseInt(rows.get(index).id()) - graphRows) / stepRows();
                return true;
            }
        }
        if (inGraph(x, y) && button == 2) {
            resetView();
            return true;
        }
        if (inGraph(x, y) && (button == 0 || button == 1)) {
            dragging = true;
            dragButton = button;
            dragDistance = 0;
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (dragging && button == dragButton) {
            panX += dx;
            panY += dy;
            dragDistance += Math.abs(dx) + Math.abs(dy);
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (dragging && button == dragButton) {
            dragging = false;
            var selection = dragDistance < 3 ? pick(x, y) : null;
            if (dragDistance < 3 && selection != null) {
                selectedEntry = Math.max(0, selection.entry());
                open(selection.node(), button == 1, hasControlDown());
            }
            return true;
        }
        return super.mouseReleased(x, y, button);
    }

    private record Selection(int node, int entry, PlanGraphLayout.Point point) {}

    private Selection pick(double x, double y) {
        if ((dragging && dragDistance >= 3) || !inGraph(x, y)) return null;
        double mx = (x - getGuiLeft() - VIEW_X - panX) / zoom, my = (y - getGuiTop() - VIEW_Y - panY) / zoom;
        var area = new PlanGraphLayout.Box(mx - 11, my - 11, 22, 22);
        if (page == 0 && tree != null) {
            for (int id : tree.visibleEntries(area)) {
                var entry = tree.entries().get(id);
                if (Math.abs(mx - entry.point().x()) <= 11 && Math.abs(my - entry.point().y()) <= 11)
                    return new Selection(entry.node(), id, entry.point());
            }
        } else if (page > 0 && fullLayout != null) {
            int group = page < 2 ? -1 : fullLayout.rings().get(page - 2).group();
            for (int id : fullLayout.visibleNodes(area)) {
                var point = fullLayout.points().get(id);
                if (missingOnly && !filteredNodes.get(id)) continue;
                if ((group < 0 || group == model.topology().groupOf(id)) && Math.abs(mx - point.x()) <= 11 && Math.abs(my - point.y()) <= 11)
                    return new Selection(id, -1, point);
            }
        }
        return null;
    }

    @Override
    public StackWithBounds getStackUnderMouse(double x, double y) {
        var selection = pick(x, y);
        if (selection == null) return null;
        var key = model.topology().nodes().get(selection.node()).resource();
        if (key == null) return null;
        int left = (int) (getGuiLeft() + VIEW_X + panX + (selection.point().x() - 11) * zoom);
        int top = (int) (getGuiTop() + VIEW_Y + panY + (selection.point().y() - 11) * zoom);
        return new StackWithBounds(new GenericStack(key, 1), new Rect2i(left, top, Math.max(1, (int) Math.ceil(22 * zoom)), Math.max(1, (int) Math.ceil(22 * zoom))));
    }

    private void open(int id, boolean uses, boolean focusOnly) {
        var node = model.topology().nodes().get(id);
        if (focusOnly && model.dependencies().hasDependencies(id)) {
            page = 0;
            focus(new Focus(id, 0));
        } else if (node.resource() == null) {
            select(2);
            page = model.recipes().indexOf(model.byRecipe().get(node.recipe()));
        } else if (LDLib.isJeiLoaded()) {
            JeiMissingIngredientBookmarks.showRecipes(node.resource(), uses);
        }
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (mode == 1 && page == 0 && tree != null && !tree.entries().isEmpty()) {
            if (key == 259) {
                previousFocus();
                return true;
            }
            if (key == 268) {
                home();
                return true;
            }
            if (key == 82 || key == 85 || key == 257) {
                int id = hoveredNode >= 0 ? hoveredNode : tree.entries().get(selectedEntry).node();
                open(id, key == 85, key == 257 || hasControlDown());
                return true;
            }
            int next = selectedEntry;
            var entry = tree.entries().get(selectedEntry);
            if (key == 265 && entry.parent() >= 0) next = entry.parent();
            if (key == 264) {
                for (int i = selectedEntry + 1; i < tree.entries().size(); i++) if (tree.entries().get(i).parent() == selectedEntry) {
                    next = i;
                    break;
                }
            }
            if (key == 262 || key == 263) {
                int direction = key == 262 ? 1 : -1;
                for (int i = selectedEntry + direction; i >= 0 && i < tree.entries().size(); i += direction)
                    if (tree.entries().get(i).parent() == entry.parent()) {
                        next = i;
                        break;
                    }
            }
            if (key >= 262 && key <= 265) {
                selectedEntry = next;
                var point = tree.entries().get(next).point();
                zoom = Math.max(0.75, zoom);
                panX = viewWidth() / 2.0 - point.x() * zoom;
                panY = viewHeight() / 3.0 - point.y() * zoom;
                return true;
            }
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    private void exportDiagram() {
        if (model == null || tree == null || export != null) return;
        List<GraphDiagramExporter.Node> nodes = new ArrayList<>();
        List<GraphDiagramExporter.Line> links = new ArrayList<>();
        if (page == 0 || mode != 1) {
            for (var entry : tree.entries()) {
                nodes.add(exportNode(entry.node(), entry.point(), entry.kind().name()));
                if (entry.parent() < 0) continue;
                var from = tree.entries().get(entry.parent()).point();
                var to = entry.point();
                double middle = (from.y() + to.y()) / 2;
                links.add(new GraphDiagramExporter.Line(from.x(), from.y(), from.x(), middle));
                links.add(new GraphDiagramExporter.Line(from.x(), middle, to.x(), middle));
                links.add(new GraphDiagramExporter.Line(to.x(), middle, to.x(), to.y()));
            }
        } else if (fullLayout != null) {
            int group = page < 2 ? -1 : fullLayout.rings().get(page - 2).group();
            BitSet included = new BitSet();
            for (var node : model.topology().nodes()) if ((group < 0 || model.topology().groupOf(node.id()) == group) && (!missingOnly || filteredNodes.get(node.id()))) {
                included.set(node.id());
                nodes.add(exportNode(node.id(), fullLayout.points().get(node.id()), "NORMAL"));
            }
            for (var link : fullLayout.links()) if (included.get(link.edge().from()) && included.get(link.edge().to())) {
                for (int i = 1; i < link.path().size(); i++) {
                    var a = link.path().get(i - 1);
                    var b = link.path().get(i);
                    links.add(new GraphDiagramExporter.Line(a.x(), a.y(), b.x(), b.y()));
                }
            }
        }
        if (!nodes.isEmpty()) export = new GraphDiagramExporter.Job(target.what().getDisplayName().getString(), nodes, links, GraphViewSettings.get().screenshotAmounts);
    }

    private GraphDiagramExporter.Node exportNode(int id, PlanGraphLayout.Point point, String reference) {
        var node = model.topology().nodes().get(id);
        var row = node.resource() == null ? null : model.byResource().get(node.resource());
        var key = node.resource() == null ? model.byRecipe().get(node.recipe()).icon().what() : node.resource();
        String label = node.resource() == null ? text("recipe_for", key.getDisplayName()).getString() : key.getDisplayName().getString();
        var amount = nodeAmount(id);
        return new GraphDiagramExporter.Node(point.x(), point.y(), node.resource() == null ? null : key, label, compact(amount), amount.toString(),
                row != null && row.missing().signum() > 0, row != null && row.seed() > 0,
                node.resource() != null && model.initialInputs().contains(key) ? row.count().toString() : "", reference);
    }
}
