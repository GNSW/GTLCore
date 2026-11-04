package org.gtlcore.gtlcore.client.gui.widget;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.integration.ae2.slot.*;

import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Position;
import com.lowdragmc.lowdraglib.utils.Size;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import appeng.api.stacks.GenericStack;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.function.IntConsumer;

/**
 * @author EasterFG on 2025/3/7
 */
public class AEDualConfigWidget extends WidgetGroup {

    protected final IConfigurableSlot[] config;
    protected Int2ObjectMap<IConfigurableSlot> changeMap = new Int2ObjectOpenHashMap<>();

    protected IConfigurableSlot[] cached;
    protected IConfigurableSlot[] displayList;
    protected ButtonWidget pageUpWidget;
    protected ButtonWidget pageDownWidget;
    protected AEDualAmountSetWidget amountSetWidget;
    private final ExportOnlyAEItemList aeItem;
    private final ExportOnlyAEFluidList aeFluid;
    private final IntConsumer pageSetter;
    private final boolean amountEditingEnabled;

    public static final int CONFIG_SIZE = 16;
    protected int page;
    protected final int MAX_PAGE;
    private final int offset;
    private final int pageSize;
    private final int rows;

    private static final int UPDATE_ID = 1001;

    public AEDualConfigWidget(int x, int y, ExportOnlyAEItemList aeItem, ExportOnlyAEFluidList aeFluid, IntConsumer pageSetter, int page) {
        this(x, y, aeItem, aeFluid, pageSetter, page, false, 2);
    }

    public AEDualConfigWidget(int x, int y, ExportOnlyAEItemList aeItem, ExportOnlyAEFluidList aeFluid,
                              IntConsumer pageSetter, int page, boolean amountEditingEnabled) {
        this(x, y, aeItem, aeFluid, pageSetter, page, amountEditingEnabled, 2);
    }

    public AEDualConfigWidget(int x, int y, ExportOnlyAEItemList aeItem, ExportOnlyAEFluidList aeFluid,
                              IntConsumer pageSetter, int page, boolean amountEditingEnabled, int rows) {
        super(new Position(x, y), new Size(8 * 18, Math.max(1, rows) * 38 + 17));
        this.aeItem = aeItem;
        this.aeFluid = aeFluid;
        this.pageSetter = pageSetter;
        this.amountEditingEnabled = amountEditingEnabled;
        this.rows = Math.max(1, rows);
        this.pageSize = this.rows * 8;
        this.offset = aeItem.getSize();
        this.MAX_PAGE = Math.max(1, offset / pageSize);
        this.page = Math.max(1, Math.min(page, MAX_PAGE));
        this.config = marge();
        init();
        int navigationY = this.rows * 38 + 1;
        this.pageUpWidget = new ButtonWidget(3, navigationY, 16, 16,
                GuiTextures.BUTTON_LEFT, this::pageUp);
        this.pageDownWidget = new ButtonWidget(125, navigationY, 16, 16,
                GuiTextures.BUTTON_RIGHT, this::pageDown);
        this.addWidget(new LabelWidget(66, navigationY + 5, () -> this.page + " / " + MAX_PAGE));
        this.addWidget(this.pageUpWidget);
        this.addWidget(this.pageDownWidget);
        int amountSetX = (getSizeWidth() - AEDualAmountSetWidget.WIDGET_WIDTH) / 2;
        int amountSetY = getSizeHeight() + 4;
        this.amountSetWidget = new AEDualAmountSetWidget(amountSetX, amountSetY, this);
        this.addWidget(this.amountSetWidget);
        this.addWidget(this.amountSetWidget.getAmountText());
        this.disableAmount();
    }

    protected void pageUp(ClickData data) {
        if (page < 2) return;
        disableAmount();
        page--;
        pageSetter.accept(page);
    }

    protected void pageDown(ClickData data) {
        if (page >= MAX_PAGE) return;
        disableAmount();
        page++;
        pageSetter.accept(page);
    }

    protected boolean isAutoPull() {
        return aeFluid.isAutoPull() || aeItem.isAutoPull();
    }

    public int getSlotIndex(int visibleIndex) {
        return (page - 1) * pageSize + visibleIndex;
    }

    private IConfigurableSlot[] marge() {
        ExportOnlyAEItemSlot[] itemSlots = aeItem.getInventory();
        ExportOnlyAEFluidSlot[] fluidSlots = aeFluid.getInventory();
        IConfigurableSlot[] result = new IConfigurableSlot[itemSlots.length + fluidSlots.length];
        System.arraycopy(itemSlots, 0, result, 0, itemSlots.length);
        System.arraycopy(fluidSlots, 0, result, itemSlots.length, fluidSlots.length);
        return result;
    }

    protected final void init() {
        int line;
        this.displayList = new IConfigurableSlot[this.config.length];
        this.cached = new IConfigurableSlot[this.config.length];
        int half = this.config.length / 2;
        for (int index = 0; index < this.config.length; index++) {
            this.displayList[index] = index < half ? new ExportOnlyAEItemSlot() : new ExportOnlyAEFluidSlot();
            this.cached[index] = index < half ? new ExportOnlyAEItemSlot() : new ExportOnlyAEFluidSlot();
            line = index / 8;
            if (line >= rows) continue;
            this.addWidget(new AEDualConfigSlotWidget((index - line * 8) * 18, line * (18 * 2 + 2), this, index));
        }
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.amountEditingEnabled && this.amountSetWidget.isVisible() &&
                this.amountSetWidget.getAmountText().mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        for (Widget w : this.widgets) {
            if (w instanceof AEDualConfigSlotWidget slot) {
                slot.setSelect(false);
            }
        }
        this.disableAmount();
        return super.mouseClicked(mouseX, mouseY, button);
    }

    public void enableAmount(int index) {
        if (!this.amountEditingEnabled) {
            return;
        }
        this.amountSetWidget.setSlotIndex(index);
        this.amountSetWidget.setVisible(true);
        this.amountSetWidget.getAmountText().setVisible(true);
    }

    public void disableAmount() {
        this.amountSetWidget.setSlotIndex(-1);
        this.amountSetWidget.setVisible(false);
        this.amountSetWidget.getAmountText().setVisible(false);
    }

    public boolean isAmountEditingEnabled() {
        return this.amountEditingEnabled;
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        this.changeMap.clear();
        for (int index = 0; index < this.config.length; index++) {
            IConfigurableSlot newSlot = this.config[index];
            IConfigurableSlot oldSlot = this.cached[index];
            GenericStack nConfig = newSlot.getConfig();
            GenericStack nStock = newSlot.getStock();
            GenericStack oConfig = oldSlot.getConfig();
            GenericStack oStock = oldSlot.getStock();
            if (!areAEStackCountsEqual(nConfig, oConfig) || !areAEStackCountsEqual(nStock, oStock)) {
                this.changeMap.put(index, newSlot.copy());
                this.cached[index] = this.config[index].copy();
                this.gui.holder.markAsDirty();
            }
        }
        if (!this.changeMap.isEmpty()) {
            this.writeUpdateInfo(UPDATE_ID, buf -> {
                buf.writeVarInt(this.changeMap.size());
                for (int index : this.changeMap.keySet()) {
                    GenericStack sConfig = this.changeMap.get(index).getConfig();
                    GenericStack sStock = this.changeMap.get(index).getStock();
                    buf.writeVarInt(index);
                    if (sConfig != null) {
                        buf.writeBoolean(true);
                        GenericStack.writeBuffer(sConfig, buf);
                    } else {
                        buf.writeBoolean(false);
                    }
                    if (sStock != null) {
                        buf.writeBoolean(true);
                        GenericStack.writeBuffer(sStock, buf);
                    } else {
                        buf.writeBoolean(false);
                    }
                }
            });
        }
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
        super.readUpdateInfo(id, buffer);
        if (id == UPDATE_ID) {
            int size = buffer.readVarInt();
            for (int i = 0; i < size; i++) {
                int index = buffer.readVarInt();
                IConfigurableSlot slot = this.displayList[index];
                if (buffer.readBoolean()) {
                    slot.setConfig(GenericStack.readBuffer(buffer));
                } else {
                    slot.setConfig(null);
                }
                if (buffer.readBoolean()) {
                    slot.setStock(GenericStack.readBuffer(buffer));
                } else {
                    slot.setStock(null);
                }
            }
        }
    }

    public boolean hasStackInConfig(GenericStack stack) {
        return aeItem.hasStackInConfig(stack, true) ||
                aeFluid.hasStackInConfig(stack, true);
    }

    public final void setItemConfig(int index, GenericStack stack) {
        var fluidConfig = getFluidConfig(index);
        if (fluidConfig != null) {
            fluidConfig.setConfig(null);
        }
        getItemConfig(index).setConfig(stack);
    }

    public final void setFluidConfig(int index, GenericStack stack) {
        var itemConfig = getItemConfig(index);
        if (itemConfig != null) {
            itemConfig.setConfig(null);
        }
        getFluidConfig(index).setConfig(stack);
    }

    public final IConfigurableSlot getItemConfig(int index) {
        return config[index];
    }

    public final IConfigurableSlot getFluidConfig(int index) {
        return config[index + offset];
    }

    public final IConfigurableSlot getConfig(int index) {
        var c = getItemConfig(index);
        if (c.getConfig() == null) {
            return getFluidConfig(index);
        }
        return c;
    }

    public final IConfigurableSlot getDisplay(int index) {
        var c = displayList[index];
        if (c.getConfig() == null) {
            return displayList[index + offset];
        }
        return c;
    }

    protected final boolean areAEStackCountsEqual(GenericStack s1, GenericStack s2) {
        if (s2 == s1) {
            return true;
        }
        if (s1 != null && s2 != null) {
            return s1.amount() == s2.amount() && s1.what().matches(s2);
        }
        return false;
    }
}
