package org.gtlcore.gtlcore.client.ftbquests;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/** Shared, compact controls for the translation workbench. */
final class QuestTranslationWidgets {

    private QuestTranslationWidgets() {}

    static Button button(Component text, int x, int y, int width, int height, Button.OnPress action) {
        return new FlatButton(text, x, y, width, height, action, false, "");
    }

    static Button row(Component text, String detail, int x, int y, int width, int height,
                      boolean selected, Button.OnPress action) {
        return new FlatButton(text, x, y, width, height, action, selected, detail);
    }

    private static final class FlatButton extends Button {

        private final boolean selected;
        private final String detail;

        private FlatButton(Component text, int x, int y, int width, int height, OnPress action,
                           boolean selected, String detail) {
            super(x, y, width, height, text, action, DEFAULT_NARRATION);
            this.selected = selected;
            this.detail = detail;
            setTooltip(Tooltip.create(text));
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            var font = Minecraft.getInstance().font;
            int x = getX(), y = getY();
            int fill = selected ? 0xFF203F4C : isHoveredOrFocused() && active ? 0xFF354657 : 0xFF293442;
            graphics.fill(x, y, x + width, y + height, fill);
            graphics.renderOutline(x, y, width, height, selected ? 0xFF63CBD2 : 0xFF485665);
            int color = active || selected ? 0xE5EDF4 : 0x788795;
            var text = Language.getInstance().getVisualOrder(font.substrByWidth(getMessage(), width - 12));
            if (detail.isEmpty()) {
                graphics.drawString(font, text, x + (width - font.width(text)) / 2, y + (height - 8) / 2, color, false);
            } else {
                graphics.fill(x, y, x + 2, y + height, selected ? 0xFF63CBD2 : 0xFF485665);
                graphics.drawString(font, text, x + 6, y + 4, color, false);
                graphics.drawString(font, font.plainSubstrByWidth(detail, width - 12), x + 6, y + 15, 0x94A9BA, false);
            }
        }
    }
}
