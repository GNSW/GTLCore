package org.gtlcore.gtlcore.client.ftbquests;

import org.gtlcore.gtlcore.integration.ftbquests.QuestTranslationNetwork;
import org.gtlcore.gtlcore.integration.ftbquests.QuestTranslationParagraphDraft;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Authors source paragraphs while preserving their language keys and rich-text structure. */
final class QuestTranslationStructureScreen extends Screen {

    private final Screen parent;
    private final Object object;
    private final QuestTranslationParagraphDraft draft;
    private final String sourceLanguage;
    private int selected;
    private int page;
    private int rows;
    private long generation;
    private int lastClicked = -1;
    private long lastClick;
    private String newText = "";
    private String message = "";
    private MultiLineEditBox input;
    private UUID request;
    private int pendingTicks;

    QuestTranslationStructureScreen(Screen parent, Object object) {
        super(QuestTranslationScreen.label("structure"));
        this.parent = parent;
        this.object = object;
        var snapshot = QuestTranslationsClient.snapshot(QuestTranslationsClient.language());
        List<String> description = QuestTranslationAccess.description(object);
        draft = snapshot == null || description == null ? null :
                new QuestTranslationParagraphDraft(QuestTranslationAccess.id(object), description, snapshot);
        sourceLanguage = snapshot == null ? "" : snapshot.defaultLanguage();
    }

    private List<String> lines() {
        return draft == null ? List.of() : draft.lines();
    }

    private boolean canEdit() {
        var snapshot = QuestTranslationsClient.snapshot(QuestTranslationsClient.language());
        return draft != null && snapshot != null && snapshot.enabled() && snapshot.canEdit() && request == null;
    }

    @Override
    protected void init() {
        generation = QuestTranslationsClient.generation();
        rows = Math.max(1, (height - 151) / 32);
        int pages = Math.max(1, (lines().size() + rows - 1) / rows);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int row = 0; row < rows; row++) {
            int index = page * rows + row;
            if (index >= lines().size()) break;
            Component preview = QuestTranslationAccess.previewComponent(draft.text(index));
            Button line = addRenderableWidget(QuestTranslationWidgets.row(
                    Component.literal((index + 1) + ". ").append(preview), lines().get(index),
                    12, 36 + row * 32, width - 112, 30, index == selected, b -> {
                        long now = System.currentTimeMillis();
                        boolean twice = lastClicked == index && now - lastClick < 250;
                        selected = index;
                        lastClicked = index;
                        lastClick = now;
                        if (twice) editSelected();
                        else rebuild();
                    }));
            line.setTooltip(Tooltip.create(preview.copy().append("\n").append(QuestTranslationScreen.label("edit_paragraph_hint"))));
            line.active = request == null;
        }
        Button edit = button(QuestTranslationScreen.label("edit_paragraph"), width - 98, 36, 86, 20, b -> editSelected());
        edit.active = canEdit() && selected < lines().size();
        Button up = button(Component.literal("↑"), width - 98, 60, 40, 20, b -> move(-1));
        Button down = button(Component.literal("↓"), width - 52, 60, 40, 20, b -> move(1));
        up.active = canEdit() && selected > 0 && selected < lines().size();
        down.active = canEdit() && selected + 1 < lines().size();
        Button remove = button(QuestTranslationScreen.label("remove_paragraph"), width - 98, 84, 86, 20, b -> {
            if (selected < lines().size()) lines().remove(selected);
            selected = Math.max(0, Math.min(selected, lines().size() - 1));
            rebuild();
        });
        remove.active = canEdit() && !lines().isEmpty();
        Button previous = button(Component.literal("<"), width - 98, 108, 40, 20, b -> {
            page--;
            rebuild();
        });
        Button next = button(Component.literal(">"), width - 52, 108, 40, 20, b -> {
            page++;
            rebuild();
        });
        previous.active = request == null && page > 0;
        next.active = request == null && page + 1 < pages;
        input = addRenderableWidget(new MultiLineEditBox(font, 12, height - 107, width - 124, 51,
                QuestTranslationScreen.label("new_paragraph"), QuestTranslationScreen.label("new_paragraph")));
        input.setCharacterLimit(16384);
        input.setValue(newText);
        input.setValueListener(value -> newText = value);
        input.active = canEdit();
        button(QuestTranslationScreen.label("add_paragraph"), width - 98, height - 107, 86, 20,
                b -> add(inputLines())).active = canEdit();
        button(QuestTranslationScreen.label("format_links"), width - 98, height - 83, 86, 20,
                b -> openRich(-1, newText, "")).active = canEdit();
        button(QuestTranslationScreen.label("page_break"), width - 98, height - 59, 86, 20, b -> {
            int index = insertionIndex();
            lines().add(index, "{@pagebreak}");
            selected = index;
            page = selected / rows;
            rebuild();
        }).active = canEdit();
        button(QuestTranslationScreen.label("save"), 12, height - 35, 85, 20, b -> save()).active = canEdit();
        button(QuestTranslationScreen.label("close"), width - 98, height - 35, 86, 20, b -> onClose()).active = request == null;
    }

    private Button button(Component text, int x, int y, int width, int height, Button.OnPress action) {
        return addRenderableWidget(QuestTranslationWidgets.button(text, x, y, width, height, action));
    }

    private void add(List<String> value) {
        try {
            int index = insertionIndex();
            draft.insert(index, QuestTranslationRichText.normalize(value));
            selected = index + value.size() - 1;
            page = selected / rows;
            newText = "";
            message = "";
        } catch (IOException | RuntimeException e) {
            message = e.getMessage();
        }
        rebuild();
    }

    private int insertionIndex() {
        return lines().isEmpty() ? 0 : Math.min(selected + 1, lines().size());
    }

    private List<String> inputLines() {
        return Arrays.asList(newText.split("\n", -1));
    }

    private void editSelected() {
        if (canEdit() && selected < lines().size()) openRich(selected, draft.text(selected), "");
    }

    private void openRich(int index, String text, String error) {
        Component heading = error.isEmpty() ? Component.translatable("gtlcore.quest_i18n.source_editor", sourceLanguage) :
                Component.literal(error);
        if (!QuestTranslationRichEditor.open(this, heading, text, replacement -> {
            try {
                List<String> normalized = QuestTranslationRichText.normalize(replacement);
                if (index < 0) {
                    newText = String.join("\n", normalized);
                    rebuild();
                    return;
                }
                draft.replace(index, normalized);
                selected = Math.min(index, Math.max(0, lines().size() - 1));
                message = "";
                rebuild();
            } catch (IOException | RuntimeException e) {
                openRich(index, String.join("\n", replacement), e.getMessage());
            }
        })) {
            message = QuestTranslationScreen.label("rich_editor_failed").getString();
        }
    }

    private void save() {
        if (!canEdit()) return;
        try {
            var submitted = draft.copy();
            // Include pending input; retain the live draft until the server acknowledges it.
            if (!newText.isEmpty()) submitted.insert(insertionIndex(), QuestTranslationRichText.normalize(inputLines()));
            request = UUID.randomUUID();
            pendingTicks = 0;
            QuestTranslationNetwork.saveDescription(new QuestTranslationNetwork.DescriptionEdit(
                    request, QuestTranslationAccess.id(object), submitted.original(),
                    List.copyOf(submitted.lines()), submitted.changes()));
            message = QuestTranslationScreen.label("saving").getString();
            rebuild();
        } catch (IOException | RuntimeException e) {
            request = null;
            message = e.getMessage();
            rebuild();
        }
    }

    void saved(UUID reply, boolean success, String error) {
        if (!reply.equals(request)) return;
        request = null;
        if (success) {
            if (parent instanceof QuestTranslationScreen screen) screen.invalidated();
            minecraft.setScreen(parent);
        } else {
            message = error;
            rebuild();
        }
    }

    private void move(int offset) {
        int next = selected + offset;
        if (next < 0 || next >= lines().size()) return;
        Collections.swap(lines(), selected, next);
        selected = next;
        page = selected / rows;
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    @Override
    public void onClose() {
        if (request != null) return;
        if ((draft == null || !draft.dirty()) && newText.isEmpty()) minecraft.setScreen(parent);
        else minecraft.setScreen(new ConfirmScreen(accepted -> minecraft.setScreen(accepted ? parent : this),
                QuestTranslationScreen.label("discard_title"), QuestTranslationScreen.label("discard_message")));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        input.tick();
        if (request != null && ++pendingTicks >= 200) {
            request = null;
            message = QuestTranslationScreen.label("timeout").getString();
            rebuild();
        } else if (generation != QuestTranslationsClient.generation()) rebuild();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        QuestTranslationScreen.renderEditorBackground(graphics, width, height);
        graphics.drawString(font, title.copy().append(" · " + sourceLanguage), 12, 12, 0xE5EDF4, false);
        graphics.drawString(font, font.plainSubstrByWidth(message.isEmpty() ?
                QuestTranslationScreen.label("paragraph_hint").getString() : message, width - 24),
                12, height - 10, message.isEmpty() ? 0xBBBBBB : 0xFF7777);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
