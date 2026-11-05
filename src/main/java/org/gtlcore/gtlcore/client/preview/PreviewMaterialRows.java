package org.gtlcore.gtlcore.client.preview;

import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;

import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.jei.IngredientIO;
import com.lowdragmc.lowdraglib.utils.CycleItemStackHandler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import appeng.api.stacks.AEItemKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class PreviewMaterialRows {

    private PreviewMaterialRows() {}

    public record Material(AEItemKey item, List<AEItemKey> alternatives, int section) {}

    public static final class Selection {

        private AEItemKey selected;
        private Set<AEItemKey> allowed = Set.of();

        public AEItemKey selected() {
            return selected;
        }

        public void toggle(AEItemKey item) {
            selected = item.equals(selected) ? null : item;
        }

        public void position(TraceabilityPredicate predicate) {
            allowed = candidates(predicate);
        }

        public void clear() {
            selected = null;
            allowed = Set.of();
        }

        public int borderColor(AEItemKey item) {
            if (allowed.contains(item)) return 0xffff4040;
            return item.equals(selected) ? 0xff80ff80 : 0;
        }
    }

    public static List<SimplePredicate> predicates(TraceabilityPredicate predicate) {
        if (predicate == null) return List.of();
        var result = new LinkedHashSet<>(predicate.common);
        result.addAll(predicate.limited);
        result.removeIf(simple -> simple == null || simple.candidates == null);
        return List.copyOf(result);
    }

    public static Set<AEItemKey> candidates(TraceabilityPredicate predicate) {
        var result = new LinkedHashSet<AEItemKey>();
        for (var simple : predicates(predicate)) {
            simple.getCandidates().stream().filter(stack -> !stack.isEmpty()).map(AEItemKey::of).forEach(result::add);
        }
        return result;
    }

    public static List<AEItemKey> alternatives(TraceabilityPredicate predicate, AEItemKey item) {
        var result = new LinkedHashSet<AEItemKey>();
        for (var simple : predicates(predicate)) {
            var options = simple.getCandidates().stream().filter(stack -> !stack.isEmpty()).map(AEItemKey::of).distinct().toList();
            if (options.contains(item)) result.addAll(options);
        }
        return List.copyOf(result);
    }

    public static String countLabel(int minimum, int maximum) {
        if (minimum < 0 && maximum < 0) return "";
        int lower = Math.max(0, minimum);
        return lower == maximum ? String.valueOf(lower) : lower + "~" + (maximum < 0 ? "∞" : maximum);
    }

    public static void drawCount(GuiGraphics graphics, int x, int y, String label) {
        if (label.isEmpty()) return;
        var font = Minecraft.getInstance().font;
        graphics.pose().pushPose();
        float scale = Math.min(0.75f, 17f / font.width(label));
        graphics.pose().translate(x + 18, y + 18, 220);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, label, -font.width(label), -font.lineHeight, 0xffffffff, true);
        graphics.pose().popPose();
    }

    public static void drawBorder(GuiGraphics graphics, int x, int y, int color) {
        if (color == 0) return;
        graphics.fill(x, y, x + 18, y + 1, color);
        graphics.fill(x, y + 17, x + 18, y + 18, color);
        graphics.fill(x, y, x + 1, y + 18, color);
        graphics.fill(x + 17, y, x + 18, y + 18, color);
    }

    public static void populate(Map<BlockPos, AEItemKey> materials, Map<BlockPos, TraceabilityPredicate> predicates,
                                BlockPos controller, DraggableScrollableWidgetGroup row,
                                DraggableScrollableWidgetGroup candidates, Selection selection,
                                Runnable refresh, Consumer<Material> expand) {
        row.clearAllWidgets();
        candidates.clearAllWidgets();
        candidates.setVisible(false);
        candidates.setActive(false);
        var amounts = new LinkedHashMap<Material, Integer>();
        materials.forEach((pos, item) -> {
            var options = alternatives(predicates.get(pos), item);
            int section = pos.equals(controller) ? 0 : options.size() > 1 ? 1 : 2;
            amounts.merge(new Material(item, options, section), 1, Integer::sum);
        });
        var entries = new ArrayList<>(amounts.entrySet());
        entries.sort(Comparator.comparingInt(entry -> entry.getKey().section()));
        var labels = new LinkedHashMap<AEItemKey, String>();
        var tips = new LinkedHashMap<AEItemKey, List<Component>>();
        predicates.values().stream().distinct().forEach(predicate -> {
            for (var simple : predicates(predicate)) {
                var label = countLabel(simple.minCount, simple.maxCount);
                for (var stack : simple.getCandidates()) {
                    if (stack.isEmpty()) continue;
                    var key = AEItemKey.of(stack);
                    if (!label.isEmpty()) labels.merge(key, label, (one, two) -> one.equals(two) ? one : one + " / " + two);
                    tips.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(PreviewCandidatePanel.Group.of(simple, predicate).tooltips());
                }
            }
        });
        var handler = new CycleItemStackHandler(entries.stream().map(entry -> List.of(entry.getKey().item().toStack(
                entry.getKey().section() == 1 ? 1 : entry.getValue()))).toList());
        int x = 4;
        int section = 0;
        Material[] expanded = { null };
        for (int i = 0; i < entries.size(); i++) {
            var material = entries.get(i).getKey();
            while (section < material.section()) {
                row.addWidget(new ImageWidget(x + 2, 1, 1, 16, new ColorRectTexture(0xffdddddd)));
                x += 6;
                section++;
            }
            var slot = new SlotWidget(handler, i, x, 0, false, false) {

                @Override
                public List<Object> getXEIIngredients() {
                    return material.section() == 1 ? List.of() : super.getXEIIngredients();
                }

                @Override
                public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                    super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
                    drawBorder(graphics, getPositionX(), getPositionY(), selection.borderColor(material.item()));
                    if (material.section() == 1) drawCount(graphics, getPositionX(), getPositionY(), labels.getOrDefault(material.item(), ""));
                }

                @Override
                public boolean mouseClicked(double mouseX, double mouseY, int button) {
                    if (button != 0 || material.section() != 1 || !isMouseOverElement(mouseX, mouseY)) {
                        return super.mouseClicked(mouseX, mouseY, button);
                    }
                    boolean show = !material.equals(expanded[0]);
                    expanded[0] = show ? material : null;
                    candidates.clearAllWidgets();
                    candidates.setScrollXOffset(0);
                    candidates.setVisible(show);
                    candidates.setActive(show);
                    selection.toggle(material.item());
                    if (show) populateCandidates(candidates, material.alternatives(), selection, refresh, labels, tips);
                    expand.accept(show ? material : null);
                    refresh.run();
                    return true;
                }
            };
            slot.setBackgroundTexture(new ColorRectTexture(0x4fffffff));
            slot.setIngredientIO(material.section() == 1 ? IngredientIO.RENDER_ONLY : IngredientIO.INPUT);
            slot.setOnAddedTooltips((widget, tooltip) -> {
                tooltip.add(Component.translatable("gui.gtlcore.preview.material_amount", amounts.get(material)));
                if (material.section() == 1) {
                    tooltip.add(Component.translatable("gui.gtlcore.preview.expand_material"));
                    tooltip.addAll(tips.getOrDefault(material.item(), List.of()));
                }
            });
            row.addWidget(slot);
            x += 18;
        }
    }

    private static void populateCandidates(DraggableScrollableWidgetGroup row, List<AEItemKey> alternatives,
                                           Selection selection, Runnable refresh, Map<AEItemKey, String> labels,
                                           Map<AEItemKey, List<Component>> tips) {
        var handler = new CycleItemStackHandler(alternatives.stream().map(item -> List.of(item.toStack())).toList());
        for (int i = 0; i < alternatives.size(); i++) {
            var item = alternatives.get(i);
            row.addWidget(new SlotWidget(handler, i, 4 + i * 18, 0, false, false) {

                @Override
                public List<Object> getXEIIngredients() {
                    return List.of();
                }

                @Override
                public boolean mouseClicked(double mouseX, double mouseY, int button) {
                    if (button == 0 && isMouseOverElement(mouseX, mouseY)) {
                        selection.toggle(item);
                        refresh.run();
                        return true;
                    }
                    return super.mouseClicked(mouseX, mouseY, button);
                }

                @Override
                public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                    super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
                    drawBorder(graphics, getPositionX(), getPositionY(), selection.borderColor(item));
                    drawCount(graphics, getPositionX(), getPositionY(), labels.getOrDefault(item, ""));
                }
            }.setBackgroundTexture(new ColorRectTexture(0x4fffffff)).setIngredientIO(IngredientIO.RENDER_ONLY)
                    .setOnAddedTooltips((slot, tooltip) -> tooltip.addAll(tips.getOrDefault(item, List.of()))));
        }
    }
}
