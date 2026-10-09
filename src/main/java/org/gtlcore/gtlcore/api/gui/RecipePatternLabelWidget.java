package org.gtlcore.gtlcore.api.gui;

import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Fixed, single-line text with its current full value available on hover. */
public class RecipePatternLabelWidget extends ImageWidget {

    private final Supplier<String> label;

    public RecipePatternLabelWidget(int x, int y, int width, int height, Supplier<String> label, int color, boolean centered) {
        super(x, y, width, height, new TextTexture(label).setColor(color).setDropShadow(false).setWidth(width)
                .setType(centered ? TextTexture.TextType.HIDE : TextTexture.TextType.LEFT_HIDE));
        this.label = label;
    }

    @Override
    protected void drawTooltipTexts(int mouseX, int mouseY) {
        if (!isMouseOverElement(mouseX, mouseY) || getHoverElement(mouseX, mouseY) != this ||
                gui == null || gui.getModularUIGui() == null)
            return;
        List<Component> hints = new ArrayList<>(tooltipTexts);
        String fullText = label.get();
        if (!fullText.isBlank() && hints.stream().noneMatch(hint -> hint.getString().equals(fullText))) {
            hints.add(0, Component.literal(fullText));
        }
        if (!hints.isEmpty()) gui.getModularUIGui().setHoverTooltip(List.copyOf(hints), ItemStack.EMPTY, null, null);
    }
}
