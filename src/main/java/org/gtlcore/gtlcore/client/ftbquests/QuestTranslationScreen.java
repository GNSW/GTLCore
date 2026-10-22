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
import java.util.List;
import java.util.Objects;

/** Edits one locale/key at a time. The raw FTB reference, pages and progress never change here. */
public final class QuestTranslationScreen extends Screen {

    private final Screen parent;
    private final Object object;
    private final List<String> initialKeys;
    private final boolean allKeys;
    private List<String> keys;
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
        int listWidth = Math.max(88, Math.min(180, width / 3));
        textX = left + listWidth + 10;
        int textWidth = Math.max(80, width - textX - 16);
        int bodyBottom = height - 77;
        int bodyTop = 76;
        int bodyHeight = Math.max(70, bodyBottom - bodyTop);
        sourceY = bodyTop + 12;
        int sourceHeight = Math.max(25, bodyHeight / 3 - 12);
        targetY = sourceY + sourceHeight + 18;
        int targetHeight = Math.max(30, bodyBottom - targetY);
        listRows = Math.max(1, (bodyHeight - 27) / 22);

        languageBox = addRenderableWidget(new EditBox(font, 70, 33, Math.max(42, Math.min(110, width - 292)), 20, label("language")));
        languageBox.setMaxLength(32);
        languageBox.setValue(locale);
        var dictionary = QuestTranslationsClient.snapshot(locale);
        if (dictionary != null && !dictionary.languages().isEmpty()) {
            languageBox.setTooltip(Tooltip.create(Component.literal(String.join(", ", dictionary.languages()))));
        }
        addRenderableWidget(Button.builder(label("load"), b -> discardThen(this::loadLanguage))
                .bounds(languageBox.getX() + languageBox.getWidth() + 5, 33, 48, 20).build());
        addRenderableWidget(Button.builder(label("close"), b -> onClose()).bounds(width - 69, 33, 57, 20).build());
        if (QuestTranslationAccess.description(object) != null) {
            addRenderableWidget(Button.builder(label("structure"), b -> discardThen(() -> minecraft.setScreen(new QuestTranslationStructureScreen(this, object))))
                    .bounds(width - 148, 33, 72, 20).build());
        }

        searchBox = addRenderableWidget(new EditBox(font, left, bodyTop - 23, listWidth, 18, label("filter")));
        searchBox.setHint(label("filter"));
        searchBox.setValue(filter);
        searchBox.setResponder(value -> {
            filter = value;
            page = 0;
            rebuild();
        });
        List<String> visibleKeys = filtered();
        int pages = Math.max(1, (visibleKeys.size() + listRows - 1) / listRows);
        page = Math.min(page, pages - 1);
        for (int row = 0; row < listRows; row++) {
            int index = page * listRows + row;
            if (index >= visibleKeys.size()) break;
            String entry = visibleKeys.get(index);
            String name = QuestTranslationAccess.keyLabel(object, entry).getString();
            Button button = Button.builder(Component.literal(font.plainSubstrByWidth(name, listWidth - 9)),
                    b -> discardThen(() -> select(entry))).bounds(left, bodyTop + row * 22, listWidth, 20).build();
            button.active = !entry.equals(key) && !pending;
            String source = dictionary == null ? "" : dictionary.source().getOrDefault(entry, "");
            button.setTooltip(Tooltip.create(Component.literal(name + "\n" + source + "\n" + entry)));
            addRenderableWidget(button);
        }
        Button previous = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            page--;
            rebuild();
        }).bounds(left, bodyBottom - 21, 28, 20).build());
        previous.active = page > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            page++;
            rebuild();
        }).bounds(left + listWidth - 28, bodyBottom - 21, 28, 20).build());
        next.active = page + 1 < pages;

        sourceBox = addRenderableWidget(new ReadOnlyTextBox(textX, sourceY, textWidth, sourceHeight));
        sourceBox.setCharacterLimit(QuestTranslationCatalog.MAX_VALUE_LENGTH);
        translationBox = addRenderableWidget(new EditableTextBox(textX, targetY, textWidth, targetHeight));
        translationBox.setCharacterLimit(QuestTranslationCatalog.MAX_VALUE_LENGTH);
        translationBox.setValue(draft);
        translationBox.setValueListener(value -> draft = value);

        int buttonWidth = Math.max(55, (width - 40) / 4);
        save = addRenderableWidget(Button.builder(label("save"), b -> save(false)).bounds(left, height - 66, buttonWidth, 20).build());
        remove = addRenderableWidget(Button.builder(label("fallback"), b -> discardThen(() -> save(true)))
                .bounds(left + buttonWidth + 5, height - 66, buttonWidth, 20).build());
        enroll = addRenderableWidget(Button.builder(label("enroll"), b -> discardThen(() -> {
            status = Component.translatable("gtlcore.quest_i18n.enrolling").getString();
            QuestTranslationNetwork.enroll();
        })).bounds(left + 2 * (buttonWidth + 5), height - 66, buttonWidth, 20).build());
        reload = addRenderableWidget(Button.builder(label("reload"), b -> discardThen(() -> {
            status = label("loading").getString();
            QuestTranslationNetwork.reload();
        })).bounds(left + 3 * (buttonWidth + 5), height - 66, buttonWidth, 20).build());
        applyState();
    }

    private List<String> filtered() {
        String query = filter.toLowerCase(java.util.Locale.ROOT);
        var snapshot = QuestTranslationsClient.snapshot(locale);
        return keys.stream().filter(value -> value.toLowerCase(java.util.Locale.ROOT).contains(query) ||
                QuestTranslationAccess.keyLabel(object, value).getString().toLowerCase(java.util.Locale.ROOT).contains(query) ||
                snapshot != null && (snapshot.source().getOrDefault(value, "").toLowerCase(java.util.Locale.ROOT).contains(query) ||
                        snapshot.translations().getOrDefault(value, "").toLowerCase(java.util.Locale.ROOT).contains(query)))
                .toList();
    }

    private void rebuild() {
        boolean searching = searchBox != null && searchBox.isFocused();
        clearWidgets();
        init();
        if (searching) setFocused(searchBox);
    }

    private void select(String selected) {
        key = selected;
        waiting = false;
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
        List<String> latest = QuestTranslationAccess.keys(object);
        if (allKeys) keys = new ArrayList<>(latest);
        else {
            // A quick-field editor remains focused on its original field after a dictionary reload.
            keys = new ArrayList<>(initialKeys);
        }
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
            fail(message);
        } else if (pending && Objects.equals(value, pendingValue)) {
            pending = false;
            loadDraft();
            status = label("saved").getString();
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
        if (snapshot == null || !snapshot.canEdit()) return;
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
            fail(label("timeout").getString());
            applyState();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderEditorBackground(graphics, parent, width, height, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        graphics.drawString(font, label("language"), 12, 39, 0xDDDDDD);
        graphics.drawString(font, label("source"), textX, sourceY - 11, 0xAAAAAA);
        graphics.drawString(font, label("translation"), textX, targetY - 11, 0xFFFFFF);
        graphics.drawString(font, font.plainSubstrByWidth(QuestTranslationAccess.keyLabel(object, key).getString(),
                width - textX - 12), textX, 60, 0x77CCDD);
        String information = waiting ? label("loading").getString() : status;
        int y = height - 40;
        for (var line : font.split(Component.literal(information), width - 24)) {
            if (y > height - 12) break;
            graphics.drawString(font, line, 12, y, error ? 0xFF7777 : 0xBBBBBB);
            y += 10;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    static void renderEditorBackground(GuiGraphics graphics, Screen parent, int width, int height, float partialTick) {
        // Keep the quest/editor underneath visible instead of dropping the user into a world overlay.
        if (parent != null) parent.render(graphics, -1, -1, partialTick);
        graphics.fill(0, 0, width, height, 0xB0000000);
        graphics.fill(5, 5, width - 5, height - 5, 0xFF252A35);
        graphics.fill(6, 6, width - 6, 27, 0xFF3C4351);
        graphics.renderOutline(5, 5, width - 10, height - 10, 0xFF8792A5);
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
