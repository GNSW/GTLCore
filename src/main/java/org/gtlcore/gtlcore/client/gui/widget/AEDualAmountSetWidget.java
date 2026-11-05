package org.gtlcore.gtlcore.client.gui.widget;

import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.utils.Position;

import net.minecraft.network.FriendlyByteBuf;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

public class AEDualAmountSetWidget extends Widget {

    private static final int SET_SLOT_ID = 0;
    static final int WIDGET_WIDTH = AmountBoxRenderer.WIDTH;
    static final int WIDGET_HEIGHT = AmountBoxRenderer.HEIGHT;
    private static final int TEXT_X = AmountBoxRenderer.TEXT_X;
    private static final int INPUT_Y = AmountBoxRenderer.INPUT_Y;
    private static final int INPUT_WIDTH = AmountBoxRenderer.INPUT_WIDTH;
    private static final int INPUT_HEIGHT = AmountBoxRenderer.INPUT_HEIGHT;

    private int index = -1;
    @Getter
    private final TextFieldWidget amountText;
    private final AEDualConfigWidget parentWidget;

    public AEDualAmountSetWidget(int x, int y, AEDualConfigWidget parentWidget) {
        super(x, y, WIDGET_WIDTH, WIDGET_HEIGHT);
        this.parentWidget = parentWidget;
        this.amountText = new TextFieldWidget(
                x + TEXT_X,
                y + INPUT_Y,
                INPUT_WIDTH,
                INPUT_HEIGHT,
                this::getAmountStr,
                this::setNewAmount)
                .setNumbersOnly(0, Long.MAX_VALUE)
                .setMaxStringLength(19);
    }

    public void setSlotIndex(int index) {
        this.index = index;
        if (isRemote()) {
            writeClientAction(SET_SLOT_ID, buffer -> buffer.writeVarInt(index));
        }
    }

    @Override
    public void handleClientAction(int id, FriendlyByteBuf buffer) {
        super.handleClientAction(id, buffer);
        if (id == SET_SLOT_ID) {
            this.index = buffer.readVarInt();
        }
    }

    @Override
    public void drawInBackground(net.minecraft.client.gui.@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
        Position position = getPosition();
        AmountBoxRenderer.draw(graphics, mouseX, mouseY, position.x, position.y,
                AmountBoxRenderer.label(isFluidSelected()));
    }

    private boolean isFluidSelected() {
        if (this.index < 0) {
            return false;
        }
        var config = this.parentWidget.getConfig(this.index).getConfig();
        return config != null && config.what() instanceof appeng.api.stacks.AEFluidKey;
    }

    private String getAmountStr() {
        if (this.index < 0) {
            return "0";
        }
        var slot = this.parentWidget.getConfig(this.index);
        return slot.getConfig() == null ? "0" : String.valueOf(slot.getConfig().amount());
    }

    private void setNewAmount(String amount) {
        if (this.index < 0) {
            return;
        }
        try {
            long value = Long.parseLong(amount);
            var slot = this.parentWidget.getConfig(this.index);
            if (value > 0 && slot.getConfig() != null) {
                slot.setConfig(new appeng.api.stacks.GenericStack(slot.getConfig().what(), value));
            }
        } catch (NumberFormatException ignored) {
            // TextFieldWidget can briefly emit an incomplete numeric value.
        }
    }
}
