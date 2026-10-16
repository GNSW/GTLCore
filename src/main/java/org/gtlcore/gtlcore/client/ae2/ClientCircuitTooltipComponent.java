package org.gtlcore.gtlcore.client.ae2;

import org.gtlcore.gtlcore.integration.ae2.CircuitTooltipComponent;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class ClientCircuitTooltipComponent implements ClientTooltipComponent {

    private static final int COLUMNS = 6;
    private static final int SLOT = 18;
    private static final int PAD_LEFT = 4;
    private static final int ELLIPSIS_WIDTH = 12;

    private final List<ItemStack> stacks;
    private final boolean more;

    public ClientCircuitTooltipComponent(CircuitTooltipComponent component) {
        this.stacks = component.stacks();
        this.more = component.more();
    }

    @Override
    public int getHeight() {
        return ((stacks.size() + COLUMNS - 1) / COLUMNS) * SLOT;
    }

    @Override
    public int getWidth(Font font) {
        return PAD_LEFT + Math.min(stacks.size(), COLUMNS) * SLOT + (more ? ELLIPSIS_WIDTH : 0);
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
        for (int i = 0; i < stacks.size(); i++) {
            graphics.renderItem(stacks.get(i), x + PAD_LEFT + i % COLUMNS * SLOT, y + i / COLUMNS * SLOT);
        }
        if (more) {
            graphics.drawString(font, Component.literal("...").withStyle(ChatFormatting.GRAY),
                    x + PAD_LEFT + Math.min(stacks.size(), COLUMNS) * SLOT, y + SLOT - 9, 0xFFFFFF, false);
        }
    }
}
