package org.gtlcore.gtlcore.client.gui.widget;

import org.gtlcore.gtlcore.common.block.StructurePlacerBlockEntity.MaterialPreviewEntry;

import com.lowdragmc.lowdraglib.gui.widget.Widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.Supplier;

/**
 * 材料预览：按当前结构需求绘制材料组件图标与「现有/需求」数量。
 * <p>
 * 数据完全由客户端方块实体状态生成，结构/材料变化后自动刷新；悬停显示物品与数量详情。
 * 创造模式下不产生素材消耗，这里改为直接显示提示文字，代替「现有/需求」清单。
 * </p>
 */
public class MaterialPreviewWidget extends Widget {

    private static final int ROW_HEIGHT = 20;
    private static final String CREATIVE_KEY = "ui.quantum_chromodynamic_charge.structure.material.creative";

    private final Supplier<List<MaterialPreviewEntry>> supplier;

    public MaterialPreviewWidget(int x, int y, int width, int height,
                                 Supplier<List<MaterialPreviewEntry>> supplier) {
        super(x, y, width, height);
        this.supplier = supplier;
    }

    /** 本地玩家是否处于创造模式（界面文案与服务端判定一致：以操作玩家为准）。 */
    @OnlyIn(Dist.CLIENT)
    private static boolean creativeMode() {
        Player player = Minecraft.getInstance().player;
        return player != null && player.isCreative();
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        var font = Minecraft.getInstance().font;
        int left = getPosition().x;
        int top = getPosition().y;

        if (creativeMode()) {
            // 创造模式：用提示文字替换材料清单（按控件宽度自动折行、居中）
            List<FormattedCharSequence> lines = font.split(
                    Component.translatable(CREATIVE_KEY), Math.max(40, getSize().width));
            int lineHeight = font.lineHeight + 1;
            int y = top + Math.max(0, (getSize().height - lines.size() * lineHeight) / 2);
            for (FormattedCharSequence line : lines) {
                int x = left + Math.max(2, (getSize().width - font.width(line)) / 2);
                graphics.drawString(font, line, x, y, 0xFFB0B0B0, false);
                y += lineHeight;
            }
            return;
        }

        List<MaterialPreviewEntry> entries = supplier.get();
        if (entries.isEmpty()) return;
        int maxRows = Math.max(1, getSize().height / ROW_HEIGHT);

        for (int i = 0; i < entries.size() && i < maxRows; i++) {
            MaterialPreviewEntry entry = entries.get(i);
            int y = top + i * ROW_HEIGHT;
            graphics.renderItem(entry.stack(), left, y);
            graphics.renderItemDecorations(font, entry.stack(), left, y);
            String text = entry.have() + "/" + entry.need();
            int color = entry.have() >= entry.need() ? 0xFF55FF55 : 0xFFFF5555;
            graphics.drawString(font, text, left + 22, y + 5, color, false);
        }
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (creativeMode() || !isMouseOverElement(mouseX, mouseY)) return;
        List<MaterialPreviewEntry> entries = supplier.get();
        int index = (int) ((mouseY - getPosition().y) / ROW_HEIGHT);
        if (index < 0 || index >= entries.size()) return;
        MaterialPreviewEntry entry = entries.get(index);
        if (gui == null || gui.getModularUIGui() == null) return;
        gui.getModularUIGui().setHoverTooltip(List.of(
                entry.stack().getHoverName(),
                Component.translatable("ui.quantum_chromodynamic_charge.structure.material.line",
                        entry.stack().getHoverName(), entry.have(), entry.need())),
                entry.stack(), null, null);
    }
}
