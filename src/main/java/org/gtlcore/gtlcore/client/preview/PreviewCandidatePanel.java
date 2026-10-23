package org.gtlcore.gtlcore.client.preview;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;

import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.jei.IngredientIO;
import com.lowdragmc.lowdraglib.utils.CycleItemStackHandler;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Expands each predicate's alternatives instead of cycling one item per predicate. */
public final class PreviewCandidatePanel extends WidgetGroup {

    private static final int SLOT_SIZE = 18;
    private static final int PREFERRED_COLUMNS = 9;
    private static final int PADDING = 4;
    private static final int PANEL_PADDING = 5;
    private static final int SCROLLBAR_WIDTH = 8;
    private static final int HEADER_HEIGHT = 22;
    private final DraggableScrollableWidgetGroup content;
    private final ButtonWidget close;
    private final Consumer<ItemStack> onSelect;
    private List<Group> groups = List.of();
    private boolean dirty = true;

    public PreviewCandidatePanel(Runnable onClose, Consumer<ItemStack> onSelect) {
        super(0, 0, preferredWidth(), HEADER_HEIGHT + SLOT_SIZE);
        this.onSelect = onSelect;
        setClientSideWidget();
        setBackground(GuiTextures.BACKGROUND);
        content = new DraggableScrollableWidgetGroup(PANEL_PADDING, HEADER_HEIGHT, 1, 1);
        content.setYScrollBarWidth(SCROLLBAR_WIDTH);
        content.setYBarStyle(GuiTextures.SLIDER_BACKGROUND_VERTICAL, GuiTextures.BUTTON);
        addWidget(content);
        close = new ButtonWidget(0, PANEL_PADDING, 14, 14,
                new GuiTextureGroup(GuiTextures.BUTTON, GuiTextures.CLOSE_ICON), click -> onClose.run());
        close.setHoverTooltips(Component.translatable("gui.gtlcore.preview.hide_candidates"));
        addWidget(close);
        setVisible(false);
        setActive(false);
    }

    public static int preferredWidth() {
        return PREFERRED_COLUMNS * SLOT_SIZE + PADDING * 2 + PANEL_PADDING * 2 + SCROLLBAR_WIDTH;
    }

    public void setGroups(List<Group> groups) {
        this.groups = List.copyOf(groups);
        dirty = true;
        if (isVisible()) rebuild();
    }

    public void close() {
        setVisible(false);
        setActive(false);
        content.clearAllWidgets();
        content.setScrollYOffset(0);
        dirty = true;
    }

    public void layout(int availableWidth, int height) {
        int columns = Math.max(1, Math.min(PREFERRED_COLUMNS,
                (availableWidth - PADDING * 2 - PANEL_PADDING * 2 - SCROLLBAR_WIDTH) / SLOT_SIZE));
        int width = columns * SLOT_SIZE + PADDING * 2 + PANEL_PADDING * 2 + SCROLLBAR_WIDTH;
        if (width != getSizeWidth() || height != getSizeHeight()) dirty = true;
        setSize(width, height);
        close.setSelfPosition(width - PANEL_PADDING - close.getSizeWidth(), PANEL_PADDING);
        content.setSize(width - PANEL_PADDING * 2, Math.max(1, height - HEADER_HEIGHT - PANEL_PADDING));
        if (dirty && isVisible()) rebuild();
    }

    private void rebuild() {
        content.clearAllWidgets();
        int width = content.getSizeWidth() - SCROLLBAR_WIDTH;
        int columns = Math.max(1, (width - PADDING * 2) / SLOT_SIZE);
        int y = 0;
        for (var group : groups) {
            var section = new CandidateGroup(group, width, columns, onSelect);
            section.setSelfPosition(0, y);
            content.addWidget(section);
            y += section.getSizeHeight() + PADDING;
        }
        dirty = false;
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
        var font = Minecraft.getInstance().font;
        graphics.drawString(font, Component.translatable("gui.gtlcore.preview.candidates"),
                getPositionX() + PANEL_PADDING, getPositionY() + 8, ChatFormatting.DARK_GRAY.getColor(), false);
        if (groups.isEmpty()) {
            int y = content.getPositionY() + PADDING;
            for (var line : font.split(Component.translatable("gui.gtlcore.preview.select_block"),
                    content.getSizeWidth() - SCROLLBAR_WIDTH - PADDING * 2)) {
                if (y + font.lineHeight > content.getPositionY() + content.getSizeHeight()) break;
                graphics.drawString(font, line, content.getPositionX() + PADDING, y, ChatFormatting.DARK_GRAY.getColor(), false);
                y += font.lineHeight;
            }
        }
    }

    public record Group(List<ItemStack> stacks, List<Component> tooltips, List<Component> limits) {

        public static Group of(SimplePredicate simple, TraceabilityPredicate predicate) {
            var limits = new ArrayList<>(simple.getToolTips(null));
            if (simple.toolTips != null) limits.subList(0, simple.toolTips.size()).clear();
            if (simple.minLayerCount >= 0) {
                limits.add(Component.translatable("gui.gtlcore.preview.min_layer", simple.minLayerCount));
            }
            if (simple.maxLayerCount >= 0) {
                limits.add(Component.translatable("gui.gtlcore.preview.max_layer", simple.maxLayerCount));
            }
            return new Group(List.copyOf(simple.getCandidates()), List.copyOf(simple.getToolTips(predicate)), List.copyOf(limits));
        }
    }

    private static final class CandidateGroup extends WidgetGroup {

        private final List<FormattedCharSequence> lines = new ArrayList<>();

        private CandidateGroup(Group group, int width, int columns, Consumer<ItemStack> onSelect) {
            super(0, 0, width, 1);
            var font = Minecraft.getInstance().font;
            for (var limit : group.limits()) lines.addAll(font.split(limit, width - PADDING * 2));
            int itemTop = PADDING + lines.size() * font.lineHeight + (lines.isEmpty() ? 0 : 2);
            int rows = (group.stacks().size() + columns - 1) / columns;
            setSize(width, itemTop + rows * SLOT_SIZE + PADDING);
            setBackground(GuiTextures.BACKGROUND_INVERSE);
            var handler = new CycleItemStackHandler(group.stacks().stream().map(List::of).toList());
            for (int index = 0; index < group.stacks().size(); index++) {
                var stack = group.stacks().get(index);
                addWidget(new SlotWidget(handler, index, PADDING + index % columns * SLOT_SIZE,
                        itemTop + index / columns * SLOT_SIZE, false, false) {

                    @Override
                    public boolean mouseClicked(double mouseX, double mouseY, int button) {
                        if (button == 0 && isMouseOverElement(mouseX, mouseY)) {
                            onSelect.accept(stack);
                            return true;
                        }
                        return super.mouseClicked(mouseX, mouseY, button);
                    }
                }
                        .setBackgroundTexture(GuiTextures.SLOT)
                        .setIngredientIO(IngredientIO.RENDER_ONLY)
                        .setOnAddedTooltips((slot, tips) -> tips.addAll(group.tooltips())));
            }
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
            var font = Minecraft.getInstance().font;
            int y = getPositionY() + PADDING;
            for (var line : lines) {
                graphics.drawString(font, line, getPositionX() + PADDING, y, ChatFormatting.RED.getColor(), false);
                y += font.lineHeight;
            }
        }
    }
}
