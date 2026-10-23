package org.gtlcore.gtlcore.client.ftbquests;

import org.gtlcore.gtlcore.integration.ftbquests.QuestTranslationCatalog;
import org.gtlcore.gtlcore.integration.ftbquests.QuestTranslationNetwork;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Edits one locale/key at a time. The raw FTB reference, pages and progress never change here. */
public final class QuestTranslationScreen extends Screen {

    private final Screen parent;
    private final Object object;
    private final List<String> initialKeys;
    private final boolean allKeys;
    private List<String> keys;
    private Map<String, List<QuestTranslationAccess.Location>> locations = Map.of();
    private final Map<String, String> names = new LinkedHashMap<>();
    private List<String> visibleKeys = List.of();
    private boolean globalKeys;
    private boolean onlyUntranslated;
    private String nextAfterSave;
    private int listWidth;
    private Button saveNext;
    private Button structure;
    private Button locate;
    private Button scope;
    private Button untranslated;
    private boolean indexReady;
    private String locale = QuestTranslationsClient.language();
    private String key;
    private String expected;
    private String draft = "";
    private String initialDraft = "";
    private String filter = "";
    private String status = "";
    private int page;
    private int listRows;
    private int textX;
    private int sourceY;
    private int targetY;
    private boolean pending;
    private String pendingValue;
    private boolean waiting = true;
    private boolean error;
    private int pendingTicks;
    private EditBox languageBox;
    private EditBox searchBox;
    private MultiLineEditBox sourceBox;
    private MultiLineEditBox translationBox;
    private Button save;
    private Button remove;
    private Button enroll;
    private Button reload;

    public QuestTranslationScreen(Screen parent, Object object, List<String> keys) {
        this(parent, object, keys, true);
    }

    public QuestTranslationScreen(Screen parent, Object object, List<String> keys, boolean allKeys) {
        super(label("editor"));
        this.parent = parent;
        this.object = object;
        this.initialKeys = List.copyOf(keys);
        this.allKeys = allKeys;
        this.keys = new ArrayList<>(keys);
        this.key = keys.isEmpty() ? "" : keys.get(0);
        QuestTranslationsClient.request(locale, true);
    }

    public static Component label(String key) {
        return Component.translatable("gtlcore.quest_i18n." + key);
    }

    @Override
    protected void init() {
        int left = 12;
        listWidth = Math.max(100, Math.min(240, width / 3 - 8));
        textX = left + listWidth + 12;
        int textWidth = width - textX - 12;
        int bodyBottom = height - 60;
        sourceY = 110;
        int sourceHeight = Math.max(24, (height - 210) / 3);
        targetY = sourceY + sourceHeight + 24;
        int targetHeight = Math.max(20, bodyBottom - targetY);
        listRows = Math.max(1, (height - 174) / 32);

        languageBox = addRenderableWidget(new EditBox(font, 64, 34, 65, 18, label("language")));
        languageBox.setMaxLength(32);
        languageBox.setValue(locale);
        var dictionary = QuestTranslationsClient.snapshot(locale);
        if (dictionary != null && !dictionary.languages().isEmpty()) {
            languageBox.setTooltip(Tooltip.create(Component.literal(String.join(", ", dictionary.languages()))));
        }
        button(label("load"), 134, 34, 42, 18, b -> discardThen(this::loadLanguage));
        button(label("close"), width - 60, 7, 48, 18, b -> onClose());
        structure = button(label("structure"), width - 110, 34, 98, 18,
                b -> discardThen(() -> minecraft.setScreen(new QuestTranslationStructureScreen(this, structureOwner()))));

        searchBox = addRenderableWidget(new EditBox(font, left, 62, listWidth, 18, label("filter")));
        searchBox.setMaxLength(256);
        searchBox.setHint(label("filter"));
        searchBox.setValue(filter);
        searchBox.setResponder(value -> {
            filter = value;
            page = 0;
            rebuild();
        });
        int half = (listWidth - 4) / 2;
        scope = button(label(globalKeys ? "scope_all" : "scope_current"), left, 86, half, 18, b -> discardThen(() -> {
            globalKeys = !globalKeys;
            updateKeys();
            if (!keys.contains(key)) {
                key = keys.isEmpty() ? "" : keys.get(0);
                loadDraft();
            }
            page = 0;
            rebuild();
        }));
        untranslated = button(label(onlyUntranslated ? "untranslated" : "filter_all"), left + half + 4, 86, listWidth - half - 4, 18, b -> {
            onlyUntranslated = !onlyUntranslated;
            page = 0;
            rebuild();
        });
        untranslated.setTooltip(Tooltip.create(label("untranslated_hint")));
        visibleKeys = filtered();
        int pages = Math.max(1, (visibleKeys.size() + listRows - 1) / listRows);
        page = Math.min(page, pages - 1);
        for (int row = 0; row < listRows; row++) {
            int index = page * listRows + row;
            if (index >= visibleKeys.size()) break;
            String entry = visibleKeys.get(index);
            String name = names.getOrDefault(entry, entry);
            boolean translated = dictionary != null && dictionary.translations().containsKey(entry);
            String summary = (translated ? "✓ " : "○ ") + name;
            String shortKey = entry.startsWith(QuestTranslationsClient.prefix()) ? entry.substring(QuestTranslationsClient.prefix().length()) : entry;
            Button button = QuestTranslationWidgets.row(Component.literal(summary), shortKey, left, 110 + row * 32,
                    listWidth, 30, entry.equals(key), b -> discardThen(() -> select(entry)));
            button.active = !pending && !waiting;
            String source = dictionary == null ? "" : dictionary.source().getOrDefault(entry, "");
            button.setTooltip(Tooltip.create(Component.literal(name + "\n" + source + "\n" + entry)));
            addRenderableWidget(button);
        }
        Button previous = button(Component.literal("<"), left, height - 56, 24, 18, b -> {
            page--;
            rebuild();
        });
        previous.active = page > 0;
        Button next = button(Component.literal(">"), left + listWidth - 24, height - 56, 24, 18, b -> {
            page++;
            rebuild();
        });
        next.active = page + 1 < pages;

        Button copyKey = button(Component.literal(key), textX, 62, textWidth - 60, 18, b -> {
            minecraft.keyboardHandler.setClipboard(key);
            status = label("key_copied").getString();
            error = false;
        });
        copyKey.setTooltip(Tooltip.create(Component.literal(key).append("\n").append(label("copy_key"))));
        locate = button(label("locate"), width - 68, 62, 56, 18,
                b -> minecraft.setScreen(new QuestTranslationLocationScreen(this, key, locations.getOrDefault(key, List.of()))));
        locate.setTooltip(Tooltip.create(label("locate_hint")));
        sourceBox = addRenderableWidget(new ReadOnlyTextBox(textX, sourceY, textWidth, sourceHeight));
        sourceBox.setCharacterLimit(QuestTranslationCatalog.MAX_VALUE_LENGTH);
        translationBox = addRenderableWidget(new EditableTextBox(textX, targetY, textWidth, targetHeight));
        translationBox.setCharacterLimit(QuestTranslationCatalog.MAX_VALUE_LENGTH);
        translationBox.setValue(draft);
        translationBox.setValueListener(value -> draft = value);

        enroll = button(label("enroll"), left, height - 32, half, 20, b -> discardThen(() -> {
            status = label("enrolling").getString();
            QuestTranslationNetwork.enroll();
        }));
        reload = button(label("reload"), left + half + 4, height - 32, listWidth - half - 4, 20, b -> discardThen(() -> {
            status = label("loading").getString();
            QuestTranslationNetwork.reload();
        }));
        save = button(label("save"), textX, height - 32, 44, 20, b -> save(false));
        save.setTooltip(Tooltip.create(label("save").copy().append(" · Ctrl+S")));
        remove = button(label("fallback"), textX + 48, height - 32, 55, 20, b -> discardThen(() -> save(true)));
        saveNext = button(label("save_next"), textX + 107, height - 32, textWidth - 107, 20, b -> saveAndNext());
        saveNext.setTooltip(Tooltip.create(label("save_next_hint")));
        applyState();
    }

    private Button button(Component text, int x, int y, int width, int height, Button.OnPress action) {
        return addRenderableWidget(QuestTranslationWidgets.button(text, x, y, width, height, action));
    }

    private void updateKeys() {
        var snapshot = QuestTranslationsClient.snapshot(locale);
        if (!indexReady) {
            locations = QuestTranslationAccess.locations();
            indexReady = true;
        }
        List<String> current = allKeys ? QuestTranslationAccess.keys(object) : initialKeys;
        var selected = new LinkedHashSet<>(current);
        if (globalKeys) {
            selected.addAll(locations.keySet());
            if (snapshot != null) selected.addAll(snapshot.source().keySet());
        }
        keys = new ArrayList<>(selected);
        if (globalKeys) keys.sort(String::compareTo);
        names.clear();
        for (String entry : keys) {
            var usages = locations.getOrDefault(entry, List.of());
            String name = usages.isEmpty() ? entry : usages.get(0).field().getString();
            if (globalKeys && !usages.isEmpty()) {
                String path = usages.get(0).path();
                name = path.substring(path.contains(" / ") ? path.lastIndexOf(" / ") + 3 : 0) + " · " + name;
            } else if (current.contains(entry)) name = QuestTranslationAccess.keyLabel(object, entry).getString();
            names.put(entry, name);
        }
    }

    private Object structureOwner() {
        if (!globalKeys) return object;
        var usages = locations.getOrDefault(key, List.of());
        return usages.isEmpty() ? null : usages.get(0).owner();
    }

    private List<String> filtered() {
        String query = filter.toLowerCase(Locale.ROOT);
        var snapshot = QuestTranslationsClient.snapshot(locale);
        return keys.stream().filter(value -> !onlyUntranslated || snapshot == null || !snapshot.translations().containsKey(value))
                .filter(value -> value.toLowerCase(Locale.ROOT).contains(query) ||
                        names.getOrDefault(value, value).toLowerCase(Locale.ROOT).contains(query) ||
                        locations.getOrDefault(value, List.of()).stream().anyMatch(location -> location.path().toLowerCase(Locale.ROOT).contains(query)) ||
                        snapshot != null && (snapshot.source().getOrDefault(value, "").toLowerCase(Locale.ROOT).contains(query) ||
                                snapshot.translations().getOrDefault(value, "").toLowerCase(Locale.ROOT).contains(query)))
                .toList();
    }

    private String adjacent(int offset) {
        List<String> candidates = filtered();
        int index = candidates.indexOf(key);
        if (candidates.isEmpty()) return null;
        if (index < 0) return candidates.get(offset > 0 ? 0 : candidates.size() - 1);
        int next = index + offset;
        return next < 0 || next >= candidates.size() ? null : candidates.get(next);
    }

    private void saveAndNext() {
        if (!saveNext.active) return;
        nextAfterSave = adjacent(1);
        // In a filtered queue, wrap to remaining work before this entry, without revisiting it.
        if (nextAfterSave == null && onlyUntranslated) {
            nextAfterSave = filtered().stream().filter(value -> !value.equals(key)).findFirst().orElse(null);
        }
        save(false);
        if (!pending) nextAfterSave = null;
    }

    private void rebuild() {
        boolean searching = searchBox != null && searchBox.isFocused();
        int cursor = searching ? searchBox.getCursorPosition() : 0;
        clearWidgets();
        init();
        if (searching) {
            setFocused(searchBox);
            searchBox.setCursorPosition(cursor);
            searchBox.setHighlightPos(cursor);
        }
    }

    private void select(String selected) {
        key = selected;
        page = Math.max(0, filtered().indexOf(key)) / Math.max(1, listRows);
        waiting = QuestTranslationsClient.snapshot(locale) == null;
        loadDraft();
        rebuild();
    }

    private void loadLanguage() {
        String selected = languageBox.getValue().trim().toLowerCase(java.util.Locale.ROOT);
        try {
            QuestTranslationCatalog.validateLocale(selected);
        } catch (IOException e) {
            fail(e.getMessage());
            return;
        }
        locale = selected;
        waiting = true;
        status = label("loading").getString();
        error = false;
        QuestTranslationsClient.request(locale, true);
        rebuild();
    }

    private void loadDraft() {
        var snapshot = QuestTranslationsClient.snapshot(locale);
        if (snapshot == null) return;
        expected = snapshot.translations().get(key);
        draft = expected == null ? snapshot.source().getOrDefault(key, "") : expected;
        initialDraft = draft;
        if (translationBox != null) translationBox.setValue(draft);
        status = key.isEmpty() ? label("no_keys").getString() : expected == null ? label("using_source").getString() : "";
        error = false;
    }

    private void applyState() {
        var snapshot = QuestTranslationsClient.snapshot(locale);
        boolean editable = snapshot != null && snapshot.enabled() && snapshot.canEdit() && !pending && !waiting;
        boolean known = snapshot != null && snapshot.source().containsKey(key);
        save.active = editable && known;
        saveNext.active = save.active;
        locate.active = !pending && !waiting && !locations.getOrDefault(key, List.of()).isEmpty();
        scope.active = !pending && !waiting;
        untranslated.active = !pending && !waiting;
        searchBox.setEditable(!pending && !waiting);
        Object owner = structureOwner();
        structure.active = editable && owner != null && QuestTranslationAccess.description(owner) != null;
        remove.active = editable && known && !locale.equals(snapshot.defaultLanguage()) && expected != null;
        enroll.active = snapshot != null && snapshot.canEdit() && !pending && !waiting;
        reload.active = snapshot != null && snapshot.canEdit() && !pending && !waiting;
        translationBox.active = editable && known;
        if (!translationBox.active && translationBox.isFocused()) setFocused(null);
        sourceBox.setValue(snapshot == null ? "" : snapshot.source().getOrDefault(key, ""));
    }

    public void received(QuestTranslationCatalog.Snapshot snapshot) {
        if (!snapshot.locale().equals(locale)) return;
        waiting = false;
        indexReady = false;
        updateKeys();
        if (key.isEmpty() && !keys.isEmpty()) key = keys.get(0);
        if (!pending && !dirty()) loadDraft();
        else if (!Objects.equals(expected, snapshot.translations().get(key))) fail(label("conflict").getString());
        if (!snapshot.enabled()) fail(label("disabled").getString());
        else if (!snapshot.canEdit()) fail(label("permission").getString());
        rebuild();
    }

    public void edited(String language, String changedKey, String value, boolean success, String message) {
        if (changedKey.isEmpty()) {
            waiting = false;
            if (success) {
                error = false;
                status = message;
            } else fail(message);
            applyState();
            return;
        }
        if (!language.equals(locale) || !changedKey.equals(key)) {
            if (success) applyState();
            return;
        }
        if (!success) {
            pending = false;
            nextAfterSave = null;
            fail(message);
        } else if (pending && Objects.equals(value, pendingValue)) {
            pending = false;
            loadDraft();
            status = label("saved").getString();
            if (nextAfterSave != null) {
                String next = nextAfterSave;
                nextAfterSave = null;
                select(next);
                setFocused(translationBox);
                return;
            }
            rebuild();
        } else if (pending) {
            // Broadcasts contain edits by other players too. A different value is not the
            // acknowledgement of our pending save; retain its draft until the CAS response.
            fail(label("conflict").getString());
        } else if (dirty()) {
            fail(label("conflict").getString());
        } else {
            loadDraft();
        }
        applyState();
    }

    public void invalidated() {
        if (dirty()) {
            fail(label("conflict").getString());
        } else {
            waiting = true;
            status = label("loading").getString();
        }
        QuestTranslationsClient.request(locale, true);
    }

    private void save(boolean fallback) {
        if (pending || waiting || key.isEmpty()) return;
        var snapshot = QuestTranslationsClient.snapshot(locale);
        if (snapshot == null || !snapshot.enabled() || !snapshot.canEdit() || !snapshot.source().containsKey(key)) return;
        try {
            if (!fallback) QuestTranslationCatalog.validateValue(draft, snapshot.keyPrefix());
        } catch (IOException e) {
            fail(e.getMessage());
            return;
        }
        pending = true;
        pendingValue = fallback ? null : draft;
        pendingTicks = 0;
        error = false;
        status = label("saving").getString();
        setFocused(null);
        QuestTranslationNetwork.edit(locale, key, expected, pendingValue);
        applyState();
    }

    private boolean dirty() {
        return !Objects.equals(initialDraft, draft);
    }

    private void fail(String message) {
        error = true;
        status = message;
    }

    private void discardThen(Runnable action) {
        if (pending) return;
        if (!dirty()) {
            action.run();
            return;
        }
        minecraft.setScreen(new ConfirmScreen(accepted -> {
            minecraft.setScreen(this);
            if (accepted) {
                draft = initialDraft;
                action.run();
            }
        }, label("discard_title"), label("discard_message")));
    }

    @Override
    public void onClose() {
        discardThen(() -> minecraft.setScreen(parent));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (minecraft.getConnection() == null) {
            minecraft.setScreen(null);
            return;
        }
        languageBox.tick();
        sourceBox.tick();
        translationBox.tick();
        if (pending && ++pendingTicks >= 200) {
            pending = false;
            nextAfterSave = null;
            fail(label("timeout").getString());
            applyState();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (hasControlDown() && keyCode == GLFW.GLFW_KEY_S) {
            if (save.active) save(false);
            return true;
        }
        if (hasControlDown() && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            saveAndNext();
            return true;
        }
        if (hasAltDown() && (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN)) {
            String next = adjacent(keyCode == GLFW.GLFW_KEY_UP ? -1 : 1);
            if (next != null) discardThen(() -> select(next));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= 12 && mouseX <= 12 + listWidth && mouseY >= 110 && mouseY < height - 60) {
            int pages = Math.max(1, (visibleKeys.size() + listRows - 1) / listRows);
            page = Math.max(0, Math.min(pages - 1, page + (delta > 0 ? -1 : 1)));
            rebuild();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderEditorBackground(graphics, width, height);
        graphics.drawString(font, title, 12, 12, 0xE5EDF4, false);
        graphics.drawString(font, label("language"), 12, 39, 0x94A9BA, false);
        graphics.fill(textX - 7, 60, textX - 6, height - 11, 0xFF364352);
        graphics.drawString(font, label("source"), textX, sourceY - 11, 0x94A9BA, false);
        graphics.drawString(font, label("translation"), textX, targetY - 11, 0x63CBD2, false);
        var usages = locations.getOrDefault(key, List.of());
        String context = usages.isEmpty() ? label("unreferenced").getString() : usages.get(0).path() + " · " + usages.get(0).field().getString();
        if (usages.size() > 1) context += " (" + usages.size() + ")";
        graphics.drawString(font, font.plainSubstrByWidth(context, width - textX - 12), textX, 86, 0x94A9BA, false);
        String count = visibleKeys.size() + " · " + (page + 1) + "/" + Math.max(1, (visibleKeys.size() + listRows - 1) / listRows);
        graphics.drawCenteredString(font, count, 12 + listWidth / 2, height - 51, 0x94A9BA);
        String information = waiting ? label("loading").getString() : status;
        if (information.isEmpty() && dirty()) information = label("unsaved").getString();
        graphics.drawString(font, font.plainSubstrByWidth(information, width - textX - 12), textX, height - 46,
                error ? 0xFF7777 : 0x94A9BA, false);
        if (visibleKeys.isEmpty()) graphics.drawWordWrap(font, label("no_matches"), 18, 118, listWidth - 12, 0x94A9BA);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (mouseX >= textX && mouseY >= 84 && mouseY < 97) {
            graphics.renderTooltip(font, font.split(Component.literal(context), Math.max(120, width / 2)), mouseX, mouseY);
        }
    }

    static void renderEditorBackground(GuiGraphics graphics, int width, int height) {
        // Never render a parent here: FTB screens change GUI scale/depth and leak their controls.
        graphics.fill(0, 0, width, height, 0xFF181F29);
        graphics.fill(0, 0, width, 28, 0xFF253241);
        graphics.fill(0, 27, width, 28, 0xFF436174);
    }

    private final class ReadOnlyTextBox extends MultiLineEditBox {

        private ReadOnlyTextBox(int x, int y, int width, int height) {
            super(QuestTranslationScreen.this.font, x, y, width, height, Component.empty(), label("source"));
        }

        @Override
        public boolean charTyped(char codePoint, int modifiers) {
            return false;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return (Screen.isCopy(keyCode) || Screen.isSelectAll(keyCode) || keyCode == GLFW.GLFW_KEY_LEFT ||
                    keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN ||
                    keyCode == GLFW.GLFW_KEY_HOME || keyCode == GLFW.GLFW_KEY_END) && super.keyPressed(keyCode, scanCode, modifiers);
        }
    }

    private final class EditableTextBox extends MultiLineEditBox {

        private EditableTextBox(int x, int y, int width, int height) {
            super(QuestTranslationScreen.this.font, x, y, width, height, label("translation_hint"), label("translation"));
        }

        @Override
        public boolean charTyped(char codePoint, int modifiers) {
            return active && super.charTyped(codePoint, modifiers);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return active && super.keyPressed(keyCode, scanCode, modifiers);
        }
    }
}
