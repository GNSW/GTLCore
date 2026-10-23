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
        rows = Math.max(1, (height - 165) / 32);
        int pages = Math.max(1, (lines.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int row = 0; row < rows; row++) {
            int index = page * rows + row;
            if (index >= lines.size()) break;
            String raw = lines.get(index);
            String preview = QuestTranslationAccess.preview(raw);
            Button line = addRenderableWidget(QuestTranslationWidgets.row(Component.literal((index + 1) + ". " + preview), raw,
                    12, 36 + row * 32, width - 112, 30, index == selected, b -> {
                        selected = index;
                        rebuild();
                    }));
            line.setTooltip(Tooltip.create(Component.literal(raw)));
        }
        Button up = button(Component.literal("↑"), width - 88, 36, 36, 20, b -> move(-1));
        Button down = button(Component.literal("↓"), width - 48, 36, 36, 20, b -> move(1));
        up.active = selected > 0 && selected < lines.size();
        down.active = selected >= 0 && selected + 1 < lines.size();
        Button remove = button(QuestTranslationScreen.label("remove_paragraph"), width - 88, 62, 76, 20, b -> {
            if (selected < lines.size()) lines.remove(selected);
            selected = Math.max(0, Math.min(selected, lines.size() - 1));
            rebuild();
        });
        remove.active = !lines.isEmpty();
        Button previous = button(Component.literal("<"), width - 88, 88, 36, 20, b -> {
            page--;
            rebuild();
        });
        Button next = button(Component.literal(">"), width - 48, 88, 36, 20, b -> {
            page++;
            rebuild();
        });
        previous.active = page > 0;
        next.active = page + 1 < pages;
        input = addRenderableWidget(new MultiLineEditBox(font, 12, height - 112, width - 112, 51,
                QuestTranslationScreen.label("new_paragraph"), QuestTranslationScreen.label("new_paragraph")));
        input.setCharacterLimit(16384);
        input.setValue(newText);
        input.setValueListener(value -> newText = value);
        button(QuestTranslationScreen.label("add_paragraph"), width - 88, height - 112, 76, 20, b -> add(newText));
        button(QuestTranslationScreen.label("page_break"), width - 88, height - 87, 76, 20, b -> {
            insert("{@pagebreak}");
            rebuild();
        });
        Button save = button(QuestTranslationScreen.label("save"), 12, height - 43, 85, 20, b -> {
            List<String> replacement = new ArrayList<>(lines);
            // Save includes the pending paragraph even when Add was not pressed.
            // Keep the draft intact if the structure changed while this screen was open.
            if (!newText.isEmpty()) replacement.add(insertionIndex(), newText);
            if (QuestTranslationAccess.saveDescription(object, original, replacement)) minecraft.setScreen(parent);
            else message = QuestTranslationScreen.label("conflict").getString();
        });
        var snapshot = QuestTranslationsClient.snapshot(QuestTranslationsClient.language());
        save.active = snapshot != null && snapshot.canEdit();
        button(QuestTranslationScreen.label("close"), width - 97, height - 43, 85, 20, b -> onClose());
    }

    private Button button(Component text, int x, int y, int width, int height, Button.OnPress action) {
        return addRenderableWidget(QuestTranslationWidgets.button(text, x, y, width, height, action));
    }

    private void add(String value) {
        insert(value);
        newText = "";
        rebuild();
    }

    private int insertionIndex() {
        return lines.isEmpty() ? 0 : Math.min(selected + 1, lines.size());
    }

    private void insert(String value) {
        int index = insertionIndex();
        lines.add(index, value);
        selected = index;
        page = index / rows;
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
        QuestTranslationScreen.renderEditorBackground(graphics, width, height);
        graphics.drawString(font, title, 12, 12, 0xE5EDF4, false);
        graphics.drawString(font, font.plainSubstrByWidth(message.isEmpty() ? QuestTranslationScreen.label("enroll_hint").getString() : message,
                width - 24), 12, height - 12, message.isEmpty() ? 0xBBBBBB : 0xFF7777);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
