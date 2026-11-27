package org.gtlcore.gtlcore.client.gui;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.client.gui.widget.BlueprintActionWidget;
import org.gtlcore.gtlcore.client.preview.PreviewButtonTexture;
import org.gtlcore.gtlcore.common.block.StructurePlacerBlockEntity;
import org.gtlcore.gtlcore.common.structure.local.BlueprintBundle;
import org.gtlcore.gtlcore.common.structure.local.BlueprintIO;
import org.gtlcore.gtlcore.common.structure.local.LocalBlueprintLibrary;
import org.gtlcore.gtlcore.common.structure.material.StructureMaterialType;
import org.gtlcore.gtlcore.common.structure.model.StructureDefinition;
import org.gtlcore.gtlcore.common.structure.place.StructurePlacer;
import org.gtlcore.gtlcore.common.structure.registry.StructureTemplateRegistry;
import org.gtlcore.gtlcore.common.structure.transform.StructureLocalPos;
import org.gtlcore.gtlcore.common.structure.transform.StructureTransform;

import com.gregtechceu.gtceu.api.gui.GuiTextures;

import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SceneWidget;
import com.lowdragmc.lowdraglib.gui.widget.SelectableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import com.lowdragmc.lowdraglib.utils.Size;
import com.lowdragmc.lowdraglib.utils.TrackedDummyWorld;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

import it.unimi.dsi.fastutil.chars.Char2ReferenceOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 蓝图选择覆盖页：左侧浏览蓝图目录（支持任意层子文件夹）与内置预设，右侧实时三维预览。
 * <p>
 * 目录内容完全在客户端渲染，服务端不构建这棵控件树；选择结果通过 {@link BlueprintActionWidget}
 * （两端下标一致的空控件）转发给服务端生效。文件夹里只有 {@code .gtcpf} 单文件蓝图会被列出，
 * 子文件夹逐级展开，点文件即选用并预览。
 * </p>
 */
public final class BlueprintBrowserPanel extends WidgetGroup {

    /** 内置预设浏览模式的目录前缀。 */
    private static final String BUILTIN = "#builtin";
    private static final String ROW_UP = "up";

    private static final int ROW_HEIGHT = 14;
    private static final int ROW_TOP = 4;
    private static final int LIST_W = 172;

    private final StructurePlacerBlockEntity be;
    private final BlueprintActionWidget action;
    private final SceneWidget scene;
    private final DraggableScrollableWidgetGroup list;
    private final LabelWidget pathLabel;
    private final LabelWidget titleLabel;
    private final ButtonWidget fullscreenButton;
    private final ButtonWidget closeButton;
    private final List<ButtonWidget> bottomButtons = new ArrayList<>();
    /** 风险确认弹窗。 */
    private final WidgetGroup confirmGroup;
    private final LabelWidget confirmLabel;
    private final ButtonWidget confirmYes;
    private final ButtonWidget confirmNo;
    private Runnable pendingConfirm;
    /** 覆盖页打开时隐藏这些底层控件，避免它们的悬停提示穿透到覆盖页上。 */
    private Widget[] underlying = new Widget[0];
    @Nullable
    private TrackedDummyWorld dummyWorld;

    /** 当前目录：{@code ""} = 蓝图根目录；{@code "#builtin"} / {@code "#builtin/<预设>"} = 内置预设。 */
    private String dir = "";
    private String highlighted = "";
    private int renderedRevision = Integer.MIN_VALUE;
    /** 三维预览是否全屏。 */
    private boolean fullscreen;
    /** 非全屏时的覆盖页尺寸（主界面大小）；全屏时改用屏幕实际缩放尺寸。 */
    private final int baseWidth;
    private final int baseHeight;

    public BlueprintBrowserPanel(StructurePlacerBlockEntity be, BlueprintActionWidget action,
                                 int width, int height) {
        super(0, 0, width, height);
        this.be = be;
        this.action = action;
        this.baseWidth = width;
        this.baseHeight = height;
        setBackground(GuiTextures.BACKGROUND);
        // 构造期间不能走 setVisible 重写（字段尚未初始化），直接隐藏
        super.setVisible(false);
        // 整个覆盖页都是客户端控件：内部控件不会向服务端发 action 包
        setClientSideWidget();

        titleLabel = new LabelWidget(8, 5, Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.browser.title").withStyle(ChatFormatting.YELLOW));
        titleLabel.setClientSideWidget();
        addWidget(titleLabel);

        pathLabel = new LabelWidget(8, 18, "");
        pathLabel.setClientSideWidget();
        addWidget(pathLabel);

        list = new DraggableScrollableWidgetGroup(6, 32, LIST_W, height - 74);
        list.setBackground(GuiTextures.DISPLAY);
        list.setClientSideWidget();
        addWidget(list);

        scene = new SceneWidget(LIST_W + 14, 32, width - LIST_W - 20, height - 74, be.getLevel());
        scene.setClientSideWidget();
        scene.setDraggable(true).setScalable(true).setRenderFacing(false).setHoverTips(false);
        scene.useCacheBuffer(true);
        addWidget(scene);

        addBottom(width, height, "ui.quantum_chromodynamic_charge.structure.browser.open_folder",
                6, 46, LocalBlueprintLibrary::openFolder);
        addBottom(width, height, "ui.quantum_chromodynamic_charge.structure.browser.rescan",
                56, 46, action::rescan);
        addBottom(width, height, "ui.quantum_chromodynamic_charge.structure.browser.preview",
                106, 64, this::previewHighlighted);
        addBottom(width, height, "ui.quantum_chromodynamic_charge.structure.browser.apply",
                174, 64, this::applyHighlighted);
        closeButton = clientButton(width - 54, height - 34, 48, 16,
                "ui.quantum_chromodynamic_charge.structure.preview.close",
                this::close);
        addWidget(closeButton);

        // 与 JEI 多方块预览一致的「展开」图标：点击后三维预览铺满整个界面
        fullscreenButton = new ButtonWidget(0, 0, 18, 18, PreviewButtonTexture.EXPAND,
                click -> {
                    if (click.isRemote) toggleFullscreen();
                });
        fullscreenButton.setClientSideWidget();
        fullscreenButton.setHoverTooltips(Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.browser.fullscreen"));
        addWidget(fullscreenButton);

        // 风险确认弹窗（最后添加，绘制在最上层；尺寸在 askConfirm 里按文字自适应）
        confirmGroup = new WidgetGroup(0, 0, 220, 60);
        confirmGroup.setBackground(new GuiTextureGroup(
                new ColorRectTexture(0xE0101010), new ColorBorderTexture(1, 0xFFA0A8B8)));
        confirmGroup.setClientSideWidget();
        confirmLabel = new LabelWidget(10, 9, "");
        confirmLabel.setClientSideWidget();
        confirmGroup.addWidget(confirmLabel);
        confirmYes = clientButton(0, 0, 70, 16,
                "ui.quantum_chromodynamic_charge.structure.browser.risky.confirm",
                () -> {
                    confirmGroup.setVisible(false);
                    Runnable pending = pendingConfirm;
                    pendingConfirm = null;
                    if (pending != null) pending.run();
                });
        confirmGroup.addWidget(confirmYes);
        confirmNo = clientButton(0, 0, 70, 16,
                "ui.quantum_chromodynamic_charge.structure.browser.risky.cancel",
                () -> {
                    confirmGroup.setVisible(false);
                    pendingConfirm = null;
                });
        confirmGroup.addWidget(confirmNo);
        confirmGroup.setVisible(false);
        addWidget(confirmGroup);

        applyLayout();
    }

    /** 弹出「可能崩溃」确认框；内容按文字宽度自适应，保证在不同 GUI 缩放/分辨率下不出框。 */
    private void askConfirm(String path, long bytes, Runnable action) {
        pendingConfirm = action;
        String text = Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.browser.risky.text",
                path, Math.max(1L, bytes / 1024L / 1024L)).getString();
        int maxWidth = Math.max(120, getSize().width - 24);
        List<String> lines = wrapLines(text, maxWidth - 20);
        int textWidth = 0;
        for (String line : lines) textWidth = Math.max(textWidth, font().width(line));

        int boxWidth = Math.min(maxWidth, Math.max(150, textWidth + 20));
        int boxHeight = 12 + lines.size() * 10 + 26;
        confirmGroup.setSize(new Size(boxWidth, boxHeight));
        confirmGroup.setSelfPosition((getSize().width - boxWidth) / 2, (getSize().height - boxHeight) / 2);
        confirmLabel.setText(String.join("\n", lines));
        int buttonY = boxHeight - 4 - 16;
        confirmYes.setSelfPosition(boxWidth / 2 - 74, buttonY);
        confirmNo.setSelfPosition(boxWidth / 2 + 4, buttonY);
        confirmGroup.setVisible(true);
    }

    /** 按像素宽度折行（LabelWidget 支持 \n）。 */
    private static List<String> wrapLines(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int charWidth = font().width(String.valueOf(c));
            if (width > 0 && width + charWidth > maxWidth) {
                lines.add(line.toString());
                line.setLength(0);
                width = 0;
            }
            line.append(c);
            width += charWidth;
        }
        if (line.length() > 0) lines.add(line.toString());
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    private static net.minecraft.client.gui.Font font() {
        return Minecraft.getInstance().font;
    }

    private void addBottom(int width, int height, String langKey, int x, int buttonWidth, Runnable action) {
        ButtonWidget button = clientButton(x, height - 34, buttonWidth, 16, langKey, action);
        bottomButtons.add(button);
        addWidget(button);
    }

    /** 隐藏/恢复底层控件（覆盖页显示时不要让下面的悬停提示透出来）。 */
    public void setUnderlying(Widget... widgets) {
        this.underlying = widgets;
    }

    @Override
    public WidgetGroup setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible) {
            applyLayout();
        } else {
            // 关闭时恢复普通布局，下次打开从列表视图开始
            fullscreen = false;
            applyLayout();
        }
        for (Widget widget : underlying) {
            widget.setVisible(!visible);
        }
        return this;
    }

    private void close() {
        setVisible(false);
    }

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        applyLayout();
    }

    /** 按全屏状态摆放场景与按钮。 */
    private void applyLayout() {
        boolean listVisible = !fullscreen;
        titleLabel.setVisible(listVisible);
        pathLabel.setVisible(listVisible);
        list.setVisible(listVisible);
        for (ButtonWidget button : bottomButtons) {
            button.setVisible(listVisible);
        }
        if (fullscreen) {
            // 真正的全屏：尺寸取当前屏幕缩放后的实际大小，位置抵消主界面（344x258）的居中偏移，
            // 这样换显示器/改 GUI 缩放后点全屏都能铺满整个界面，而不是固定 344x258
            Minecraft minecraft = Minecraft.getInstance();
            int screenWidth = minecraft.getWindow().getGuiScaledWidth();
            int screenHeight = minecraft.getWindow().getGuiScaledHeight();
            WidgetGroup parent = getParent();
            int offsetX = parent == null ? 0 : parent.getPositionX();
            int offsetY = parent == null ? 0 : parent.getPositionY();
            setSize(screenWidth, screenHeight);
            setSelfPosition(-offsetX, -offsetY);
            scene.setSelfPosition(0, 0);
            scene.setSize(new Size(screenWidth, screenHeight));
            fullscreenButton.setSelfPosition(screenWidth - 20, 2);
            closeButton.setSelfPosition(screenWidth - 54, screenHeight - 20);
        } else {
            setSize(baseWidth, baseHeight);
            setSelfPosition(0, 0);
            scene.setSelfPosition(LIST_W + 14, 32);
            scene.setSize(new Size(baseWidth - LIST_W - 20, baseHeight - 74));
            fullscreenButton.setSelfPosition(baseWidth - 20, 34);
            closeButton.setSelfPosition(baseWidth - 54, baseHeight - 34);
        }
    }

    /** 窗口/分辨率变化（含换显示器、改 GUI 缩放）时重排，保证全屏始终贴合当前屏幕。 */
    @Override
    public void onScreenSizeUpdate(int screenWidth, int screenHeight) {
        super.onScreenSizeUpdate(screenWidth, screenHeight);
        if (fullscreen) {
            applyLayout();
        }
    }

    /** 打开选择页（仅客户端）。 */
    public void show() {
        // 进入时把高亮定位到当前已应用的蓝图，方便直接预览/确认
        if (be.isLocalBlueprintSelected()) {
            highlighted = "f:" + be.localBlueprintPath();
        } else {
            highlighted = "s:" + be.getPresetIndex() + ":" + be.getStructureIndex();
        }
        fullscreen = false;
        // 每次从主界面打开蓝图选择都让服务端重扫一次目录，新放进来的蓝图立刻可见
        action.rescan();
        rebuild();
        previewStructure(be.currentStructure());
        setVisible(true);
    }

    /** 「预览蓝图」：只渲染高亮项，不改变已应用的蓝图。 */
    private void previewHighlighted() {
        String id = highlighted;
        if (id.startsWith("f:")) {
            String path = id.substring(2);
            Path file = LocalBlueprintLibrary.resolve(path);
            if (file == null || !Files.isRegularFile(file)) {
                previewStructure(be.currentStructure());
                toast(Component.translatable("ui.quantum_chromodynamic_charge.structure.browser.missing"));
                return;
            }
            try {
                previewBundle(BlueprintIO.load(file));
            } catch (Exception | OutOfMemoryError failure) {
                if (sizeRelated(failure)) {
                    // 只是超过安全上限：先问一句，确认后再强制读取
                    askConfirm(path, fileBytes(path), () -> previewForced(path));
                } else {
                    reportFailure(path, failure);
                }
            }
            return;
        }
        StructureDefinition.Structure structure = highlightedStructure();
        if (structure == null) return;
        previewStructure(structure);
    }

    /** 确认后强制读取超大蓝图并预览（客户端读取）。 */
    private void previewForced(String path) {
        Path file = LocalBlueprintLibrary.resolve(path);
        if (file == null) return;
        try {
            previewBundle(BlueprintIO.loadForced(file));
        } catch (Exception | OutOfMemoryError failure) {
            GTLCore.LOGGER.warn("Forced preview failed: {} / {}", path, failure.toString());
            reportFailure(path, failure);
        }
    }

    /** 「应用蓝图」：把高亮项交给服务端生效，然后关闭选择页。 */
    private void applyHighlighted() {
        String id = highlighted;
        if (id.startsWith("f:")) {
            String path = id.substring(2);
            Path file = LocalBlueprintLibrary.resolve(path);
            if (file == null || !Files.isRegularFile(file)) {
                // 客户端没有文件（专用服务器）：让服务端自己解析
                action.applyLocal(path, 0, 0, 0, 0, "");
                setVisible(false);
                return;
            }
            try {
                BlueprintBundle.Bundle bundle = BlueprintIO.load(file);
                action.applyLocal(path, bundle.sizeX(), bundle.sizeY(), bundle.sizeZ(), bundle.blocks(),
                        bundle.author());
                setVisible(false);
            } catch (Exception | OutOfMemoryError failure) {
                if (sizeRelated(failure)) {
                    askConfirm(path, fileBytes(path), () -> {
                        action.applyLocalForced(path);
                        setVisible(false);
                    });
                } else {
                    reportFailure(path, failure);
                }
            }
            return;
        }
        if (id.startsWith("s:")) {
            String[] parts = id.split(":");
            if (parts.length != 3) return;
            int preset = parseNumber(parts[1]);
            int index = parseNumber(parts[2]);
            if (preset < 0 || index < 0) return;
            action.selectBuiltin(preset, index);
        } else {
            return;
        }
        setVisible(false);
    }

    @Nullable
    private StructureDefinition.Structure highlightedStructure() {
        if (highlighted.startsWith("s:")) {
            String[] parts = highlighted.split(":");
            if (parts.length != 3) return null;
            int preset = parseNumber(parts[1]);
            int index = parseNumber(parts[2]);
            return preset < 0 || index < 0 ? null : be.structureAt(preset, index);
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // 目录与列表
    // -------------------------------------------------------------------------

    @Override
    public void updateScreen() {
        super.updateScreen();
        // 服务端扫描/选择结果变化时重建列表（新增、删除蓝图或外部改选）
        if (renderedRevision != be.blueprintRevision()) {
            rebuild();
        }
    }

    private void rebuild() {
        renderedRevision = be.blueprintRevision();
        pathLabel.setText(pathText());

        List<Row> rows = new ArrayList<>();
        if (dir.startsWith(BUILTIN)) {
            String rest = dir.substring(BUILTIN.length());
            if (rest.startsWith("/")) rest = rest.substring(1);
            rows.add(new Row(ROW_UP, "§7..", true));
            if (rest.isEmpty()) {
                for (int preset = 0; preset < StructureTemplateRegistry.presetCount(); preset++) {
                    int count = StructureTemplateRegistry.getPreset(preset).structures().size();
                    rows.add(new Row(BUILTIN + "/" + preset,
                            "▸ " + StructureTemplateRegistry.getPreset(preset).title().getString() + " (" + count + ")",
                            true));
                }
            } else {
                int preset = parseNumber(rest);
                for (int index = 0; preset >= 0 && index < be.structureCount(preset); index++) {
                    StructureDefinition.Structure structure = be.structureAt(preset, index);
                    if (structure == null) continue;
                    boolean selected = be.localBlueprintPath().isEmpty() &&
                            be.getPresetIndex() == preset && be.getStructureIndex() == index;
                    rows.add(new Row("s:" + preset + ":" + index,
                            (selected ? "§e» " : "  ") + structure.title().getString() + "  §7" +
                                    sizeText(structure) + "  " + costText(structure),
                            false));
                }
            }
        } else {
            if (!dir.isEmpty()) rows.add(new Row(ROW_UP, "§7..", true));
            if (dir.isEmpty()) {
                rows.add(new Row(BUILTIN, "▸ " + tr("ui.quantum_chromodynamic_charge.structure.browser.builtin"), true));
            }
            collectLocal(rows, dir);
        }
        boolean hasEntry = false;
        for (Row row : rows) {
            if (!row.id().equals(ROW_UP)) {
                hasEntry = true;
                break;
            }
        }
        if (!hasEntry) {
            rows.add(new Row("", tr("ui.quantum_chromodynamic_charge.structure.browser.empty"), false));
        }

        list.clearAllWidgets();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = ROW_TOP + i * ROW_HEIGHT;
            if (row.id().isEmpty()) {
                LabelWidget hint = new LabelWidget(4, y + 3, row.label());
                hint.setClientSideWidget();
                list.addWidget(hint);
                continue;
            }
            SelectableWidgetGroup widget = new SelectableWidgetGroup(0, y, LIST_W - 8, ROW_HEIGHT);
            widget.setHoverTexture(new ColorRectTexture(0x40FFFFFF));
            if (row.id().equals(highlighted)) {
                widget.setBackground(new ColorRectTexture(0x33FFD966));
            }
            LabelWidget label = new LabelWidget(3, 3, row.label());
            label.setClientSideWidget();
            widget.addWidget(label);
            widget.setOnSelected(selected -> onRowSelected(row));
            list.addWidget(widget);
        }
        // 通知滚动组重新计算内容高度，行的数量变化后仍可滚动
        list.computeMax();
    }

    private void collectLocal(List<Row> rows, String prefix) {
        String base = prefix.isEmpty() ? "" : prefix + "/";
        TreeSet<String> subDirs = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<String> files = new ArrayList<>();
        // 磁盘上的目录（含空文件夹）与蓝图路径推导出的目录取并集
        for (String dir : be.localDirectories()) {
            if (dir.equals(prefix)) continue;
            if (!base.isEmpty() && !dir.startsWith(base)) continue;
            String rest = base.isEmpty() ? dir : dir.substring(base.length());
            if (rest.isEmpty()) continue;
            int slash = rest.indexOf('/');
            subDirs.add(slash < 0 ? rest : rest.substring(0, slash));
        }
        for (var entry : be.localBlueprints()) {
            String path = entry.path();
            if (!path.startsWith(base)) continue;
            String rest = path.substring(base.length());
            int slash = rest.indexOf('/');
            if (slash < 0) files.add(path);
            else subDirs.add(rest.substring(0, slash));
        }
        for (String sub : subDirs) rows.add(new Row("d:" + base + sub, "▸ " + sub, true));
        files.sort(String.CASE_INSENSITIVE_ORDER);
        for (String file : files) {
            boolean selected = file.equals(be.localBlueprintPath());
            long bytes = fileBytes(file);
            var meta = be.localMeta().get(file);
            String info = meta != null ? sizeText(meta.sizeX(), meta.sizeY(), meta.sizeZ()) :
                    Math.max(1L, bytes / 1024L) + " KB";
            rows.add(new Row("f:" + file,
                    (selected ? "§e» " : "  ") + LocalBlueprintLibrary.displayName(file) + "  §7" + info,
                    false));
        }
    }

    private long fileBytes(String path) {
        for (var entry : be.localBlueprints()) {
            if (entry.path().equals(path)) return entry.bytes();
        }
        return 0L;
    }

    private static String sizeText(int sizeX, int sizeY, int sizeZ) {
        return sizeX + "×" + sizeY + "×" + sizeZ;
    }

    private static String sizeText(StructureDefinition.Structure structure) {
        return sizeText(structure.sizeX(), structure.sizeY(), structure.sizeZ());
    }

    /** 内置结构的材料需求（本地蓝图的需求在应用后才显示在蓝图页）。 */
    private static String costText(StructureDefinition.Structure structure) {
        int cost = structure.materialCost(StructureMaterialType.FRAME);
        return cost <= 0 ? "" : cost + " " + StructureMaterialType.FRAME.displayName().getString();
    }

    private void onRowSelected(Row row) {
        if (row.id().equals(ROW_UP)) {
            dir = dir.startsWith(BUILTIN) ?
                    (dir.equals(BUILTIN) ? "" : BUILTIN) :
                    parent(dir);
            rebuild();
            return;
        }
        if (row.id().equals(BUILTIN) || row.id().startsWith(BUILTIN + "/")) {
            dir = row.id();
            rebuild();
            return;
        }
        if (row.id().startsWith("d:")) {
            dir = row.id().substring(2);
            rebuild();
            return;
        }
        if (row.id().startsWith("f:")) {
            // 选中文件只做高亮，等「预览蓝图」/「应用蓝图」按钮执行动作
            highlighted = row.id();
            rebuild();
            return;
        }
        if (row.id().startsWith("s:") || row.id().startsWith("r:")) {
            highlighted = row.id();
            rebuild();
        }
    }

    private static int parseNumber(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String parent(String dir) {
        int slash = dir.lastIndexOf('/');
        return slash < 0 ? "" : dir.substring(0, slash);
    }

    private String pathText() {
        if (dir.startsWith(BUILTIN)) {
            String base = tr("ui.quantum_chromodynamic_charge.structure.browser.builtin");
            String rest = dir.substring(BUILTIN.length());
            if (rest.startsWith("/")) rest = rest.substring(1);
            int preset = parseNumber(rest);
            if (preset >= 0 && preset < StructureTemplateRegistry.presetCount()) {
                return base + " / " + StructureTemplateRegistry.getPreset(preset).title().getString();
            }
            return base;
        }
        return LocalBlueprintLibrary.FOLDER + (dir.isEmpty() ? "" : "/" + dir);
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    // -------------------------------------------------------------------------
    // 预览（按当前旋转/镜像换算，与放置结果一致）
    // -------------------------------------------------------------------------

    private void previewStructure(@Nullable StructureDefinition.Structure structure) {
        if (structure == null) return;
        StructurePlacer.LoadedStructure loaded;
        try {
            loaded = StructurePlacer.load(structure);
        } catch (Exception | OutOfMemoryError e) {
            GTLCore.LOGGER.warn("Blueprint preview failed: {} / {}", structure.name(), e.toString());
            toast(Component.translatable("ui.quantum_chromodynamic_charge.structure.browser.missing"));
            return;
        }
        buildScene(loaded.data().pattern(), loaded.mapping(),
                loaded.data().sizeX(), loaded.data().sizeY(), loaded.data().sizeZ());
    }

    /** 直接用一个已解析的蓝图构建预览（用于确认后的超大蓝图）。 */
    private void previewBundle(BlueprintBundle.Bundle bundle) {
        buildScene(bundle.pattern(), bundle.palette(), bundle.sizeX(), bundle.sizeY(), bundle.sizeZ());
    }

    /** 是否只是「超出安全上限」；这类才值得弹崩溃确认框。 */
    private static boolean sizeRelated(Throwable failure) {
        if (failure instanceof OutOfMemoryError) return true;
        String message = failure.getMessage();
        return message != null && message.contains("too large");
    }

    /** 解析失败：直接在聊天栏说明原因，避免玩家以为点了没反应。 */
    private void reportFailure(String path, Throwable failure) {
        GTLCore.LOGGER.warn("Blueprint load failed: {} / {}", path, failure.toString());
        String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (reason.length() > 80) reason = reason.substring(0, 80) + "…";
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(
                    "ui.quantum_chromodynamic_charge.structure.browser.load_failed", path, reason)
                    .withStyle(ChatFormatting.RED), false);
        }
    }

    private void toast(Component message) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(message.copy().withStyle(ChatFormatting.RED), true);
    }

    /** 按当前旋转/镜像把图案搭成预览场景。 */
    private void buildScene(String[][] pattern, Char2ReferenceOpenHashMap<BlockState> mapping,
                            int sizeX, int sizeY, int sizeZ) {
        int rot = be.getRotation();
        boolean mirrorX = be.isXMirror();
        boolean mirrorZ = be.isZMirror();

        StructureLocalPos offset = StructureTransform.calcOriginOffset(sizeX, sizeY, sizeZ, rot, mirrorX, mirrorZ);
        Map<BlockPos, BlockInfo> blocks = new HashMap<>();
        for (int z = 0; z < pattern.length; z++) {
            String[] aisle = pattern[z];
            for (int y = 0; y < aisle.length; y++) {
                String row = aisle[y];
                for (int x = 0; x < row.length(); x++) {
                    BlockState state = mapping.get(row.charAt(x));
                    if (state == null || state.isAir()) continue;
                    StructureLocalPos transformed = StructureTransform.transformCoords(
                            x, y, z, sizeX, sizeZ, rot, mirrorX, mirrorZ);
                    BlockPos pos = new BlockPos(
                            transformed.x() + offset.x(),
                            transformed.y() + offset.y(),
                            transformed.z() + offset.z());
                    blocks.put(pos, BlockInfo.fromBlockState(
                            StructureTransform.transformStateForPlace(state, rot, mirrorX, mirrorZ)));
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
    }

    // -------------------------------------------------------------------------
    // 控件
    // -------------------------------------------------------------------------

    /** 服务端动作通过 {@link BlueprintActionWidget} 的固定下标转发，本控件保持纯客户端。 */
    private ButtonWidget clientButton(int x, int y, int width, int height, String langKey, Runnable clientAction) {
        ButtonWidget button = new ButtonWidget(x, y, width, height,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(langKey)),
                click -> {
                    if (click.isRemote) clientAction.run();
                });
        button.setClientSideWidget();
        return button;
    }

    private record Row(String id, String label, boolean directory) {}

    // ---------------------------------------------------------------
    // 覆盖页显示时吞掉鼠标事件，避免点到下层的标签页控件
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
