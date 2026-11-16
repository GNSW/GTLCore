package org.gtlcore.gtlcore.client.gui.widget;

import com.gregtechceu.gtceu.api.gui.GuiTextures;

import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SelectableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Size;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.Nullable;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

/**
 * 结构 UI 用的下拉选择器。
 * <p>
 * 显示文案完全在客户端按方块实体状态渲染（保证中文/英文即时本地化），
 * 选择动作通过客户端 action 发回服务端执行；列表可滚动。
 * </p>
 */
public class StructureSelector extends WidgetGroup {

    private static final int ROW_HEIGHT = 14;
    private static final int MAX_POPUP_HEIGHT = 70;
    private static final int ACTION_PICK = 42;

    private final IntSupplier sizeSupplier;
    private final IntSupplier valueSupplier;
    private final IntFunction<Component> labelFunction;
    private final IntConsumer serverPick;
    private final LabelWidget valueLabel;
    private final DraggableScrollableWidgetGroup popup;
    private boolean open;

    public StructureSelector(int x, int y, int width,
                             IntSupplier sizeSupplier,
                             IntSupplier valueSupplier,
                             IntFunction<Component> labelFunction,
                             IntConsumer serverPick) {
        super(x, y, width, 16);
        this.sizeSupplier = sizeSupplier;
        this.valueSupplier = valueSupplier;
        this.labelFunction = labelFunction;
        this.serverPick = serverPick;

        setBackground(GuiTextures.BUTTON);
        setHoverTexture(new ColorBorderTexture(1, 0xFFFFFFFF));

        valueLabel = new LabelWidget(4, 4, () -> currentLabel());
        valueLabel.setClientSideWidget();
        addWidget(valueLabel);

        popup = new DraggableScrollableWidgetGroup(0, 16, width, 1);
        popup.setBackground(GuiTextures.BACKGROUND_INVERSE);
        popup.setVisible(false).setActive(false);
        addWidget(popup);
    }

    private String currentLabel() {
        int size = sizeSupplier.getAsInt();
        if (size <= 0) return "§7-";
        int index = Math.max(0, Math.min(size - 1, valueSupplier.getAsInt()));
        Component label = labelFunction.apply(index);
        return truncate(label == null ? "-" : label.getString(), 20);
    }

    /** 下拉/选项标签过长时截断，避免超出按钮宽度。 */
    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        return text.substring(0, Math.max(1, maxChars - 1)) + "…";
    }

    @Override
    public boolean isMouseOverElement(double mouseX, double mouseY) {
        return super.isMouseOverElement(mouseX, mouseY) || (open && popup.isMouseOverElement(mouseX, mouseY));
    }

    @Override
    public @Nullable Widget getHoverElement(double mouseX, double mouseY) {
        if (open) {
            Widget hovered = popup.getHoverElement(mouseX, mouseY);
            if (hovered != null) return hovered;
        }
        if (super.isMouseOverElement(mouseX, mouseY)) return this;
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 展开状态：落在列表区域内的点击一律归本控件处理，
        // 不能再往兄弟控件传递（列表向下展开会压在下方控件上，
        // 传下去会误触发那个控件的点击，表现为“点预设项却打开了样板下拉”）。
        if (open && popup.isMouseOverElement(mouseX, mouseY)) {
            super.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        if (super.isMouseOverElement(mouseX, mouseY)) {
            if (open) {
                close();
            } else {
                open();
            }
            playButtonClickSound();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void open() {
        rebuildRows();
        open = true;
        popup.setVisible(true).setActive(true);
        if (getGui() != null) setFocus(true);
    }

    private void close() {
        open = false;
        popup.setVisible(false).setActive(false);
    }

    @Override
    public void onFocusChanged(@Nullable Widget lastFocus, Widget focus) {
        if (open && focus != this && (lastFocus == null || !lastFocus.isParent(this))) {
            close();
        }
    }

    private void rebuildRows() {
        popup.clearAllWidgets();
        int size = Math.max(0, sizeSupplier.getAsInt());
        int height = Math.min(MAX_POPUP_HEIGHT, Math.max(ROW_HEIGHT, size * ROW_HEIGHT));
        popup.setSize(new Size(getSize().width, height));
        int current = valueSupplier.getAsInt();

        for (int i = 0; i < size; i++) {
            int index = i;
            SelectableWidgetGroup row = new SelectableWidgetGroup(0, i * ROW_HEIGHT, getSize().width, ROW_HEIGHT);
            row.setHoverTexture(new ColorRectTexture(0x40FFFFFF));
            if (index == current) {
                // 当前选中项高亮，避免“点没点上都看不出来”
                row.setBackground(new ColorRectTexture(0x33FFD966));
            }
            LabelWidget label = new LabelWidget(3, 3, () -> {
                Component component = labelFunction.apply(index);
                return truncate(component == null ? "" : component.getString(), 22);
            });
            label.setClientSideWidget();
            row.addWidget(label);
            row.setOnSelected(selected -> pick(index));
            popup.addWidget(row);
        }
    }

    private void pick(int index) {
        close();
        if (!isRemote()) return;
        writeClientAction(ACTION_PICK, buffer -> buffer.writeVarInt(index));
    }

    @Override
    public void handleClientAction(int id, FriendlyByteBuf buffer) {
        super.handleClientAction(id, buffer);
        if (id == ACTION_PICK) {
            serverPick.accept(buffer.readVarInt());
        }
    }
}
