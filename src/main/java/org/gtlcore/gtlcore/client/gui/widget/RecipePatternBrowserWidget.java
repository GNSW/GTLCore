package org.gtlcore.gtlcore.client.gui.widget;

import org.gtlcore.gtlcore.api.gui.RecipePatternUiTextures;
import org.gtlcore.gtlcore.client.ae2.wireless.UniversalSearch;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MERecipePatternBufferPartMachine;
import org.gtlcore.gtlcore.integration.ae2.handler.MERecipePatternFilter;
import org.gtlcore.gtlcore.integration.ae2.handler.MERecipePatternFilter.Rule;
import org.gtlcore.gtlcore.integration.ae2.handler.MERecipePatternFilter.Target;
import org.gtlcore.gtlcore.integration.ae2.handler.MERecipePatternHelper.RecipePattern;

import com.lowdragmc.lowdraglib.gui.texture.*;
import com.lowdragmc.lowdraglib.gui.widget.*;
import com.lowdragmc.lowdraglib.misc.FluidStorage;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import com.google.common.primitives.Ints;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Only paged search results and the selected pattern are sent to the client, never the entire recipe map. */
public class RecipePatternBrowserWidget extends WidgetGroup {

    private static final String PREFIX = "gtceu.machine.me_recipe_pattern_buffer.";
    private static final int PAGE_SIZE = 5;
    private static final int RECIPE_LIST_PAGE_SIZE = 4;
    private static final int RULE_PAGE_SIZE = 4;
    private static final int STACK_PAGE_SIZE = 9;
    private static final int TEXT_COLOR = RecipePatternUiTextures.TEXT_COLOR;
    private static final int MUTED_COLOR = RecipePatternUiTextures.MUTED_COLOR;
    private static final int PANEL_COLOR = RecipePatternUiTextures.PANEL_COLOR;
    private static final int PANEL_BORDER = 0xFF7E7E7E;
    private static final int MAX_LOCALIZED_MATERIALS = 8192;
    private static final int SNAPSHOT = 10;
    private static final int SEARCH = 20;
    private static final int SEARCH_PAGE = 21;
    private static final int SELECT = 22;
    private static final int BLACKLIST = 23;
    private static final int PRIMARY = 24;
    private static final int ADD_RULE = 25;
    private static final int REMOVE_RULE = 26;
    private static final int RECIPE_LIST_PAGE = 27;
    private static final int RULE_PAGE = 28;
    private static final int WHITELIST = 29;
    private static final int FILTER_MODE = 30;

    public enum Page {
        OVERVIEW,
        SEARCH,
        FILTERS
    }

    private enum FilterMode {
        BLACKLIST,
        WHITELIST
    }

    private final MERecipePatternBufferPartMachine machine;
    private final Page page;
    private FilterMode filterMode = FilterMode.BLACKLIST;
    private final WidgetGroup recipeRows = new WidgetGroup(8, 48, 136, PAGE_SIZE * 28).setClientSideWidget();
    private final WidgetGroup inputSlots = new WidgetGroup(160, 90, 162, 18).setClientSideWidget();
    private final WidgetGroup outputSlots = new WidgetGroup(160, 140, 162, 18).setClientSideWidget();
    private final WidgetGroup ruleRows = new WidgetGroup(10, 78, 148, RULE_PAGE_SIZE * 28).setClientSideWidget();
    private final WidgetGroup recipeListRows = new WidgetGroup(176, 78, 146, RECIPE_LIST_PAGE_SIZE * 28).setClientSideWidget();
    private String search = "";
    private Set<String> localizedMaterials = Set.of();
    private List<RecipePattern> searchResults = List.of();
    private String selectedId = "";
    private List<GenericStack> selectedInputs = List.of();
    private List<GenericStack> selectedOutputs = List.of();
    private AEKey selectedDisplay;
    private AEKey selectedPrimary;
    private int selectedStatus;
    private boolean selectedWhitelisted;
    private boolean whitelistActive;
    private int searchPage, recipeListPage, rulePage, inputPage, outputPage;
    private int searchCount, recipeListCount, ruleCount, publishedCount, skippedCount;
    private int blockedCount, chancedPrimaryCount, ruleFilteredCount, activeBlacklistCount;
    private int whitelistFilteredCount;
    private Target ruleTarget = Target.RECIPE_ID;
    private String ruleExpression = "";
    private long lastRevision = -1;
    private boolean dirty = true;

    public RecipePatternBrowserWidget(int x, int y, MERecipePatternBufferPartMachine machine, Page page) {
        super(x, y, page == Page.OVERVIEW ? 232 : 332, page == Page.OVERVIEW ? 84 : 226);
        this.machine = machine;
        this.page = page;
        switch (page) {
            case OVERVIEW -> addWidget(createOverview());
            case SEARCH -> addWidget(createSearchPage());
            case FILTERS -> addWidget(createRecipeListPage());
        }
    }

    private WidgetGroup createOverview() {
        WidgetGroup view = new WidgetGroup(0, 0, 232, 84).setClientSideWidget();
        addCounter(view, 0, "published_label", () -> publishedCount, RecipePatternUiTextures.COUNTER_PUBLISHED_COLOR);
        addCounter(view, 80, "blocked_label", () -> blockedCount, RecipePatternUiTextures.COUNTER_ERROR_COLOR);
        addCounter(view, 160, "skipped_label", () -> skippedCount, RecipePatternUiTextures.COUNTER_MUTED_COLOR);
        view.addWidget(line(0, 48, 232, 14, this::blockedCounts, 0xFF616978)
                .setHoverTooltips(Component.translatable(PREFIX + "counts_hint")));
        view.addWidget(line(0, 68, 232, 14, () -> text("navigation_hint"), 0xFF616978));
        return view;
    }

    private void addCounter(WidgetGroup view, int x, String label, Supplier<Integer> count, int color) {
        view.addWidget(new ImageWidget(x, 0, 72, 40, RecipePatternUiTextures.COUNTER_BACKGROUND));
        view.addWidget(new ImageWidget(x + 6, 4, 60, 12,
                new TextTexture(() -> text(label)).setColor(RecipePatternUiTextures.COUNTER_TEXT_COLOR).setDropShadow(false)
                        .setWidth(60).setType(TextTexture.TextType.LEFT_ROLL)));
        view.addWidget(new ImageWidget(x + 6, 19, 60, 16,
                new TextTexture(() -> Integer.toString(count.get())).setColor(color).setDropShadow(false).setWidth(60))
                .setHoverTooltips(Component.translatable(PREFIX + "counts_hint")));
    }

    private WidgetGroup createSearchPage() {
        WidgetGroup view = new WidgetGroup(0, 0, 332, 226).setClientSideWidget();
        TextFieldWidget field = new TextFieldWidget(4, 4, 270, 18, () -> search, value -> search = value) {

            @Override
            public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                if (isFocus() && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
                    submitSearch();
                    return true;
                }
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }.setMaxStringLength(MERecipePatternFilter.MAX_EXPRESSION_LENGTH)
                .setBordered(false).setTextColor(0xFFFFFFFF).setBackground(RecipePatternUiTextures.INPUT_BACKGROUND);
        field.setHoverTooltips(Component.translatable(PREFIX + "search_hint"));
        enableMaterialDrop(field, value -> search = value);
        view.addWidget(field);
        view.addWidget(button(280, 4, 48, 18, () -> text("search"), this::submitSearch));
        view.addWidget(panel(4, 28, 144, 178));
        view.addWidget(panel(154, 28, 174, 178));
        view.addWidget(line(8, 31, 136, 12, () -> text("results_label", searchCount), MUTED_COLOR));
        view.addWidget(recipeRows);
        addPager(view, 8, 190, 136, () -> searchPage, () -> pages(searchCount, PAGE_SIZE), delta -> sendPage(SEARCH_PAGE, delta));
        view.addWidget(line(160, 31, 162, 12, () -> selectedDisplay == null ? text("select_recipe") : selectedDisplay.getDisplayName().getString(), TEXT_COLOR));
        view.addWidget(line(160, 45, 162, 12, () -> selectedId, MUTED_COLOR));
        view.addWidget(line(160, 59, 162, 12, () -> selectedId.isEmpty() ? "" : text("status." + selectedStatus), MUTED_COLOR));
        view.addWidget(new ImageWidget(160, 73, 162, 1, new ColorRectTexture(PANEL_BORDER)));
        view.addWidget(line(160, 76, 162, 12, () -> text("inputs"), TEXT_COLOR));
        view.addWidget(line(160, 126, 162, 12, () -> text("outputs_short"), TEXT_COLOR)
                .setHoverTooltips(Component.translatable(PREFIX + "primary_hint")));
        view.addWidget(inputSlots);
        view.addWidget(outputSlots);
        addPager(view, 160, 110, 162, () -> inputPage, () -> pages(selectedInputs.size(), STACK_PAGE_SIZE), delta -> changeStackPage(false, delta));
        addPager(view, 160, 160, 162, () -> outputPage, () -> pages(selectedOutputs.size(), STACK_PAGE_SIZE), delta -> changeStackPage(true, delta));
        view.addWidget(button(160, 181, 50, 18, () -> text(selectedStatus == 1 ? "unblacklist_short" : "blacklist_short"), () -> {
            if (!selectedId.isEmpty()) sendBlacklist(selectedId, selectedStatus != 1);
        }).setHoverTooltips(Component.translatable(PREFIX + "blacklist_hint")));
        view.addWidget(button(214, 181, 50, 18, () -> text(selectedWhitelisted ? "unwhitelist_short" : "whitelist_short"), () -> {
            if (!selectedId.isEmpty()) sendWhitelist(selectedId, !selectedWhitelisted);
        }).setHoverTooltips(Component.translatable(PREFIX + "whitelist_hint")));
        view.addWidget(button(268, 181, 54, 18, () -> text("reset_primary_short"), () -> {
            if (!selectedId.isEmpty()) sendPrimary(selectedId, null);
        }).setHoverTooltips(Component.translatable(PREFIX + "primary_hint")));
        addFooter(view);
        return view;
    }

    private WidgetGroup createRecipeListPage() {
        WidgetGroup view = new WidgetGroup(0, 0, 332, 226).setClientSideWidget();
        view.addWidget(button(4, 0, 16, 12, () -> "<", () -> changeFilterMode(-1))
                .setHoverTooltips(Component.translatable(PREFIX + "lists_hint")));
        view.addWidget(button(312, 0, 16, 12, () -> ">", () -> changeFilterMode(1))
                .setHoverTooltips(Component.translatable(PREFIX + "lists_hint")));
        view.addWidget(new ImageWidget(24, 0, 284, 16,
                new TextTexture(() -> text("list_mode", text(isWhitelistMode() ? "whitelist_tab" : "blacklist_tab"), filterMode.ordinal() + 1))
                        .setColor(TEXT_COLOR).setDropShadow(false).setWidth(284))
                .setHoverTooltips(Component.translatable(PREFIX + "lists_hint")));
        view.addWidget(line(4, 18, 324, 12,
                () -> isWhitelistMode() ? text(whitelistActive ? "whitelist_active" : "whitelist_inactive", whitelistFilteredCount) : blockedCounts(), MUTED_COLOR)
                .setHoverTooltips(Component.translatable(PREFIX + "lists_hint"), Component.translatable(PREFIX + "counts_hint")));
        view.addWidget(button(4, 32, 74, 18, () -> Component.translatable(ruleTarget.translationKey()).getString(),
                () -> ruleTarget = Target.values()[(ruleTarget.ordinal() + 1) % Target.values().length]));
        TextFieldWidget field = new TextFieldWidget(82, 32, 206, 18, () -> ruleExpression, value -> ruleExpression = value)
                .setMaxStringLength(MERecipePatternFilter.MAX_EXPRESSION_LENGTH)
                .setBordered(false).setTextColor(0xFFFFFFFF).setBackground(RecipePatternUiTextures.INPUT_BACKGROUND);
        field.setHoverTooltips(Component.translatable(PREFIX + "rule_hint"), Component.translatable(PREFIX + "lists_hint"));
        enableMaterialDrop(field, value -> ruleExpression = value);
        view.addWidget(field);
        view.addWidget(button(292, 32, 36, 18, () -> text("add_rule"), () -> {
            if (ruleExpression.isBlank()) return;
            send(ADD_RULE, buffer -> {
                buffer.writeEnum(filterMode);
                buffer.writeEnum(ruleTarget);
                buffer.writeUtf(ruleExpression, MERecipePatternFilter.MAX_EXPRESSION_LENGTH);
            });
        }));
        view.addWidget(panel(4, 58, 160, 148));
        view.addWidget(panel(170, 58, 158, 148));
        view.addWidget(line(10, 61, 148, 12, () -> text(isWhitelistMode() ? "whitelist_rules_label" : "rules_label", ruleCount), TEXT_COLOR)
                .setHoverTooltips(Component.translatable(PREFIX + "rule_hint"), Component.translatable(PREFIX + "lists_hint")));
        view.addWidget(line(176, 61, 146, 12, () -> text(isWhitelistMode() ? "whitelist_label" : "blacklist_label", recipeListCount), TEXT_COLOR)
                .setHoverTooltips(Component.translatable(PREFIX + "individual_lists_hint"), Component.translatable(PREFIX + "lists_hint")));
        view.addWidget(ruleRows);
        view.addWidget(recipeListRows);
        addPager(view, 10, 190, 148, () -> rulePage, () -> pages(ruleCount, RULE_PAGE_SIZE), delta -> sendPage(RULE_PAGE, delta));
        addPager(view, 176, 190, 146, () -> recipeListPage, () -> pages(recipeListCount, RECIPE_LIST_PAGE_SIZE), delta -> sendPage(RECIPE_LIST_PAGE, delta));
        addFooter(view);
        return view;
    }

    private String blockedCounts() {
        return text("blocked_counts", chancedPrimaryCount, activeBlacklistCount, ruleFilteredCount, whitelistFilteredCount);
    }

    private List<String> getRecipeList() {
        return isWhitelistMode() ? machine.getWhitelistedRecipes() : machine.getBlacklistedRecipes();
    }

    private List<Rule> getRules() {
        return isWhitelistMode() ? machine.getWhitelistRules() : machine.getFilterRules();
    }

    private boolean isWhitelistMode() {
        return filterMode == FilterMode.WHITELIST;
    }

    private void changeFilterMode(int delta) {
        FilterMode target = FilterMode.values()[Math.floorMod(filterMode.ordinal() + Integer.signum(delta), FilterMode.values().length)];
        send(FILTER_MODE, buffer -> buffer.writeEnum(target));
    }

    private static Widget panel(int x, int y, int width, int height) {
        return new ImageWidget(x, y, width, height, RecipePatternUiTextures.BACKGROUND);
    }

    private static ImageWidget line(int x, int y, int width, int height, Supplier<String> label, int color) {
        return new ImageWidget(x, y, width, height,
                new TextTexture(label).setColor(color).setDropShadow(false).setWidth(width).setType(TextTexture.TextType.LEFT_ROLL));
    }

    private void addFooter(WidgetGroup view) {
        view.addWidget(line(4, 211, 324, 12, () -> text("recipe_counts", publishedCount, blockedCount, skippedCount), MUTED_COLOR)
                .setHoverTooltips(Component.translatable(PREFIX + "counts_hint")));
    }

    private void addPager(WidgetGroup view, int x, int y, int width, Supplier<Integer> current, Supplier<Integer> total, Consumer<Integer> change) {
        view.addWidget(button(x, y, 16, 12, () -> "<", () -> change.accept(-1)));
        view.addWidget(button(x + width - 16, y, 16, 12, () -> ">", () -> change.accept(1)));
        view.addWidget(new ImageWidget(x + 20, y, width - 40, 12,
                new TextTexture(() -> text("page", current.get() + 1, total.get())).setColor(MUTED_COLOR).setDropShadow(false).setWidth(width - 40)));
    }

    private Widget button(int x, int y, int width, int height, Supplier<String> label, Runnable action) {
        return new ButtonWidget(x, y, width, height,
                new GuiTextureGroup(RecipePatternUiTextures.BUTTON, new TextTexture(label).setColor(RecipePatternUiTextures.BUTTON_TEXT_COLOR).setDropShadow(true).setWidth(width - 4).setType(TextTexture.TextType.ROLL)),
                click -> {
                    if (click.isRemote) action.run();
                }).setHoverTexture(RecipePatternUiTextures.BUTTON_HOVER);
    }

    private void enableMaterialDrop(TextFieldWidget field, Consumer<String> responder) {
        field.setDraggingConsumer(value -> materialId(value) != null, null, null, value -> {
            String id = materialId(value);
            if (id != null) {
                responder.accept(id);
                field.setCurrentString(id);
            }
        });
    }

    private static String materialId(Object value) {
        if (value instanceof ItemStack item && !item.isEmpty()) return BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
        if (value instanceof FluidStack fluid && !fluid.isEmpty()) return BuiltInRegistries.FLUID.getKey(fluid.getFluid()).toString();
        if (value instanceof net.minecraftforge.fluids.FluidStack fluid && !fluid.isEmpty()) return BuiltInRegistries.FLUID.getKey(fluid.getFluid()).toString();
        if (value instanceof GenericStack stack) return materialId(stack.what());
        if (value instanceof AEItemKey item) return BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
        if (value instanceof AEFluidKey fluid) return BuiltInRegistries.FLUID.getKey(fluid.getFluid()).toString();
        return null;
    }

    private static Set<Integer> findLocalizedMaterials(String query) {
        if (query.isBlank()) return Set.of();
        // Registry indices keep even broad localized searches within one small client action packet.
        Set<Integer> ids = new LinkedHashSet<>();
        for (var item : BuiltInRegistries.ITEM) {
            if (ids.size() >= MAX_LOCALIZED_MATERIALS) break;
            if (matchesLocalizedName(item.getDescription().getString(), query)) ids.add(BuiltInRegistries.ITEM.getId(item) << 1);
        }
        for (var fluid : BuiltInRegistries.FLUID) {
            if (ids.size() >= MAX_LOCALIZED_MATERIALS) break;
            if (matchesLocalizedName(fluid.getFluidType().getDescription().getString(), query)) ids.add((BuiltInRegistries.FLUID.getId(fluid) << 1) | 1);
        }
        return ids;
    }

    private static boolean matchesLocalizedName(String name, String query) {
        return MERecipePatternFilter.matchesText(name, query) ||
                (query.indexOf('*') < 0 && query.indexOf('?') < 0 && UniversalSearch.contains(name, query.strip()));
    }

    private void send(int action, Consumer<FriendlyByteBuf> writer) {
        writeClientAction(action, writer);
    }

    private void submitSearch() {
        Set<Integer> materials = findLocalizedMaterials(search);
        send(SEARCH, buffer -> {
            buffer.writeUtf(search, MERecipePatternFilter.MAX_EXPRESSION_LENGTH);
            buffer.writeVarInt(materials.size());
            materials.forEach(buffer::writeVarInt);
        });
    }

    private void sendPage(int action, int delta) {
        send(action, buffer -> {
            if (action == RECIPE_LIST_PAGE || action == RULE_PAGE) buffer.writeEnum(filterMode);
            buffer.writeInt(delta);
        });
    }

    private void sendBlacklist(String id, boolean excluded) {
        send(BLACKLIST, buffer -> {
            buffer.writeUtf(id, 1024);
            buffer.writeBoolean(excluded);
        });
    }

    private void sendWhitelist(String id, boolean allowed) {
        send(WHITELIST, buffer -> {
            buffer.writeUtf(id, 1024);
            buffer.writeBoolean(allowed);
        });
    }

    private void sendPrimary(String id, AEKey primary) {
        send(PRIMARY, buffer -> {
            buffer.writeUtf(id, 1024);
            buffer.writeBoolean(primary != null);
            if (primary != null) GenericStack.writeBuffer(new GenericStack(primary, 1), buffer);
        });
    }

    @Override
    public void handleClientAction(int id, FriendlyByteBuf buffer) {
        // Actions retain IDs, complete rules and list modes, even if the view changes before they arrive.
        switch (id) {
            case SEARCH -> {
                search = buffer.readUtf(MERecipePatternFilter.MAX_EXPRESSION_LENGTH);
                int count = buffer.readVarInt();
                if (count < 0 || count > MAX_LOCALIZED_MATERIALS) return;
                Set<String> ids = new HashSet<>();
                for (int index = 0; index < count; index++) {
                    int registryId = buffer.readVarInt();
                    if (registryId < 0) continue;
                    if ((registryId & 1) == 0) {
                        var item = BuiltInRegistries.ITEM.byId(registryId >>> 1);
                        if (item != null) ids.add(BuiltInRegistries.ITEM.getKey(item).toString());
                    } else {
                        var fluid = BuiltInRegistries.FLUID.byId(registryId >>> 1);
                        if (fluid != null) ids.add(BuiltInRegistries.FLUID.getKey(fluid).toString());
                    }
                }
                localizedMaterials = ids;
                searchPage = 0;
                selectedId = "";
            }
            case SEARCH_PAGE -> searchPage = movePage(searchPage, buffer.readInt(), searchCount, PAGE_SIZE);
            case SELECT -> {
                String recipeId = buffer.readUtf(1024);
                if (machine.getRecipePattern(recipeId) != null) selectedId = recipeId;
            }
            case BLACKLIST -> machine.setRecipeBlacklisted(buffer.readUtf(1024), buffer.readBoolean());
            case WHITELIST -> machine.setRecipeWhitelisted(buffer.readUtf(1024), buffer.readBoolean());
            case PRIMARY -> {
                String recipeId = buffer.readUtf(1024);
                GenericStack primary = buffer.readBoolean() ? GenericStack.readBuffer(buffer) : null;
                machine.setPrimaryOutput(recipeId, primary == null ? null : primary.what());
            }
            case ADD_RULE -> {
                FilterMode mode = buffer.readEnum(FilterMode.class);
                Target target = buffer.readEnum(Target.class);
                String expression = buffer.readUtf(MERecipePatternFilter.MAX_EXPRESSION_LENGTH);
                if (page != Page.FILTERS) return;
                if (mode == FilterMode.WHITELIST) machine.addWhitelistRule(target, expression);
                else machine.addFilterRule(target, expression);
            }
            case REMOVE_RULE -> {
                FilterMode mode = buffer.readEnum(FilterMode.class);
                Rule rule = new Rule(buffer.readEnum(Target.class), buffer.readUtf(MERecipePatternFilter.MAX_EXPRESSION_LENGTH));
                if (page != Page.FILTERS) return;
                if (mode == FilterMode.WHITELIST) machine.removeWhitelistRule(rule);
                else machine.removeFilterRule(rule);
            }
            case RECIPE_LIST_PAGE -> {
                FilterMode mode = buffer.readEnum(FilterMode.class);
                int delta = buffer.readInt();
                if (page == Page.FILTERS && mode == filterMode) recipeListPage = movePage(recipeListPage, delta, recipeListCount, RECIPE_LIST_PAGE_SIZE);
            }
            case RULE_PAGE -> {
                FilterMode mode = buffer.readEnum(FilterMode.class);
                int delta = buffer.readInt();
                if (page == Page.FILTERS && mode == filterMode) rulePage = movePage(rulePage, delta, ruleCount, RULE_PAGE_SIZE);
            }
            case FILTER_MODE -> {
                FilterMode mode = buffer.readEnum(FilterMode.class);
                if (page == Page.FILTERS && mode != filterMode) {
                    filterMode = mode;
                    recipeListPage = 0;
                    rulePage = 0;
                }
            }
            default -> {
                super.handleClientAction(id, buffer);
                return;
            }
        }
        dirty = true;
    }

    @Override
    public void writeInitialData(FriendlyByteBuf buffer) {
        super.writeInitialData(buffer);
        refreshView();
        writeSnapshot(buffer);
    }

    @Override
    public void readInitialData(FriendlyByteBuf buffer) {
        super.readInitialData(buffer);
        readSnapshot(buffer);
    }

    @Override
    public void detectAndSendChanges() {
        // All child widgets are client-side views. Their updates are supplied by this bounded snapshot.
        if (dirty || lastRevision != machine.getConfigurationRevision()) {
            refreshView();
            writeUpdateInfo(SNAPSHOT, this::writeSnapshot);
        }
    }

    @Override
    public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
        if (id == SNAPSHOT) readSnapshot(buffer);
        else super.readUpdateInfo(id, buffer);
    }

    private void refreshView() {
        if (page == Page.SEARCH) {
            searchResults = machine.searchRecipes(search, localizedMaterials);
            searchCount = searchResults.size();
            searchPage = clampPage(searchPage, searchCount, PAGE_SIZE);
            if (machine.getRecipePattern(selectedId) == null) selectedId = searchResults.isEmpty() ? "" : searchResults.get(searchPage * PAGE_SIZE).id();
        } else if (page == Page.FILTERS) {
            recipeListCount = getRecipeList().size();
            recipeListPage = clampPage(recipeListPage, recipeListCount, RECIPE_LIST_PAGE_SIZE);
            ruleCount = getRules().size();
            rulePage = clampPage(rulePage, ruleCount, RULE_PAGE_SIZE);
        }
        var statistics = machine.getRecipeStatistics();
        publishedCount = statistics.published();
        skippedCount = statistics.skipped();
        blockedCount = statistics.blocked();
        chancedPrimaryCount = statistics.chancedPrimary();
        activeBlacklistCount = statistics.blacklisted();
        ruleFilteredCount = statistics.ruleFiltered();
        whitelistFilteredCount = statistics.whitelistFiltered();
        whitelistActive = machine.isWhitelistActive();
        lastRevision = machine.getConfigurationRevision();
        dirty = false;
    }

    private void writeSnapshot(FriendlyByteBuf buffer) {
        buffer.writeVarInt(publishedCount);
        buffer.writeVarInt(skippedCount);
        buffer.writeVarInt(blockedCount);
        buffer.writeVarInt(chancedPrimaryCount);
        buffer.writeVarInt(activeBlacklistCount);
        buffer.writeVarInt(ruleFilteredCount);
        buffer.writeVarInt(whitelistFilteredCount);
        buffer.writeBoolean(whitelistActive);
        switch (page) {
            case SEARCH -> writeSearchSnapshot(buffer);
            case FILTERS -> writeRecipeListSnapshot(buffer);
        }
    }

    private void writeSearchSnapshot(FriendlyByteBuf buffer) {
        buffer.writeVarInt(searchPage);
        buffer.writeVarInt(searchCount);
        buffer.writeUtf(selectedId);
        List<RecipePattern> rows = page(searchResults, searchPage, PAGE_SIZE);
        buffer.writeVarInt(rows.size());
        for (RecipePattern pattern : rows) {
            buffer.writeUtf(pattern.id());
            buffer.writeVarInt(machine.getRecipeStatus(pattern.id()));
            GenericStack.writeBuffer(machine.getPreviewPattern(pattern).getPrimaryOutput(), buffer);
        }
        RecipePattern selected = machine.getRecipePattern(selectedId);
        if (selected != null) {
            buffer.writeVarInt(machine.getRecipeStatus(selectedId));
            buffer.writeBoolean(machine.isRecipeWhitelisted(selectedId));
            AEKey primary = machine.getPrimaryOutput(selected);
            buffer.writeBoolean(primary != null);
            if (primary != null) GenericStack.writeBuffer(new GenericStack(primary, 1), buffer);
            IPatternDetails preview = machine.getPreviewPattern(selected);
            List<GenericStack> inputs = Arrays.stream(preview.getInputs()).map(input -> {
                GenericStack stack = input.getPossibleInputs()[0];
                return new GenericStack(stack.what(), Math.multiplyExact(stack.amount(), input.getMultiplier()));
            }).toList();
            writeStacks(buffer, inputs);
            writeStacks(buffer, Arrays.asList(preview.getOutputs()));
        }
    }

    private void writeRecipeListSnapshot(FriendlyByteBuf buffer) {
        buffer.writeEnum(filterMode);
        buffer.writeVarInt(rulePage);
        buffer.writeVarInt(ruleCount);
        List<Rule> rules = page(getRules(), rulePage, RULE_PAGE_SIZE);
        buffer.writeVarInt(rules.size());
        for (Rule rule : rules) {
            buffer.writeEnum(rule.target());
            buffer.writeUtf(rule.expression());
        }
        buffer.writeVarInt(recipeListPage);
        buffer.writeVarInt(recipeListCount);
        List<String> entries = page(getRecipeList(), recipeListPage, RECIPE_LIST_PAGE_SIZE);
        buffer.writeVarInt(entries.size());
        entries.forEach(buffer::writeUtf);
    }

    private void readSnapshot(FriendlyByteBuf buffer) {
        publishedCount = buffer.readVarInt();
        skippedCount = buffer.readVarInt();
        blockedCount = buffer.readVarInt();
        chancedPrimaryCount = buffer.readVarInt();
        activeBlacklistCount = buffer.readVarInt();
        ruleFilteredCount = buffer.readVarInt();
        whitelistFilteredCount = buffer.readVarInt();
        whitelistActive = buffer.readBoolean();
        switch (page) {
            case SEARCH -> readSearchSnapshot(buffer);
            case FILTERS -> readRecipeListSnapshot(buffer);
        }
    }

    private void readSearchSnapshot(FriendlyByteBuf buffer) {
        searchPage = buffer.readVarInt();
        searchCount = buffer.readVarInt();
        String previous = selectedId;
        selectedId = buffer.readUtf();
        if (!previous.equals(selectedId)) {
            inputPage = 0;
            outputPage = 0;
        }
        recipeRows.clearAllWidgets();
        int rowCount = buffer.readVarInt();
        for (int row = 0; row < rowCount; row++) {
            String id = buffer.readUtf();
            int status = buffer.readVarInt();
            GenericStack stack = GenericStack.readBuffer(buffer);
            int y = row * 28;
            Widget entry = new ButtonWidget(0, y, 136, 27,
                    new ColorRectTexture(id.equals(selectedId) ? RecipePatternUiTextures.SELECTED_COLOR : row % 2 == 0 ? RecipePatternUiTextures.ROW_COLOR : PANEL_COLOR),
                    click -> {
                        if (click.isRemote) send(SELECT, packet -> packet.writeUtf(id, 1024));
                    }).setHoverTexture(new ColorRectTexture(RecipePatternUiTextures.HOVER_COLOR))
                    .setHoverTooltips(Component.literal(id), Component.translatable(PREFIX + "status." + status));
            recipeRows.addWidget(entry);
            recipeRows.addWidget(new ImageWidget(0, y, 2, 27, new ColorRectTexture(status == 0 ? RecipePatternUiTextures.COUNTER_PUBLISHED_COLOR : RecipePatternUiTextures.COUNTER_ERROR_COLOR)));
            recipeRows.addWidget(stackWidget(stack, 4, y + 4));
            recipeRows.addWidget(line(26, y + 2, 106, 12, () -> stack.what().getDisplayName().getString(), TEXT_COLOR));
            recipeRows.addWidget(line(26, y + 15, 106, 10, () -> shortRecipeId(id), MUTED_COLOR));
        }
        if (rowCount == 0) recipeRows.addWidget(line(4, 4, 128, 14, () -> text("no_results"), MUTED_COLOR));
        if (!selectedId.isEmpty()) {
            selectedStatus = buffer.readVarInt();
            selectedWhitelisted = buffer.readBoolean();
            selectedPrimary = buffer.readBoolean() ? GenericStack.readBuffer(buffer).what() : null;
            selectedInputs = readStacks(buffer);
            selectedOutputs = readStacks(buffer);
            selectedDisplay = selectedOutputs.isEmpty() ? null : selectedOutputs.get(0).what();
        } else {
            selectedStatus = 0;
            selectedWhitelisted = false;
            selectedPrimary = null;
            selectedInputs = List.of();
            selectedOutputs = List.of();
            selectedDisplay = null;
        }
        displayStacks();
    }

    private void readRecipeListSnapshot(FriendlyByteBuf buffer) {
        filterMode = buffer.readEnum(FilterMode.class);
        FilterMode mode = filterMode;
        rulePage = buffer.readVarInt();
        ruleCount = buffer.readVarInt();
        ruleRows.clearAllWidgets();
        int rules = buffer.readVarInt();
        for (int row = 0; row < rules; row++) {
            Rule rule = new Rule(buffer.readEnum(Target.class), buffer.readUtf());
            int y = row * 28;
            ruleRows.addWidget(new ImageWidget(0, y, 148, 27, new ColorRectTexture(row % 2 == 0 ? RecipePatternUiTextures.ROW_COLOR : PANEL_COLOR)));
            ruleRows.addWidget(line(4, y + 2, 124, 12, () -> Component.translatable(rule.target().translationKey()).getString(), TEXT_COLOR));
            ruleRows.addWidget(line(4, y + 15, 124, 10, rule::expression, MUTED_COLOR)
                    .setHoverTooltips(Component.literal(rule.expression())));
            ruleRows.addWidget(button(130, y + 5, 16, 16, () -> "×", () -> send(REMOVE_RULE, packet -> {
                packet.writeEnum(mode);
                packet.writeEnum(rule.target());
                packet.writeUtf(rule.expression(), MERecipePatternFilter.MAX_EXPRESSION_LENGTH);
            })).setHoverTooltips(Component.translatable(PREFIX + "remove")));
        }
        if (rules == 0) ruleRows.addWidget(line(4, 4, 140, 14, () -> text("no_rules"), MUTED_COLOR));
        recipeListPage = buffer.readVarInt();
        recipeListCount = buffer.readVarInt();
        recipeListRows.clearAllWidgets();
        int excluded = buffer.readVarInt();
        for (int row = 0; row < excluded; row++) {
            String id = buffer.readUtf();
            int y = row * 28;
            recipeListRows.addWidget(new ImageWidget(0, y, 146, 27, new ColorRectTexture(row % 2 == 0 ? RecipePatternUiTextures.ROW_COLOR : PANEL_COLOR)));
            recipeListRows.addWidget(line(4, y + 2, 122, 12, () -> shortRecipeId(id), TEXT_COLOR).setHoverTooltips(Component.literal(id)));
            recipeListRows.addWidget(line(4, y + 15, 122, 10, () -> id.substring(0, Math.max(0, id.indexOf('/'))), MUTED_COLOR));
            recipeListRows.addWidget(button(128, y + 5, 16, 16, () -> "×", () -> {
                if (mode == FilterMode.WHITELIST) sendWhitelist(id, false);
                else sendBlacklist(id, false);
            })
                    .setHoverTooltips(Component.literal(id), Component.translatable(PREFIX + "remove")));
        }
        if (excluded == 0) recipeListRows.addWidget(line(4, 4, 138, 14, () -> text(isWhitelistMode() ? "no_whitelist" : "no_blacklist"), MUTED_COLOR));
    }

    private static String shortRecipeId(String id) {
        return id.substring(id.lastIndexOf('/') + 1);
    }

    private void changeStackPage(boolean output, int delta) {
        if (output) outputPage = movePage(outputPage, delta, selectedOutputs.size(), STACK_PAGE_SIZE);
        else inputPage = movePage(inputPage, delta, selectedInputs.size(), STACK_PAGE_SIZE);
        displayStacks();
    }

    private void displayStacks() {
        inputPage = clampPage(inputPage, selectedInputs.size(), STACK_PAGE_SIZE);
        outputPage = clampPage(outputPage, selectedOutputs.size(), STACK_PAGE_SIZE);
        displayStacks(inputSlots, page(selectedInputs, inputPage, STACK_PAGE_SIZE), false);
        displayStacks(outputSlots, page(selectedOutputs, outputPage, STACK_PAGE_SIZE), true);
    }

    private void displayStacks(WidgetGroup group, List<GenericStack> stacks, boolean output) {
        group.clearAllWidgets();
        for (int index = 0; index < stacks.size(); index++) {
            GenericStack stack = stacks.get(index);
            int x = (index % 9) * 18;
            int y = (index / 9) * 18;
            group.addWidget(stackWidget(stack, x, y));
            if (output) {
                String recipeId = selectedId;
                Widget button = new ButtonWidget(x, y, 18, 18, new ColorRectTexture(0), click -> {
                    if (click.isRemote) sendPrimary(recipeId, stack.what());
                }).setHoverTooltips(stack.what().getDisplayName(), Component.literal(materialId(stack) + " × " + stack.amount()),
                        Component.translatable(PREFIX + (stack.what().equals(selectedPrimary) ? "is_primary" : "make_primary")));
                if (stack.what().equals(selectedPrimary)) button.setBackground(new ColorBorderTexture(1, 0xFF70D090));
                group.addWidget(button);
            }
        }
    }

    private static Widget stackWidget(GenericStack stack, int x, int y) {
        if (stack.what() instanceof AEFluidKey fluid) {
            FluidStorage storage = new FluidStorage(FluidStack.create(fluid.getFluid(), stack.amount(), fluid.getTag()));
            return new TankWidget(storage, x, y, false, false).setBackground(RecipePatternUiTextures.SLOT).setClientSideWidget();
        }
        ItemStackTransfer transfer = new ItemStackTransfer(1);
        if (stack.what() instanceof AEItemKey item) transfer.setStackInSlot(0, item.toStack(Ints.saturatedCast(stack.amount())));
        return new SlotWidget(transfer, 0, x, y, false, false).setBackgroundTexture(RecipePatternUiTextures.SLOT).setClientSideWidget();
    }

    private static void writeStacks(FriendlyByteBuf buffer, List<GenericStack> stacks) {
        buffer.writeVarInt(stacks.size());
        stacks.forEach(stack -> GenericStack.writeBuffer(stack, buffer));
    }

    private static List<GenericStack> readStacks(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<GenericStack> stacks = new ArrayList<>(size);
        for (int index = 0; index < size; index++) stacks.add(GenericStack.readBuffer(buffer));
        return stacks;
    }

    private static <T> List<T> page(List<T> values, int page, int size) {
        int from = Math.min(values.size(), Math.max(0, page) * size);
        return values.subList(from, Math.min(values.size(), from + size));
    }

    private static int pages(int count, int size) {
        return Math.max(1, (count + size - 1) / size);
    }

    private static int clampPage(int page, int count, int size) {
        return Math.max(0, Math.min(page, pages(count, size) - 1));
    }

    private static int movePage(int page, int delta, int count, int size) {
        return clampPage(page + Integer.signum(delta), count, size);
    }

    private static String text(String key, Object... args) {
        return Component.translatable(PREFIX + key, args).getString();
    }
}
