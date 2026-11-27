package org.gtlcore.gtlcore.client.gui;

import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * 界面槽位访问控制：只有「当前标签页」的格子允许放入物品。
 * <p>
 * 材料页 = 材料格；导出页 = 坐标卡格；蓝图/设置页不接受任何放入。
 * shift 点击背包物品时服务端按 {@link SlotWidget#canPutStack} 过滤，因此标签页下标要同步到服务端。
 * 取出不受限制。
 * </p>
 */
public final class StructurePlacerWorkspace {

    public static final int TAB_BLUEPRINT = 0;
    public static final int TAB_MATERIAL = 1;
    public static final int TAB_SETTINGS = 2;
    public static final int TAB_EXPORT = 3;

    private final List<SlotWidget> materialSlots = new ArrayList<>();
    private final List<SlotWidget> cardSlots = new ArrayList<>();

    public void addMaterialSlot(SlotWidget slot) {
        materialSlots.add(slot);
    }

    public void addCardSlot(SlotWidget slot) {
        cardSlots.add(slot);
    }

    /** 按当前标签页打开/关闭各页格子的放入权限。 */
    public void setActiveTab(int tab) {
        boolean material = tab == TAB_MATERIAL;
        boolean export = tab == TAB_EXPORT;
        for (SlotWidget slot : materialSlots) slot.setCanPutItems(material);
        for (SlotWidget slot : cardSlots) slot.setCanPutItems(export);
    }
}
