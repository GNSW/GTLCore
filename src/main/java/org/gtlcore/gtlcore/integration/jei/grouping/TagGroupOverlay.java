package org.gtlcore.gtlcore.integration.jei.grouping;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import com.mojang.blaze3d.systems.RenderSystem;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.Internal;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.input.IClickableIngredientInternal;
import mezz.jei.gui.input.IDraggableIngredientInternal;
import mezz.jei.gui.overlay.elements.IElement;
import mezz.jei.gui.overlay.ingredients.IngredientGrid;
import mezz.jei.gui.overlay.ingredients.IngredientListRenderer;
import mezz.jei.gui.overlay.ingredients.IngredientListSlot;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** The popup uses JEI's original elements, slots, renderer and input candidates. */
public final class TagGroupOverlay {

    private static final ResourceLocation RECIPE_BOOK = new ResourceLocation("minecraft", "textures/gui/recipe_book.png");
    private static final ResourceLocation INVENTORY = new ResourceLocation("minecraft", "textures/gui/container/inventory.png");
    private static TagGroupOverlay active;
    private final TagGroupSource source;
    private final IngredientGrid grid;
    private final IngredientListRenderer renderer;
    private Screen screen;
    private int screenWidth;
    private int screenHeight;
    private TagGroupingRules.Group<IElement<?>> candidate;
    private TagGroupingRules.Group<IElement<?>> expanded;
    private ImmutableRect2i anchor;
    private TagGroupLayout layout;
    private String title = "";
    private List<FormattedCharSequence> titleLines = List.of();
    private long hoverStarted;
    private long leftAt;
    private int firstRow;
    private double scrollRemainder;
    private IElement<?> dismissed;

    public TagGroupOverlay(TagGroupSource source, IngredientGrid grid, IIngredientManager ingredientManager) {
        this.source = source;
        this.grid = grid;
        this.renderer = new IngredientListRenderer(ingredientManager, true);
    }

    public static TagGroupOverlay active() {
        if (active != null && !active.isUsable()) active.close();
        return active;
    }

    public static void closeActive() {
        if (active != null) active.close();
    }

    public static void dismissActive() {
        if (active != null) {
            var overlay = active;
            IElement<?> representative = overlay.expanded.representative();
            overlay.close();
            overlay.dismissed = representative;
        }
    }

    private boolean isUsable() {
        return expanded != null && screen == Minecraft.getInstance().screen && TagGroupSource.options().jeiTagGrouping &&
                Internal.getClientToggleState().isOverlayEnabled() && !Internal.getClientToggleState().isEditModeEnabled();
    }

    public void update(int mouseX, int mouseY) {
        Screen currentScreen = Minecraft.getInstance().screen;
        if (currentScreen == null) {
            close();
            return;
        }
        if (screen != currentScreen || screenWidth != currentScreen.width || screenHeight != currentScreen.height) {
            close();
            screen = currentScreen;
            screenWidth = currentScreen.width;
            screenHeight = currentScreen.height;
            dismissed = null;
        }
        long now = System.nanoTime();
        if (expanded != null) {
            boolean anchorStillVisible = grid.getSlots().anyMatch(slot -> slot.getElement() == expanded.representative() &&
                    slot.getArea().equals(anchor));
            if (!anchorStillVisible || !isUsable()) {
                close();
            } else if (contains(mouseX, mouseY) || anchor.contains(mouseX, mouseY)) {
                leftAt = 0;
                return;
            } else {
                if (leftAt == 0) leftAt = now;
                if (now - leftAt < 180_000_000L) return;
                close();
            }
        }
        IngredientListSlot hovered = grid.getSlots().filter(slot -> slot.isMouseOver(mouseX, mouseY)).findFirst().orElse(null);
        var group = hovered == null ? null : source.group(hovered.getElement());
        if (group != null && group.representative() == dismissed) return;
        dismissed = null;
        if (candidate != group) {
            candidate = group;
            hoverStarted = now;
        }
        if (group != null && now - hoverStarted >= TagGroupSource.options().jeiGroupHoverDelay * 1_000_000L) {
            closeActive();
            expanded = group;
            anchor = hovered.getArea();
            var font = Minecraft.getInstance().font;
            title = localizedTitle().getString();
            int minimumWidth = font.width(title) + 2 * TagGroupLayout.PADDING;
            layout = TagGroupLayout.create(screenWidth, screenHeight, anchor.x(), anchor.y(), group.elements().size(), minimumWidth, TagGroupLayout.HEADER);
            titleLines = font.split(Component.literal(title), layout.width() - 2 * TagGroupLayout.PADDING);
            layout = TagGroupLayout.create(screenWidth, screenHeight, anchor.x(), anchor.y(), group.elements().size(), minimumWidth,
                    titleLines.size() * font.lineHeight + 2);
            firstRow = 0;
            leftAt = 0;
            rebuildSlots();
            active = this;
        }
    }

    public boolean contains(double mouseX, double mouseY) {
        return isUsable() && layout != null && layout.contains(mouseX, mouseY);
    }

    private Component localizedTitle() {
        Component itemName = expanded.representative().getTypedIngredient().getIngredient(VanillaTypes.ITEM_STACK)
                .map(stack -> stack.getHoverName()).orElse(Component.translatable("gtlcore.jei.tag_group.items"));
        String tag = expanded.tag();
        if (tag.startsWith("@")) return itemName;
        String path = tag.substring(tag.indexOf(':') + 1).replace('/', '.');
        for (String key : List.of("gtlcore.jei.tag_group.category." + tag.replace(':', '.').replace('/', '.'),
                "tag.item." + tag.replace(':', '.').replace('/', '.'), "gtlcore.jei.tag_group.category." + path,
                "gtlcore.jei.tag_group.category." + path.split("\\.", 2)[0])) {
            if (I18n.exists(key)) return Component.translatable(key);
        }
        return Component.translatable("gtlcore.jei.tag_group.variants", itemName);
    }

    public boolean suppressesTooltip(double mouseX, double mouseY) {
        return contains(mouseX, mouseY) || isUsable() && anchor.contains(mouseX, mouseY);
    }

    public Stream<IClickableIngredientInternal<?>> clickable(double mouseX, double mouseY) {
        return hoveredSlot(mouseX, mouseY).flatMap(IngredientListSlot::getClickableIngredient).stream();
    }

    public Stream<IDraggableIngredientInternal<?>> draggable(double mouseX, double mouseY) {
        return hoveredSlot(mouseX, mouseY).flatMap(IngredientListSlot::getDraggableIngredient).stream();
    }

    public Optional<IngredientListSlot> hoveredSlot(double mouseX, double mouseY) {
        return contains(mouseX, mouseY) ? renderer.getSlots().filter(slot -> slot.isMouseOver(mouseX, mouseY)).findFirst() : Optional.empty();
    }

    public boolean scroll(double mouseX, double mouseY, double delta) {
        if (!contains(mouseX, mouseY)) return false;
        if (Math.signum(delta) != Math.signum(scrollRemainder)) scrollRemainder = 0;
        scrollRemainder += delta;
        int steps = (int) scrollRemainder;
        scrollRemainder -= steps;
        int next = Math.max(0, Math.min(layout.maxFirstRow(expanded.elements().size()), firstRow - steps));
        if (next != firstRow) {
            firstRow = next;
            rebuildSlots();
        }
        return true;
    }

    private void rebuildSlots() {
        renderer.clear();
        for (int row = 0; row < layout.rows(); row++) {
            for (int column = 0; column < layout.columns(); column++) {
                renderer.add(new IngredientListSlot(layout.x() + TagGroupLayout.PADDING + column * TagGroupLayout.SLOT,
                        layout.y() + TagGroupLayout.PADDING + layout.headerHeight() + row * TagGroupLayout.SLOT,
                        TagGroupLayout.SLOT, TagGroupLayout.SLOT, 1));
            }
        }
        renderer.set(firstRow * layout.columns(), expanded.elements());
    }

    public void drawBadges(GuiGraphics graphics) {
        var font = Minecraft.getInstance().font;
        var bounds = grid.getArea();
        graphics.enableScissor(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height());
        grid.getSlots().forEach(slot -> {
            var group = source.group(slot.getElement());
            if (group == null) return;
            String count = group.elements().size() > 99 ? "99+" : Integer.toString(group.elements().size());
            var area = slot.getArea();
            graphics.pose().pushPose();
            graphics.pose().translate(area.x() + area.width() - 1, area.y() + 1, 200);
            graphics.pose().scale(0.4f, 0.4f, 1);
            RenderSystem.enableBlend();
            // Vanilla recipe-book forward arrow, with its original transparent pixels.
            graphics.blit(RECIPE_BOOK, -12, 0, 1, 208, 12, 17);
            graphics.drawString(font, count, -14 - font.width(count), 1, 0xFFFFFFFF, true);
            RenderSystem.disableBlend();
            graphics.pose().popPose();
        });
        graphics.disableScissor();
    }

    public void drawPopup(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isUsable()) return;
        var font = Minecraft.getInstance().font;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        RenderSystem.enableBlend();
        // Same panel texture and nine-slice as vanilla's expanded recipe-book group.
        graphics.blitNineSliced(RECIPE_BOOK, layout.x(), layout.y(), layout.width(), layout.height(), 4, 32, 32, 82, 208);
        int titleY = layout.y() + TagGroupLayout.PADDING;
        for (var line : titleLines) {
            if (titleY + font.lineHeight > layout.y() + TagGroupLayout.PADDING + layout.headerHeight()) break;
            graphics.drawString(font, line, layout.x() + TagGroupLayout.PADDING, titleY, 0xFF404040, false);
            titleY += font.lineHeight;
        }
        renderer.getSlots().forEach(slot -> {
            var area = slot.getArea();
            graphics.blit(INVENTORY, area.x(), area.y(), 7, 83, TagGroupLayout.SLOT, TagGroupLayout.SLOT);
        });
        RenderSystem.disableBlend();
        renderer.render(graphics);
        hoveredSlot(mouseX, mouseY).ifPresent(slot -> IngredientGrid.drawHighlight(graphics, slot.getArea()));
        int first = firstRow * layout.columns() + 1;
        int last = Math.min(expanded.elements().size(), (firstRow + layout.rows()) * layout.columns());
        Component footer = Component.translatable("gtlcore.jei.tag_group.range", first, last, expanded.elements().size());
        graphics.drawString(font, footer, layout.x() + TagGroupLayout.PADDING, layout.y() + layout.height() - TagGroupLayout.FOOTER,
                0xFF404040, false);
        graphics.pose().popPose();
    }

    public void drawTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        try {
            if (contains(mouseX, mouseY) && mouseY < layout.y() + TagGroupLayout.PADDING + layout.headerHeight()) {
                graphics.renderTooltip(Minecraft.getInstance().font, Component.literal(title), mouseX, mouseY);
                return;
            }
            hoveredSlot(mouseX, mouseY).flatMap(IngredientListSlot::getOptionalElement)
                    .ifPresent(element -> ((TagGroupGridAccess) grid).gtlcore$drawTagGroupTooltip(graphics, mouseX, mouseY, element));
        } finally {
            graphics.pose().popPose();
        }
    }

    public void drawCarriedStack(GuiGraphics graphics, int mouseX, int mouseY) {
        var minecraft = Minecraft.getInstance();
        if (!isUsable() || minecraft.player == null) return;
        var carried = minecraft.player.containerMenu.getCarried();
        if (carried.isEmpty() || mouseX + 8 < layout.x() || mouseX - 8 >= layout.x() + layout.width() ||
                mouseY + 8 < layout.y() || mouseY - 8 >= layout.y() + layout.height())
            return;
        graphics.pose().pushPose();
        try {
            // The popup is drawn after the container's cursor item. Restore the
            // real carried stack above the popup and its tooltips, including count.
            graphics.pose().translate(0, 0, 1000);
            graphics.renderItem(carried, mouseX - 8, mouseY - 8);
            graphics.renderItemDecorations(minecraft.font, carried, mouseX - 8, mouseY - 8);
        } finally {
            graphics.pose().popPose();
        }
    }

    public void close() {
        if (active == this) active = null;
        candidate = null;
        expanded = null;
        layout = null;
        title = "";
        titleLines = List.of();
        anchor = null;
        leftAt = 0;
        scrollRemainder = 0;
        renderer.clear();
    }
}
