package org.gtlcore.gtlcore.client.gui;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.block.StructurePlacerBlockEntity;
import org.gtlcore.gtlcore.common.structure.model.StructureDefinition;
import org.gtlcore.gtlcore.common.structure.place.StructurePlacer;
import org.gtlcore.gtlcore.common.structure.transform.StructureLocalPos;
import org.gtlcore.gtlcore.common.structure.transform.StructureTransform;

import com.gregtechceu.gtceu.api.gui.GuiTextures;

import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SceneWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import com.lowdragmc.lowdraglib.utils.TrackedDummyWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 结构预览浮层：用 LDLib 的 {@link SceneWidget} + {@link TrackedDummyWorld} 搭一个可拖动/缩放的三维预览，
 * 与 JEI 多方块结构预览用的是同一套渲染（GTCEu 的 {@code PatternPreviewWidget} 也是这么做的）。
 * <p>
 * 本类在客户端与服务端都会创建（保证 LDLib 用“控件下标”定位 action 的链路两端一致），
 * 但只有客户端才会真正构建场景：服务端的 {@link SceneWidget} 不会创建 renderer，方法内部也都有 isRemote 判断。
 * </p>
 */
public final class StructurePreviewPanel extends WidgetGroup {

    private final StructurePlacerBlockEntity be;
    private final SceneWidget scene;
    @Nullable
    private TrackedDummyWorld dummyWorld;
    private long lastKey = Long.MIN_VALUE;

    public StructurePreviewPanel(StructurePlacerBlockEntity be, int width, int height) {
        super(0, 0, width, height);
        this.be = be;
        setBackground(GuiTextures.BACKGROUND);
        setVisible(false);
        // 整个浮层都是客户端控件：内部控件不会向服务端发 action 包
        setClientSideWidget();

        this.scene = new SceneWidget(6, 30, width - 12, height - 68, be.getLevel());
        // 客户端控件：SceneWidget 点击会 writeClientAction，标记后不会往服务端发包
        scene.setClientSideWidget();
        scene.setDraggable(true).setScalable(true).setRenderFacing(false).setHoverTips(false);
        // 与 GTCEu 的 PatternPreviewWidget 一样缓存几何，大结构不会每帧重建
        scene.useCacheBuffer(true);
        addWidget(scene);

        LabelWidget title = new LabelWidget(8, 6, Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.preview.title")
                .withStyle(net.minecraft.ChatFormatting.YELLOW));
        title.setClientSideWidget();
        addWidget(title);

        LabelWidget info = new LabelWidget(8, 18, this::describeCurrent);
        info.setClientSideWidget();
        addWidget(info);

        LabelWidget hint = new LabelWidget(8, height - 32, Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.preview.hint")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        hint.setClientSideWidget();
        addWidget(hint);

        ButtonWidget close = new ButtonWidget(width - 88, height - 36, 80, 16,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(
                        "ui.quantum_chromodynamic_charge.structure.preview.close")),
                click -> {
                    if (click.isRemote) setVisible(false);
                });
        close.setClientSideWidget();
        addWidget(close);
    }

    /** 打开预览（仅客户端调用）。 */
    public void show() {
        refresh();
        setVisible(true);
    }

    private String describeCurrent() {
        StructureDefinition.Structure structure = be.currentStructure();
        if (structure == null) {
            return Component.translatable("ui.quantum_chromodynamic_charge.structure.preview.empty").getString();
        }
        int rotation = StructureTransform.normalizeRotation(be.getRotation());
        boolean swapped = rotation == 90 || rotation == 270;
        int sizeX = swapped ? structure.sizeZ() : structure.sizeX();
        int sizeZ = swapped ? structure.sizeX() : structure.sizeZ();
        StringBuilder text = new StringBuilder(structure.title().getString())
                .append("  ").append(sizeX).append('×').append(structure.sizeY()).append('×').append(sizeZ);
        if (rotation != 0) text.append("  ").append(rotation).append('°');
        if (be.isXMirror()) text.append("  X-");
        if (be.isZMirror()) text.append("  Z-");
        String line = text.toString();
        // 单行标签不会自动换行，太长会顶出窗口
        return line.length() > 56 ? line.substring(0, 55) + "…" : line;
    }

    /** 按当前预设/结构/旋转/镜像重建预览场景（仅客户端）。 */
    private void refresh() {
        StructureDefinition.Structure structure = be.currentStructure();
        if (structure == null) return;

        int rotation = be.getRotation();
        boolean mirrorX = be.isXMirror();
        boolean mirrorZ = be.isZMirror();
        long key = ((long) be.getPresetIndex() << 40) ^ ((long) be.getStructureIndex() << 20) ^ rotation ^ (mirrorX ? 1L : 0L) ^ (mirrorZ ? 2L : 0L);
        if (key == lastKey) return;

        StructurePlacer.LoadedStructure loaded;
        try {
            loaded = StructurePlacer.load(structure);
        } catch (Exception e) {
            GTLCore.LOGGER.warn("Structure preview load failed: {} / {}", structure.name(), structure.resource(), e);
            return;
        }

        int sizeX = loaded.data().sizeX();
        int sizeZ = loaded.data().sizeZ();
        StructureLocalPos offset = StructureTransform.calcOriginOffset(
                sizeX, loaded.data().sizeY(), sizeZ, rotation, mirrorX, mirrorZ);
        Map<BlockPos, BlockInfo> blocks = new HashMap<>();
        String[][] pattern = loaded.data().pattern();
        var mapping = loaded.mapping();
        for (int z = 0; z < pattern.length; z++) {
            String[] aisle = pattern[z];
            for (int y = 0; y < aisle.length; y++) {
                String row = aisle[y];
                for (int x = 0; x < row.length(); x++) {
                    BlockState state = mapping.get(row.charAt(x));
                    if (state == null || state.isAir()) continue;
                    StructureLocalPos transformed = StructureTransform.transformCoords(
                            x, y, z, sizeX, sizeZ, rotation, mirrorX, mirrorZ);
                    BlockPos pos = new BlockPos(
                            transformed.x() + offset.x(),
                            transformed.y() + offset.y(),
                            transformed.z() + offset.z());
                    blocks.put(pos, BlockInfo.fromBlockState(
                            StructureTransform.transformStateForPlace(state, rotation, mirrorX, mirrorZ)));
                }
            }
        }
        if (blocks.isEmpty()) return;

        if (dummyWorld == null) {
            dummyWorld = new TrackedDummyWorld();
        } else {
            dummyWorld.clear();
        }
        dummyWorld.addBlocks(blocks);
        scene.createScene(dummyWorld);
        scene.setRenderedCore(blocks.keySet());
        lastKey = key;
    }

    // ---------------------------------------------------------------
    // 浮层显示时吞掉鼠标事件，避免点到下面的标签页控件
    // ---------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        super.mouseReleased(mouseX, mouseY, button);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        return true;
    }

    @Override
    public boolean mouseWheelMove(double mouseX, double mouseY, double wheelDelta) {
        super.mouseWheelMove(mouseX, mouseY, wheelDelta);
        return true;
    }
}
