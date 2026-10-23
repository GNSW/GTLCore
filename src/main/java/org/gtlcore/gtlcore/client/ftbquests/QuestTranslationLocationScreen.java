package org.gtlcore.gtlcore.client.ftbquests;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Lists every usage of a key before opening the corresponding native quest or property editor. */
final class QuestTranslationLocationScreen extends Screen {

    private final Screen parent;
    private final String key;
    private final List<QuestTranslationAccess.Location> locations;
    private int selected;
    private int page;
    private String error = "";

    QuestTranslationLocationScreen(Screen parent, String key, List<QuestTranslationAccess.Location> locations) {
        super(QuestTranslationScreen.label("locations"));
        this.parent = parent;
        this.key = key;
        this.locations = List.copyOf(locations);
    }

    @Override
    protected void init() {
        int rows = Math.max(1, (height - 118) / 34);
        page = Math.min(page, Math.max(0, (locations.size() - 1) / rows));
        for (int i = 0; i < rows; i++) {
            int index = page * rows + i;
            if (index >= locations.size()) break;
            var location = locations.get(index);
            var button = QuestTranslationWidgets.row(location.field(), location.path(), 12, 55 + i * 34,
                    width - 24, 30, index == selected, b -> {
                        selected = index;
                        rebuild();
                    });
            button.setTooltip(Tooltip.create(Component.literal(location.path()).append("\n").append(location.field())));
            addRenderableWidget(button);
        }
        var previous = addRenderableWidget(QuestTranslationWidgets.button(Component.literal("<"), 12, height - 61, 28, 18, b -> {
            page--;
            rebuild();
        }));
        previous.active = page > 0;
        var next = addRenderableWidget(QuestTranslationWidgets.button(Component.literal(">"), 44, height - 61, 28, 18, b -> {
            page++;
            rebuild();
        }));
        next.active = (page + 1) * rows < locations.size();
        int w = (width - 32) / 3;
        var view = addRenderableWidget(QuestTranslationWidgets.button(QuestTranslationScreen.label("view_quest"), 12, height - 35, w, 22, b -> {
            if (!QuestTranslationAccess.locate(locations.get(selected), this)) error = QuestTranslationScreen.label("location_failed").getString();
        }));
        view.active = !locations.isEmpty();
        view.setTooltip(Tooltip.create(QuestTranslationScreen.label("location_return")));
        var edit = addRenderableWidget(QuestTranslationWidgets.button(QuestTranslationScreen.label("edit_owner"), 16 + w, height - 35, w, 22, b -> {
            if (!QuestTranslationAccess.editLocation(locations.get(selected), this)) error = QuestTranslationScreen.label("location_failed").getString();
        }));
        var snapshot = QuestTranslationsClient.snapshot(QuestTranslationsClient.language());
        edit.active = !locations.isEmpty() && snapshot != null && snapshot.canEdit();
        addRenderableWidget(QuestTranslationWidgets.button(QuestTranslationScreen.label("back_editor"), 20 + 2 * w, height - 35, w, 22, b -> onClose()));
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        QuestTranslationScreen.renderEditorBackground(graphics, width, height);
        graphics.drawString(font, title, 12, 12, 0xE5EDF4, false);
        graphics.drawString(font, font.plainSubstrByWidth(key, width - 24), 12, 36, 0x63CBD2, false);
        graphics.drawString(font, font.plainSubstrByWidth(error.isEmpty() ? QuestTranslationScreen.label("location_return").getString() : error,
                width - 104), 84, height - 56, error.isEmpty() ? 0x94A9BA : 0xFF7777, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
