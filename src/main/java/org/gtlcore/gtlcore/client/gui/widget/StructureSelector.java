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

import java.util.List;
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
        if (super.isMouseOverElement(mouseX, mouseY) && (popup == null || !popup.isMouseOverElement(mouseX, mouseY))) {
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
        bringToFront();
        if (getGui() != null) setFocus(true);
    }

    /**
     * 展开时把自身移到父组控件列表末尾。
     * <p>
     * LDLib 的绘制/命中顺序就是父组 {@code widgets} 的顺序，下拉列表是本控件的子控件，
     * 若本控件不是父组最后一个控件（例如下面还有第二个下拉框/说明文字），
     * 展开后的列表会被后绘制的兄弟控件盖住。
     * </p>
     * <p>
     * 这里直接调整父组列表顺序，而不是 removeWidget/addWidget：
     * 后者会清空 gui/parent 并触发重排，可能打断正在进行的交互。
     * </p>
     */
    private void bringToFront() {
        WidgetGroup parentGroup = getParent();
        if (parentGroup == null) return;
        List<Widget> siblings = parentGroup.widgets;
        int lastIndex = siblings.size() - 1;
        if (lastIndex < 0 || siblings.get(lastIndex) == this) return;
        int index = siblings.indexOf(this);
        if (index < 0) return;
        siblings.remove(index);
        siblings.add(this);
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

        for (int i = 0; i < size; i++) {
            int index = i;
            SelectableWidgetGroup row = new SelectableWidgetGroup(0, i * ROW_HEIGHT, getSize().width, ROW_HEIGHT);
            row.setHoverTexture(new ColorRectTexture(0x40FFFFFF));
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
