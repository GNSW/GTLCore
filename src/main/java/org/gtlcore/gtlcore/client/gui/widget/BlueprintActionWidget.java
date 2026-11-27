package org.gtlcore.gtlcore.client.gui.widget;

import org.gtlcore.gtlcore.common.block.StructurePlacerBlockEntity;

import com.lowdragmc.lowdraglib.gui.widget.Widget;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 蓝图选择的动作中转控件。
 * <p>
 * 蓝图选择界面是纯客户端覆盖页（服务端不存在这棵控件树），而选择结果必须由服务端生效。
 * LDLib 的客户端 action 是按“控件在父组里的下标”定位的，所以动态生成的客户端行不能自己发包；
 * 这里放一个**服务端与客户端都存在、且下标固定**的空控件，让覆盖页里的行动作调用它，
 * 再把参数（内置下标 / 本地相对路径）转发到方块实体。
 * </p>
 */
public final class BlueprintActionWidget extends Widget {

    /** 选择蓝图：kind(0=内置,1=本地) + preset + index + path。 */
    private static final int ACTION_SELECT = 61;
    /** 重扫蓝图目录。 */
    private static final int ACTION_RESCAN = 62;
    /** 切换标签页（用于限定 shift 点击只进当前页的格子）。 */
    private static final int ACTION_TAB = 63;
    /** 选中本地蓝图（带客户端解析出的尺寸）。 */
    private static final int ACTION_LOCAL = 64;
    /** 确认风险后强制读取本地蓝图。 */
    private static final int ACTION_LOCAL_FORCED = 65;

    private final StructurePlacerBlockEntity be;

    public BlueprintActionWidget(StructurePlacerBlockEntity be) {
        super(0, 0, 1, 1);
        this.be = be;
        // 不参与绘制/命中测试，但仍在控件树里，下标两端一致
        setVisible(false);
    }

    /** 选择内置结构。 */
    public void selectBuiltin(int preset, int index) {
        writeClientAction(ACTION_SELECT, buffer -> {
            buffer.writeByte(0);
            buffer.writeVarInt(preset);
            buffer.writeVarInt(index);
            buffer.writeUtf("");
        });
    }

    /**
     * 选中本地蓝图：客户端已解析出尺寸/方块数时直接带上，服务端不必再读文件。
     * 尺寸传 0 表示客户端没读到，由服务端自己解析。
     */
    public void applyLocal(String path, int sizeX, int sizeY, int sizeZ, int blocks, String author) {
        writeClientAction(ACTION_LOCAL, buffer -> {
            buffer.writeUtf(path == null ? "" : path);
            buffer.writeVarInt(Math.max(0, sizeX));
            buffer.writeVarInt(Math.max(0, sizeY));
            buffer.writeVarInt(Math.max(0, sizeZ));
            buffer.writeVarInt(Math.max(0, blocks));
            buffer.writeUtf(author == null ? "" : author);
        });
    }

    /** 玩家确认风险后强制读取本地蓝图。 */
    public void applyLocalForced(String path) {
        writeClientAction(ACTION_LOCAL_FORCED, buffer -> buffer.writeUtf(path == null ? "" : path));
    }

    /** 重扫蓝图目录。 */
    public void rescan() {
        writeClientAction(ACTION_RESCAN, buffer -> {});
    }

    /** 通知服务端当前标签页下标。 */
    public void setTab(int index) {
        writeClientAction(ACTION_TAB, buffer -> buffer.writeVarInt(index));
    }

    @Override
    public void handleClientAction(int id, FriendlyByteBuf buffer) {
        super.handleClientAction(id, buffer);
        if (id == ACTION_SELECT) {
            int kind = buffer.readByte();
            int preset = buffer.readVarInt();
            int index = buffer.readVarInt();
            String path = buffer.readUtf();
            be.applySelection(kind, preset, index, path);
        } else if (id == ACTION_RESCAN) {
            be.rescanBlueprints();
        } else if (id == ACTION_TAB) {
            be.applyActiveTab(buffer.readVarInt());
        } else if (id == ACTION_LOCAL) {
            String path = buffer.readUtf();
            int sizeX = buffer.readVarInt();
            int sizeY = buffer.readVarInt();
            int sizeZ = buffer.readVarInt();
            int blocks = buffer.readVarInt();
            String author = buffer.readUtf();
            be.applyLocal(path, sizeX, sizeY, sizeZ, blocks, author);
        } else if (id == ACTION_LOCAL_FORCED) {
            be.applyLocalForced(buffer.readUtf());
        }
    }
}
