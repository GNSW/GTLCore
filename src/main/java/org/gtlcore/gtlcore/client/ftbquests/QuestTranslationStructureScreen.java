package org.gtlcore.gtlcore.client.ftbquests;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reorders paragraph references, rather than copying translated prose over their persistent keys. */
final class QuestTranslationStructureScreen extends Screen {

    private final Screen parent;
    private final Object object;
    private final List<String> original;
    private final List<String> lines;
    private int selected;
    private int page;
    private String newText = "";
    private String message = "";
    private int rows;
    private MultiLineEditBox input;

    QuestTranslationStructureScreen(Screen parent, Object object) {
        super(QuestTranslationScreen.label("structure"));
        this.parent = parent;
        this.object = object;
        List<String> description = QuestTranslationAccess.description(object);
        this.original = description == null ? List.of() : List.copyOf(description);
        this.lines = new ArrayList<>(original);
    }

    @Override
    protected void init() {
        rows = Math.max(1, (height - 160) / 22);
        int pages = Math.max(1, (lines.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int row = 0; row < rows; row++) {
            int index = page * rows + row;
            if (index >= lines.size()) break;
            String raw = lines.get(index);
            String preview = QuestTranslationAccess.preview(raw);
            Button line = addRenderableWidget(Button.builder(Component.literal((index + 1) + ". " + font.plainSubstrByWidth(preview, width - 130)), b -> {
                selected = index;
                rebuild();
            }).bounds(12, 34 + row * 22, width - 92, 20).build());
            line.active = index != selected;
            line.setTooltip(Tooltip.create(Component.literal(raw)));
        }
        Button up = addRenderableWidget(Button.builder(Component.literal("↑"), b -> move(-1)).bounds(width - 70, 34, 26, 20).build());
        Button down = addRenderableWidget(Button.builder(Component.literal("↓"), b -> move(1)).bounds(width - 40, 34, 26, 20).build());
        up.active = selected > 0 && selected < lines.size();
        down.active = selected >= 0 && selected + 1 < lines.size();
        Button remove = addRenderableWidget(Button.builder(QuestTranslationScreen.label("remove_paragraph"), b -> {
            if (selected < lines.size()) lines.remove(selected);
            selected = Math.max(0, Math.min(selected, lines.size() - 1));
            rebuild();
        }).bounds(width - 70, 59, 56, 20).build());
        remove.active = !lines.isEmpty();
        Button previous = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            page--;
            rebuild();
        })
                .bounds(width - 70, 84, 26, 20).build());
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            page++;
            rebuild();
        })
                .bounds(width - 40, 84, 26, 20).build());
        previous.active = page > 0;
        next.active = page + 1 < pages;
        input = addRenderableWidget(new MultiLineEditBox(font, 12, height - 112, width - 104, 51,
                QuestTranslationScreen.label("new_paragraph"), QuestTranslationScreen.label("new_paragraph")));
        input.setCharacterLimit(16384);
        input.setValue(newText);
        input.setValueListener(value -> newText = value);
        addRenderableWidget(Button.builder(QuestTranslationScreen.label("add_paragraph"), b -> add(newText))
                .bounds(width - 84, height - 112, 72, 20).build());
        addRenderableWidget(Button.builder(QuestTranslationScreen.label("page_break"), b -> add("{@pagebreak}"))
                .bounds(width - 84, height - 87, 72, 20).build());
        Button save = addRenderableWidget(Button.builder(QuestTranslationScreen.label("save"), b -> {
            if (QuestTranslationAccess.saveDescription(object, original, lines)) minecraft.setScreen(parent);
            else message = QuestTranslationScreen.label("conflict").getString();
        }).bounds(12, height - 50, 85, 20).build());
        var snapshot = QuestTranslationsClient.snapshot(QuestTranslationsClient.language());
        save.active = snapshot != null && snapshot.canEdit();
        addRenderableWidget(Button.builder(QuestTranslationScreen.label("close"), b -> onClose()).bounds(width - 97, height - 50, 85, 20).build());
    }

    private void add(String value) {
        int index = lines.isEmpty() ? 0 : Math.min(selected + 1, lines.size());
        lines.add(index, value);
        selected = index;
        page = index / rows;
        newText = "";
        rebuild();
    }

    private void move(int offset) {
        int next = selected + offset;
        if (next < 0 || next >= lines.size()) return;
        Collections.swap(lines, selected, next);
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
        if (lines.equals(original) && newText.isEmpty()) minecraft.setScreen(parent);
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
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        QuestTranslationScreen.renderEditorBackground(graphics, parent, width, height, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        graphics.drawString(font, font.plainSubstrByWidth(message.isEmpty() ? QuestTranslationScreen.label("enroll_hint").getString() : message,
                width - 24), 12, height - 20, message.isEmpty() ? 0xBBBBBB : 0xFF7777);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
