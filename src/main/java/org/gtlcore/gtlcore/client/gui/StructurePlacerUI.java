package org.gtlcore.gtlcore.client.gui;

import org.gtlcore.gtlcore.client.gui.widget.BlueprintActionWidget;
import org.gtlcore.gtlcore.client.gui.widget.MaterialPreviewWidget;
import org.gtlcore.gtlcore.common.block.StructurePlacerBlockEntity;
import org.gtlcore.gtlcore.common.structure.local.LocalBlueprintLibrary;
import org.gtlcore.gtlcore.common.structure.transform.StructureTransform;

import com.gregtechceu.gtceu.api.gui.GuiTextures;

import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.TabButton;
import com.lowdragmc.lowdraglib.gui.widget.TabContainer;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 结构放置器界面：蓝图 / 材料 / 设置 / 导出四个标签页。
 * <p>
 * 布局按实机可读性重排：加宽窗口、设置页双栏（无需滚动）、蓝图与材料页均带材料图标预览。
 * 文案全部在客户端按方块实体状态即时渲染，操作按钮通过 LDLib 客户端 action 交给服务端执行，
 * 界面状态由方块实体更新包同步。
 * </p>
 */
public final class StructurePlacerUI {

    private static final int WIDTH = 344;
    private static final int HEIGHT = 258;

    private static final int CONTENT_X = 8;
    private static final int CONTENT_Y = 30;
    private static final int CONTENT_W = 328;
    private static final int CONTENT_H = 142;

    private static final int TAB_W = 80;
    private static final int TAB_H = 18;
    private static final int TAB_STEP = 82;

    private static final int BOTTOM_Y = 176;
    private static final int DEPLOY_W = 104;
    private static final int DEPLOY_H = 74;
    private static final int INV_X = 120;

    private StructurePlacerUI() {}

    public static ModularUI create(StructurePlacerBlockEntity be, Player player) {
        ModularUI ui = new ModularUI(WIDTH, HEIGHT, be, player).background(GuiTextures.BACKGROUND);

        // 蓝图选择的动作中转控件：两端都有、下标固定，客户端覆盖页通过它把选择结果交给服务端
        BlueprintActionWidget action = new BlueprintActionWidget(be);
        // 槽位访问控制：shift 点击只进当前标签页的格子（下标需要同步给服务端）
        StructurePlacerWorkspace workspace = new StructurePlacerWorkspace();
        // 蓝图选择覆盖页只在客户端创建：里面的 SceneWidget 需要客户端渲染器。
        // 覆盖页最后加入且整体是客户端控件，不会影响前面控件的 action 下标。
        BlueprintBrowserPanel browser = player.level().isClientSide ?
                new BlueprintBrowserPanel(be, action, WIDTH, HEIGHT) : null;

        WidgetGroup tabs = buildTabs(be, action, browser, workspace);
        WidgetGroup deploy = buildDeployPanel(be);
        WidgetGroup inventory = buildPlayerInventory(player);
        ui.widget(tabs);
        ui.widget(deploy);
        ui.widget(inventory);
        if (browser != null) {
            // 覆盖页打开时隐藏这些底层控件，避免它们的悬停提示画到覆盖页上
            browser.setUnderlying(tabs, deploy, inventory);
            // 最后添加：绘制在最上层
            ui.widget(browser);
        }
        workspace.setActiveTab(StructurePlacerWorkspace.TAB_BLUEPRINT);
        be.attachWorkspace(workspace);
        return ui;
    }

    // -------------------------------------------------------------------------
    // 标签容器
    // -------------------------------------------------------------------------

    private static TabContainer buildTabs(StructurePlacerBlockEntity be, BlueprintActionWidget action,
                                          @Nullable BlueprintBrowserPanel browser,
                                          StructurePlacerWorkspace workspace) {
        TabContainer tabs = new TabContainer(CONTENT_X, CONTENT_Y, CONTENT_W, CONTENT_H);
        // 不使用带伪标签缺口的 GT 背景，标签按钮自己绘制在容器上方
        tabs.setBackground(IGuiTexture.EMPTY);

        List<WidgetGroup> pages = new java.util.ArrayList<>(4);
        WidgetGroup blueprint = buildBlueprintTab(be, action, browser);
        WidgetGroup material = buildMaterialTab(be, workspace);
        WidgetGroup settings = buildSettingsTab(be);
        WidgetGroup export = buildExportTab(be, workspace);
        pages.add(blueprint);
        pages.add(material);
        pages.add(settings);
        pages.add(export);

        tabs.addTab(tabButton(0, "ui.quantum_chromodynamic_charge.structure.tab.blueprint"), blueprint);
        tabs.addTab(tabButton(TAB_STEP, "ui.quantum_chromodynamic_charge.structure.tab.material"), material);
        tabs.addTab(tabButton(TAB_STEP * 2, "ui.quantum_chromodynamic_charge.structure.tab.settings"), settings);
        tabs.addTab(tabButton(TAB_STEP * 3, "ui.quantum_chromodynamic_charge.structure.tab.export"), export);

        // 切换页时只放开该页格子的放入权限，并把下标同步给服务端（shift 点击在服务端判定）
        tabs.setOnChanged((oldPage, newPage) -> {
            int index = pages.indexOf(newPage);
            if (index < 0) index = pages.indexOf(oldPage);
            if (index < 0) return;
            workspace.setActiveTab(index);
            action.setTab(index);
        });
        return tabs;
    }

    private static TabButton tabButton(int x, String langKey) {
        TabButton button = new TabButton(x, -22, TAB_W, TAB_H);
        button.setTexture(
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(langKey)),
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(langKey, 0xFFFFD54F)));
        return button;
    }

    private static WidgetGroup contentPanel() {
        WidgetGroup panel = new WidgetGroup(0, 0, CONTENT_W, CONTENT_H);
        panel.setBackground(GuiTextures.DISPLAY);
        return panel;
    }

    // -------------------------------------------------------------------------
    // 蓝图页：蓝图选择入口 + 摘要 + 材料预览（预览在蓝图选择覆盖页里）
    // -------------------------------------------------------------------------

    private static WidgetGroup buildBlueprintTab(StructurePlacerBlockEntity be, BlueprintActionWidget action,
                                                 @Nullable BlueprintBrowserPanel browser) {
        WidgetGroup panel = contentPanel();

        // 共享空控件放最前：两端下标一致，蓝图覆盖页里的动态行只通过它发动作
        panel.addWidget(action);

        panel.addWidget(staticLabel(6, 5, "ui.quantum_chromodynamic_charge.structure.blueprint"));
        panel.addWidget(pickButton(be, browser));
        panel.addWidget(sectionTitleWidget(6, 47, "ui.quantum_chromodynamic_charge.structure.summary"));

        panel.addWidget(dynamicLabel(6, 58, 168, be::blueprintCurrentLine));
        panel.addWidget(dynamicLabel(6, 78, 168, be::blueprintSourceLine));
        panel.addWidget(dynamicLabel(6, 98, 168, be::blueprintSizeLine));
        panel.addWidget(dynamicLabel(6, 118, 168, be::blueprintCostLine));
        panel.addWidget(fixedLabel(6, 130, 168,
                "ui.quantum_chromodynamic_charge.structure.bounds_hint"));

        panel.addWidget(sectionTitleWidget(182, 47,
                "ui.quantum_chromodynamic_charge.structure.material.preview"));
        panel.addWidget(new MaterialPreviewWidget(182, 60, 140, 80, be::materialPreviewEntries));

        return panel;
    }

    /** 蓝图选择入口：显示当前蓝图，点击展开蓝图选择覆盖页（纯客户端）。 */
    private static ButtonWidget pickButton(StructurePlacerBlockEntity be, @Nullable BlueprintBrowserPanel browser) {
        ButtonWidget button = new ButtonWidget(6, 20, CONTENT_W - 12, 20,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(
                        () -> shorten(be.blueprintCurrentLine().getString() + "  ▸", CONTENT_W - 26))),
                click -> {
                    if (click.isRemote && browser != null) browser.show();
                });
        button.setClientSideWidget();
        button.setHoverTooltips(Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.browser.hint",
                LocalBlueprintLibrary.FOLDER));
        return button;
    }

    // -------------------------------------------------------------------------
    // 材料页：图标预览 + 明细 + 装卸 + 槽位
    // -------------------------------------------------------------------------

    private static WidgetGroup buildMaterialTab(StructurePlacerBlockEntity be, StructurePlacerWorkspace workspace) {
        WidgetGroup panel = contentPanel();

        panel.addWidget(staticLabel(6, 3, "ui.quantum_chromodynamic_charge.structure.material.summary"));
        panel.addWidget(new MaterialPreviewWidget(6, 20, 160, 60, be::materialPreviewEntries));

        panel.addWidget(dynamicLabel(172, 20, 152, () -> lineFor(be,
                org.gtlcore.gtlcore.common.structure.material.StructureMaterialType.FRAME)));
        panel.addWidget(dynamicLabel(172, 34, 152, () -> lineFor(be,
                org.gtlcore.gtlcore.common.structure.material.StructureMaterialType.PLATE)));
        panel.addWidget(dynamicLabel(172, 48, 152, () -> lineFor(be,
                org.gtlcore.gtlcore.common.structure.material.StructureMaterialType.FINISH)));
        panel.addWidget(dynamicLabel(172, 64, 152, () -> be.isMaterialsAdequate() ? Component.translatable("ui.quantum_chromodynamic_charge.structure.material.adequate") : Component.translatable("ui.quantum_chromodynamic_charge.structure.material.insufficient")));

        panel.addWidget(button(240, 80, 80, 18,
                "ui.quantum_chromodynamic_charge.structure.material.load",
                be::loadingMaterial));
        panel.addWidget(button(240, 100, 80, 18,
                "ui.quantum_chromodynamic_charge.structure.material.unload",
                be::unloadingMaterial));

        panel.addWidget(slotGrid(be, 6, 80, StructurePlacerBlockEntity.MATERIAL_COLS,
                StructurePlacerBlockEntity.MATERIAL_SLOTS, workspace));
        return panel;
    }

    private static Component lineFor(StructurePlacerBlockEntity be,
                                     org.gtlcore.gtlcore.common.structure.material.StructureMaterialType type) {
        Component line = Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.material.line",
                type.displayName(), be.bankValue(type), be.costValue(type));
        return be.costValue(type) > 0 || be.bankValue(type) > 0 ? line : Component.empty();
    }

    private static WidgetGroup slotGrid(StructurePlacerBlockEntity be, int x, int y, int cols, int size,
                                        StructurePlacerWorkspace workspace) {
        WidgetGroup grid = new WidgetGroup(x, y, cols * 18, (size / cols) * 18);
        for (int i = 0; i < size; i++) {
            int col = i % cols;
            int row = i / cols;
            SlotWidget slot = new SlotWidget(be.getMaterialInventory(), i, col * 18, row * 18, true, true)
                    .setBackgroundTexture(GuiTextures.SLOT);
            workspace.addMaterialSlot(slot);
            grid.addWidget(slot);
        }
        return grid;
    }

    // -------------------------------------------------------------------------
    // 设置页：双栏，无需滚动
    // -------------------------------------------------------------------------

    private static WidgetGroup buildSettingsTab(StructurePlacerBlockEntity be) {
        WidgetGroup panel = contentPanel();

        // 左栏：偏移 / 放置边界 / 变换（镜像）
        panel.addWidget(sectionTitleWidget(6, 3,
                "ui.quantum_chromodynamic_charge.structure.offset_section"));
        panel.addWidget(offsetRow(be, "X", 0, 15));
        panel.addWidget(offsetRow(be, "Y", 1, 33));
        panel.addWidget(offsetRow(be, "Z", 2, 51));

        panel.addWidget(sectionTitleWidget(6, 71,
                "ui.quantum_chromodynamic_charge.structure.boundary"));
        panel.addWidget(dynamicLabel(6, 84, 164, () -> {
            if (be.presetCount() <= 0) {
                return Component.translatable("ui.quantum_chromodynamic_charge.structure.no_presets");
            }
            StructureTransform.BlockPosBounds b = be.placementBounds();
            return Component.translatable(
                    "ui.quantum_chromodynamic_charge.structure.bounds_compact",
                    b.min().getX(), b.min().getY(), b.min().getZ(),
                    b.max().getX(), b.max().getY(), b.max().getZ());
        }));

        panel.addWidget(sectionTitleWidget(6, 104,
                "ui.quantum_chromodynamic_charge.structure.transform_section"));
        panel.addWidget(toggleCell(6, 116, 80,
                "ui.quantum_chromodynamic_charge.structure.x_mirror", be::isXMirror, be::setXMirror));
        panel.addWidget(toggleCell(90, 116, 80,
                "ui.quantum_chromodynamic_charge.structure.z_mirror", be::isZMirror, be::setZMirror));

        // 右栏：放置选项 / 旋转与速度
        panel.addWidget(sectionTitleWidget(180, 3,
                "ui.quantum_chromodynamic_charge.structure.place_options_section"));
        panel.addWidget(toggleCell(180, 15, 72,
                "ui.quantum_chromodynamic_charge.structure.skip_air", be::isSkipAir, be::setSkipAir));
        panel.addWidget(toggleCell(254, 15, 72,
                "ui.quantum_chromodynamic_charge.structure.skip_occupied", be::isSkipOccupied,
                be::setSkipOccupied));
        panel.addWidget(toggleCell(180, 33, 72,
                "ui.quantum_chromodynamic_charge.structure.update_light", be::isUpdateLight,
                be::setUpdateLight));
        panel.addWidget(toggleCell(254, 33, 72,
                "ui.quantum_chromodynamic_charge.structure.update_heightmap", be::isUpdateHeightmap,
                be::setUpdateHeightmap));

        panel.addWidget(sectionTitleWidget(180, 55,
                "ui.quantum_chromodynamic_charge.structure.rotation_speed"));
        panel.addWidget(staticLabel(180, 71, "ui.quantum_chromodynamic_charge.structure.rotation"));
        panel.addWidget(dynamicLabel(222, 71, 26, () -> Component.literal(be.getRotation() + "°")));
        panel.addWidget(button(250, 67, 64, 18, "§e+90°", be::cycleRotation));

        panel.addWidget(staticLabel(180, 93, "ui.quantum_chromodynamic_charge.structure.speed"));
        panel.addWidget(dynamicLabel(222, 93, 24, () -> Component.literal(String.valueOf(be.getSpeed()))));
        panel.addWidget(button(248, 89, 18, 18, "-", () -> be.nudgeSpeed(-1)));
        panel.addWidget(button(268, 89, 18, 18, "+", () -> be.nudgeSpeed(1)));

        return panel;
    }

    private static WidgetGroup offsetRow(StructurePlacerBlockEntity be, String axis, int axisId, int y) {
        WidgetGroup row = new WidgetGroup(0, y, CONTENT_W, 18);
        row.addWidget(staticLabel(6, 5, Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.offset", axis)));
        row.addWidget(dynamicLabel(34, 5, 26, () -> Component.literal(String.valueOf(offsetValue(be, axisId)))));
        int[] deltas = { -5, -1, 1, 5 };
        for (int i = 0; i < deltas.length; i++) {
            int delta = deltas[i];
            String text = (delta > 0 ? "+" : "") + delta;
            row.addWidget(button(60 + i * 26, 0, 24, 18, text, () -> be.nudgeOffset(axisId, delta)));
        }
        return row;
    }

    private static int offsetValue(StructurePlacerBlockEntity be, int axis) {
        return switch (axis) {
            case 0 -> be.getOffsetX();
            case 1 -> be.getOffsetY();
            case 2 -> be.getOffsetZ();
            default -> 0;
        };
    }

    private static WidgetGroup toggleCell(int x, int y, int width, String langKey,
                                          BooleanSupplier state, Consumer<Boolean> setter) {
        WidgetGroup cell = new WidgetGroup(x, y, width, 18);
        cell.addWidget(fixedLabel(0, 5, width - 26, langKey));
        TextTexture text = new TextTexture(() -> state.getAsBoolean() ? "§aON" : "§cOFF");
        cell.addWidget(new ButtonWidget(width - 26, 0, 24, 18,
                new GuiTextureGroup(GuiTextures.BUTTON, text),
                click -> {
                    if (!click.isRemote) {
                        setter.accept(!state.getAsBoolean());
                    }
                }));
        return cell;
    }

    // -------------------------------------------------------------------------
    // 导出页
    // -------------------------------------------------------------------------

    private static WidgetGroup buildExportTab(StructurePlacerBlockEntity be, StructurePlacerWorkspace workspace) {
        WidgetGroup panel = contentPanel();

        panel.addWidget(sectionTitleWidget(6, 3,
                "ui.quantum_chromodynamic_charge.structure.export_section"));
        panel.addWidget(dynamicLabel(6, 16, 316, () -> Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.export_hint")));

        panel.addWidget(staticLabel(6, 40,
                "ui.quantum_chromodynamic_charge.structure.export_slots"));
        for (int i = 0; i < StructurePlacerBlockEntity.COORD_SLOTS; i++) {
            SlotWidget slot = new SlotWidget(be.getCoordinateInventory(), i, 40 + i * 22, 36, true, true)
                    .setBackgroundTexture(GuiTextures.SLOT);
            // 导出页只接受坐标定位卡（坐标背包自身也有过滤器，这里再限制为仅该页可放入）
            workspace.addCardSlot(slot);
            panel.addWidget(slot);
        }
        panel.addWidget(dynamicLabel(86, 38, 234, () -> be.exportCardText(0)));
        panel.addWidget(dynamicLabel(86, 52, 234, () -> be.exportCardText(1)));

        panel.addWidget(dynamicLabel(6, 72, 316, be::exportRegionText));
        panel.addWidget(dynamicLabel(6, 88, 316, be::exportStatusText));
        panel.addWidget(fixedLabel(6, 104, 316,
                "ui.quantum_chromodynamic_charge.structure.export_dir"));

        panel.addWidget(button(6, 118, 110, 18,
                "ui.quantum_chromodynamic_charge.structure.export",
                be::exportRegion));
        return panel;
    }

    // -------------------------------------------------------------------------
    // 底栏：状态 + 放置
    // -------------------------------------------------------------------------

    private static WidgetGroup buildDeployPanel(StructurePlacerBlockEntity be) {
        WidgetGroup panel = new WidgetGroup(CONTENT_X, BOTTOM_Y, DEPLOY_W, DEPLOY_H);
        panel.setBackground(GuiTextures.BACKGROUND_INVERSE);

        panel.addWidget(dynamicLabel(6, 6, DEPLOY_W - 12, be::statusText));
        panel.addWidget(dynamicLabel(6, 24, DEPLOY_W - 12, be::blueprintCurrentLine));
        panel.addWidget(button(8, DEPLOY_H - 26, DEPLOY_W - 16, 18,
                "ui.quantum_chromodynamic_charge.structure.place",
                be::startPlacement));
        return panel;
    }

    private static WidgetGroup buildPlayerInventory(Player player) {
        Inventory inventory = player.getInventory();
        WidgetGroup group = new WidgetGroup(INV_X, BOTTOM_Y, 162, 74);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = 9 + row * 9 + col;
                group.addWidget(new SlotWidget(inventory, index, col * 18, row * 18, true, true)
                        .setLocationInfo(true, false)
                        .setBackgroundTexture(GuiTextures.SLOT));
            }
        }
        for (int col = 0; col < 9; col++) {
            group.addWidget(new SlotWidget(inventory, col, col * 18, 56, true, true)
                    .setLocationInfo(true, true)
                    .setBackgroundTexture(GuiTextures.SLOT));
        }
        return group;
    }

    // -------------------------------------------------------------------------
    // 通用控件
    // -------------------------------------------------------------------------

    private static LabelWidget staticLabel(int x, int y, String langKey) {
        LabelWidget label = new LabelWidget(x, y, Component.translatable(langKey));
        label.setClientSideWidget();
        return label;
    }

    private static LabelWidget staticLabel(int x, int y, Component component) {
        LabelWidget label = new LabelWidget(x, y, component);
        label.setClientSideWidget();
        return label;
    }

    private static LabelWidget fixedLabel(int x, int y, int width, String langKey) {
        LabelWidget label = new LabelWidget(x, y, () -> shorten(Component.translatable(langKey).getString(), width));
        label.setClientSideWidget();
        return label;
    }

    private static LabelWidget dynamicLabel(int x, int y, int width, Supplier<Component> supplier) {
        LabelWidget label = new LabelWidget(x, y, () -> wrap(supplier.get(), width));
        label.setClientSideWidget();
        return label;
    }

    private static LabelWidget dynamicLabel(int x, int y, Supplier<Component> supplier) {
        LabelWidget label = new LabelWidget(x, y, () -> supplier.get().getString());
        label.setClientSideWidget();
        return label;
    }

    private static LabelWidget sectionTitleWidget(int x, int y, String langKey) {
        LabelWidget label = new LabelWidget(x, y, Component.translatable(langKey)
                .withStyle(net.minecraft.ChatFormatting.YELLOW));
        label.setClientSideWidget();
        return label;
    }

    private static ButtonWidget button(int x, int y, int width, int height, String text, Runnable serverAction) {
        return new ButtonWidget(x, y, width, height,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(text)),
                click -> {
                    if (!click.isRemote) {
                        serverAction.run();
                    }
                });
    }

    /** 客户端宽度换行：中文按实际像素宽度切分。 */
    private static String wrap(Component component, int maxWidth) {
        String text = component.getString();
        if (text.isEmpty()) return "";
        var font = Minecraft.getInstance().font;
        StringBuilder out = new StringBuilder(text.length() + 8);
        int lineWidth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                out.append('\n');
                lineWidth = 0;
                continue;
            }
            int charWidth = font.width(String.valueOf(c));
            if (lineWidth > 0 && lineWidth + charWidth > maxWidth) {
                out.append('\n');
                lineWidth = 0;
            }
            out.append(c);
            lineWidth += charWidth;
        }
        return out.toString();
    }

    /** 单行标签按像素宽度截断，超长以省略号结尾。 */
    private static String shorten(String text, int maxWidth) {
        var font = Minecraft.getInstance().font;
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "…";
        int budget = maxWidth - font.width(ellipsis);
        StringBuilder out = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int charWidth = font.width(String.valueOf(c));
            if (width + charWidth > budget) break;
            out.append(c);
            width += charWidth;
        }
        return out + ellipsis;
    }
}
