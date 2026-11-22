package org.gtlcore.gtlcore.common.block;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.client.gui.StructurePlacerUI;
import org.gtlcore.gtlcore.client.gui.StructurePlacerWorkspace;
import org.gtlcore.gtlcore.common.data.GTLStructureContent;
import org.gtlcore.gtlcore.common.item.CoordinatePositioningCardItem;
import org.gtlcore.gtlcore.common.structure.export.StructureExporter;
import org.gtlcore.gtlcore.common.structure.local.BlueprintBundle;
import org.gtlcore.gtlcore.common.structure.local.BlueprintIO;
import org.gtlcore.gtlcore.common.structure.local.LocalBlueprintLibrary;
import org.gtlcore.gtlcore.common.structure.material.StructureMaterialOps;
import org.gtlcore.gtlcore.common.structure.material.StructureMaterialTable;
import org.gtlcore.gtlcore.common.structure.material.StructureMaterialTier;
import org.gtlcore.gtlcore.common.structure.material.StructureMaterialType;
import org.gtlcore.gtlcore.common.structure.material.StructureMaterials;
import org.gtlcore.gtlcore.common.structure.model.StructureDefinition;
import org.gtlcore.gtlcore.common.structure.model.StructureDefinition.ExtraMaterial;
import org.gtlcore.gtlcore.common.structure.place.StructurePlacer;
import org.gtlcore.gtlcore.common.structure.registry.StructureTemplateRegistry;
import org.gtlcore.gtlcore.common.structure.transform.StructureTransform;

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构放置器：选预设、材料银行、调偏移/变换、分 tick 放置，或用坐标卡导出。
 * <p>
 * 材料逻辑在 {@link StructureMaterialOps}；本类负责持久化、客户端同步与 UI 入口。
 * 槽位内容由 LDLib 容器同步，其余状态通过方块实体更新包同步。
 * </p>
 * <p>
 * 移植自 Quantum-Chromodynamic-Charge（GPLv3，作者 maple197），经作者许可引入。
 * </p>
 */
public class StructurePlacerBlockEntity extends BlockEntity implements IUIHolder {

    public static final int COORD_SLOTS = 2;
    public static final int MATERIAL_ROWS = 3;
    public static final int MATERIAL_COLS = 12;
    public static final int MATERIAL_SLOTS = MATERIAL_ROWS * MATERIAL_COLS;
    public static final int CHUNK_EDGE_BLOCKS = 16;

    /** 导出每 tick 采样格数。 */
    public static final int EXPORT_BUDGET_PER_TICK = 1 << 16;
    /** 放置进度同步间隔（tick）。 */
    private static final int SYNC_INTERVAL = 5;
    /** 本地蓝图目录检查间隔（tick）：签名为文件列表+大小+修改时间，未变时不读文件。 */
    private static final int BLUEPRINT_CHECK_INTERVAL = 40;

    /** 当前界面实例的槽位访问控制器（仅本次打开界面有效，不持久化）。 */
    @Nullable
    private StructurePlacerWorkspace workspace;

    /** 界面打开时登记槽位控制器（客户端/服务端各一份）。 */
    public void attachWorkspace(@Nullable StructurePlacerWorkspace workspace) {
        this.workspace = workspace;
    }

    /** 标签页切换：服务端收到同步后只放开当前页格子的放入权限。 */
    public void applyActiveTab(int tab) {
        if (workspace != null) workspace.setActiveTab(tab);
    }

    /** 坐标卡 / 结构组件槽。 */
    private final ItemStackTransfer coordinateInventory = new Transfer(COORD_SLOTS);
    private final ItemStackTransfer materialInventory = new Transfer(MATERIAL_SLOTS);

    /** 材料点数银行（下标 = {@link StructureMaterialType#index()}）。 */
    private int[] materialBank = StructureMaterials.newBankArray();

    /** 材料是否满足当前结构需求。 */
    private boolean materialsAdequate;

    private int presetIndex;
    private int structureIndex;
    private int offsetX;
    private int offsetY = -1;
    private int offsetZ;

    private boolean skipAir = true;
    /**
     * 跳过已有方块：世界中该位置非空气则不覆盖放置。
     * {@code false} 时覆盖（基岩仍始终跳过）。
     */
    private boolean skipOccupied;
    private boolean updateLight = true;
    /** 放置时是否更新高度图；默认开启。 */
    private boolean updateHeightmap = true;
    private boolean xMirror;
    private boolean zMirror;
    private int rotation;
    private int speed = 10;
    private boolean taskCompleted = true;
    private int progress;

    /** 运行中的放置 / 导出任务（不持久化）。 */
    private StructurePlacer.PlacementTask activePlacement;
    private StructureExporter.ExportTask activeExport;
    private int syncCountdown;

    /** 导出状态：0=就绪，1=导出中，2=完成，3=失败。 */
    private int exportState;
    private int exportProgress;
    private String exportFile = "";
    private String exportError = "";
    private int exportSyncCountdown;

    /** 本地蓝图文件列表（只有路径/名称/体积，内容不解析）。 */
    private List<LocalBlueprintLibrary.Entry> localBlueprints = List.of();
    /** 已读取过的蓝图元数据：相对路径 -> {sizeX, sizeY, sizeZ, blocks}。 */
    private Map<String, int[]> localMeta = Map.of();
    /** 本地蓝图目录树（含空文件夹），用于选择界面展示。 */
    private List<String> localDirectories = List.of();
    /** 选中的本地蓝图相对路径；空串表示使用内置结构。 */
    private String localBlueprintPath = "";
    /** 蓝图列表版本号：客户端据此重建选择界面。 */
    private int blueprintRevision;
    private int blueprintCheckCountdown;

    public StructurePlacerBlockEntity(BlockPos pos, BlockState state) {
        this(GTLStructureContent.STRUCTURE_PLACER_ENTITY.get(), pos, state);
    }

    public StructurePlacerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        coordinateInventory.setFilter(stack -> stack.isEmpty() ||
                stack.getItem() == GTLStructureContent.COORDINATE_POSITIONING_CARD.get());
        coordinateInventory.setOnContentsChanged(this::onInventoryChanged);
        materialInventory.setOnContentsChanged(this::onInventoryChanged);
    }

    // -------------------------------------------------------------------------
    // 生命周期 / 同步
    // -------------------------------------------------------------------------

    public static void tick(Level level, BlockPos pos, BlockState state, BlockEntity be) {
        if (be instanceof StructurePlacerBlockEntity placer) {
            placer.serverTick();
        }
    }

    private void serverTick() {
        if (level == null || level.isClientSide) return;

        // 蓝图文件夹的内容变化（放入/删除/覆盖文件）在这里被自动识别
        if (--blueprintCheckCountdown <= 0) {
            blueprintCheckCountdown = BLUEPRINT_CHECK_INTERVAL;
            refreshBlueprints();
        }

        if (activePlacement != null) {
            boolean done = activePlacement.tick();
            progress = done ? 100 : activePlacement.progress();
            if (done) {
                taskCompleted = true;
                activePlacement = null;
                markChanged();
            } else if (--syncCountdown <= 0) {
                syncCountdown = SYNC_INTERVAL;
                markChanged();
            }
        }

        if (activeExport != null && activeExport.tick(EXPORT_BUDGET_PER_TICK)) {
            finishExport();
        } else if (activeExport != null) {
            exportProgress = activeExport.progressPercentage();
            if (--exportSyncCountdown <= 0) {
                exportSyncCountdown = SYNC_INTERVAL;
                markChanged();
            }
        }
    }

    /** 导出任务结束：记录结果、同步 UI、通知附近玩家。 */
    private void finishExport() {
        StructureExporter.ExportTask task = activeExport;
        activeExport = null;
        exportProgress = 100;
        exportSyncCountdown = 0;
        if (task == null) return;
        boolean ok = !task.isFailed();
        if (ok) {
            exportState = 2;
            exportError = "";
            exportFile = task.savedFileName();
            GTLCore.LOGGER.info("Structure export finished: {} ({}x{}x{})",
                    exportFile, task.outSizeX(), task.outSizeY(), task.outSizeZ());
        } else {
            exportState = 3;
            exportFile = "";
            exportError = task.failureMessage();
            GTLCore.LOGGER.error("Structure export failed: {}", exportError);
        }
        announceExport(ok, task);
        markChanged();
    }

    /** 导出结果聊天反馈：发给放置器 64 格内的玩家。 */
    private void announceExport(boolean ok, @Nullable StructureExporter.ExportTask task) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        Component message;
        if (ok && task != null) {
            message = Component.translatable(
                    "ui.quantum_chromodynamic_charge.structure.export_done_msg",
                    LocalBlueprintLibrary.FOLDER + "/" + LocalBlueprintLibrary.DEFAULT_SUBFOLDER + "/" + exportFile,
                    task.outSizeX() + "x" + task.outSizeY() + "x" + task.outSizeZ())
                    .withStyle(ChatFormatting.GREEN);
        } else {
            message = Component.translatable(
                    "ui.quantum_chromodynamic_charge.structure.export_failed_msg",
                    Component.translatable(exportError))
                    .withStyle(ChatFormatting.RED);
        }
        double x = worldPosition.getX() + 0.5D;
        double y = worldPosition.getY() + 0.5D;
        double z = worldPosition.getZ() + 0.5D;
        for (ServerPlayer player : serverLevel.players()) {
            if (player.distanceToSqr(x, y, z) <= 64.0D * 64.0D) {
                player.displayClientMessage(message, false);
            }
        }
    }

    /** 服务端状态变更：保存 + 发送方块实体更新包。 */
    public void markChanged() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    private void onInventoryChanged() {
        if (level != null && !level.isClientSide) {
            examineMaterials();
            markChanged();
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        readUiState(tag);
        if (tag.contains("coordinateInventory")) {
            coordinateInventory.deserializeNBT(tag.getCompound("coordinateInventory"));
        }
        if (tag.contains("materialInventory")) {
            materialInventory.deserializeNBT(tag.getCompound("materialInventory"));
        }
        activePlacement = null;
        activeExport = null;
        taskCompleted = true;
        progress = 0;
        if (exportState == 1) {
            // 存档载入时进行中的导出任务已丢失
            exportState = 0;
            exportProgress = 0;
        }
        // 先补齐本地蓝图列表，否则指向本地蓝图的存档会找不到结构
        if (level != null && !level.isClientSide) {
            LocalBlueprintLibrary.refresh();
            rebuildLocalBlueprints();
        }
        clampIndices();
        examineMaterials();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        writeUiState(tag);
        tag.put("coordinateInventory", coordinateInventory.serializeNBT());
        tag.put("materialInventory", materialInventory.serializeNBT());
    }

    /** 仅同步 UI 状态（槽位由容器同步）。 */
    @Override
    public CompoundTag getUpdateTag() {
        return writeUiState(new CompoundTag());
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        readUiState(tag);
        clampIndices();
        examineMaterials();
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /**
     * 增量更新包只携带 UI 状态；不走 {@code load}，避免重置客户端的进度/任务标记。
     */
    @Override
    public void onDataPacket(net.minecraft.network.Connection connection,
                             ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            handleUpdateTag(tag);
        }
    }

    private CompoundTag writeUiState(CompoundTag tag) {
        tag.putIntArray("materialBank", materialBank);
        tag.putBoolean("materialsAdequate", materialsAdequate);
        tag.putInt("presetIndex", presetIndex);
        tag.putInt("structureIndex", structureIndex);
        tag.putInt("offsetX", offsetX);
        tag.putInt("offsetY", offsetY);
        tag.putInt("offsetZ", offsetZ);
        tag.putBoolean("skipAir", skipAir);
        tag.putBoolean("skipOccupied", skipOccupied);
        tag.putBoolean("updateLight", updateLight);
        tag.putBoolean("updateHeightmap", updateHeightmap);
        tag.putBoolean("xMirror", xMirror);
        tag.putBoolean("zMirror", zMirror);
        tag.putInt("rotation", rotation);
        tag.putInt("speed", speed);
        tag.putBoolean("taskCompleted", taskCompleted);
        tag.putInt("progress", progress);
        tag.putInt("exportState", exportState);
        tag.putInt("exportProgress", exportProgress);
        tag.putString("exportFile", exportFile);
        tag.putString("exportError", exportError);
        tag.putString("localBlueprint", localBlueprintPath);
        tag.putInt("blueprintRevision", blueprintRevision);
        ListTag blueprints = new ListTag();
        for (LocalBlueprintLibrary.Entry entry : localBlueprints) {
            CompoundTag item = new CompoundTag();
            item.putString("path", entry.path());
            item.putLong("bytes", entry.bytes());
            blueprints.add(item);
        }
        tag.put("blueprints", blueprints);
        ListTag meta = new ListTag();
        for (Map.Entry<String, int[]> entry : localMeta.entrySet()) {
            int[] value = entry.getValue();
            CompoundTag item = new CompoundTag();
            item.putString("path", entry.getKey());
            item.putInt("x", value[0]);
            item.putInt("y", value[1]);
            item.putInt("z", value[2]);
            item.putInt("b", value[3]);
            meta.add(item);
        }
        tag.put("blueprintMeta", meta);
        ListTag directories = new ListTag();
        for (String dir : localDirectories) {
            directories.add(net.minecraft.nbt.StringTag.valueOf(dir));
        }
        tag.put("blueprintDirs", directories);
        return tag;
    }

    private void readUiState(CompoundTag tag) {
        if (tag.contains("materialBank")) {
            materialBank = StructureMaterialOps.ensureBankSize(tag.getIntArray("materialBank"));
        }
        materialsAdequate = tag.getBoolean("materialsAdequate");
        presetIndex = tag.getInt("presetIndex");
        structureIndex = tag.getInt("structureIndex");
        offsetX = tag.getInt("offsetX");
        offsetY = tag.contains("offsetY") ? tag.getInt("offsetY") : -1;
        offsetZ = tag.getInt("offsetZ");
        skipAir = !tag.contains("skipAir") || tag.getBoolean("skipAir");
        skipOccupied = tag.getBoolean("skipOccupied");
        updateLight = !tag.contains("updateLight") || tag.getBoolean("updateLight");
        updateHeightmap = !tag.contains("updateHeightmap") || tag.getBoolean("updateHeightmap");
        xMirror = tag.getBoolean("xMirror");
        zMirror = tag.getBoolean("zMirror");
        rotation = tag.getInt("rotation");
        speed = tag.contains("speed") ? tag.getInt("speed") : 10;
        taskCompleted = !tag.contains("taskCompleted") || tag.getBoolean("taskCompleted");
        progress = tag.getInt("progress");
        exportState = tag.getInt("exportState");
        exportProgress = tag.getInt("exportProgress");
        exportFile = tag.getString("exportFile");
        exportError = tag.getString("exportError");
        blueprintRevision = tag.getInt("blueprintRevision");
        localBlueprintPath = tag.getString("localBlueprint");
        List<LocalBlueprintLibrary.Entry> files = new java.util.ArrayList<>();
        ListTag blueprints = tag.getList("blueprints", 10);
        for (int i = 0; i < blueprints.size(); i++) {
            CompoundTag entry = blueprints.getCompound(i);
            String path = entry.getString("path");
            if (path.isEmpty()) continue;
            files.add(new LocalBlueprintLibrary.Entry(path, LocalBlueprintLibrary.displayName(path),
                    entry.getLong("bytes")));
        }
        localBlueprints = List.copyOf(files);

        Map<String, int[]> meta = new java.util.LinkedHashMap<>();
        ListTag metaTag = tag.getList("blueprintMeta", 10);
        for (int i = 0; i < metaTag.size(); i++) {
            CompoundTag entry = metaTag.getCompound(i);
            String path = entry.getString("path");
            int sizeX = entry.getInt("x");
            int sizeY = entry.getInt("y");
            int sizeZ = entry.getInt("z");
            if (path.isEmpty() || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) continue;
            meta.put(path, new int[] { sizeX, sizeY, sizeZ, entry.getInt("b") });
        }
        localMeta = Map.copyOf(meta);
        List<String> dirs = new java.util.ArrayList<>();
        ListTag dirTag = tag.getList("blueprintDirs", 8);
        for (int i = 0; i < dirTag.size(); i++) {
            String dir = dirTag.getString(i);
            if (!dir.isEmpty()) dirs.add(dir);
        }
        localDirectories = List.copyOf(dirs);
    }

    // -------------------------------------------------------------------------
    // 结构 / 位置
    // -------------------------------------------------------------------------

    public @Nullable StructureDefinition.Structure currentStructure() {
        if (!localBlueprintPath.isEmpty()) {
            int[] meta = localMeta.get(localBlueprintPath);
            if (meta != null) {
                return LocalBlueprintLibrary.structure(localBlueprintPath, meta[0], meta[1], meta[2], meta[3]);
            }
        }
        clampIndices();
        return structureAt(presetIndex, structureIndex);
    }

    /** 内置预设库数量。 */
    public int presetCount() {
        return StructureTemplateRegistry.presetCount();
    }

    public Component presetTitle(int preset) {
        if (preset >= 0 && preset < StructureTemplateRegistry.presetCount()) {
            return StructureTemplateRegistry.getPreset(preset).title();
        }
        return Component.literal("-");
    }

    public int structureCount(int preset) {
        if (preset >= 0 && preset < StructureTemplateRegistry.presetCount()) {
            return StructureTemplateRegistry.getPreset(preset).structures().size();
        }
        return 0;
    }

    /** 按下标取内置结构；没有结构时返回 null。 */
    @Nullable
    public StructureDefinition.Structure structureAt(int preset, int index) {
        int count = structureCount(preset);
        if (count <= 0) return null;
        return StructureTemplateRegistry.getPreset(preset).structures().get(Mth.clamp(index, 0, count - 1));
    }

    /** 已选中的本地蓝图相对路径；空串表示使用内置结构。 */
    public String localBlueprintPath() {
        return localBlueprintPath;
    }

    /** 本地蓝图文件列表（只含路径/名称/体积）。 */
    public List<LocalBlueprintLibrary.Entry> localBlueprints() {
        return localBlueprints;
    }

    /** 已读取的蓝图元数据（相对路径 -> {x, y, z, blocks}）。 */
    public Map<String, int[]> localMeta() {
        return localMeta;
    }

    /** 本地蓝图目录树（相对路径，含空文件夹）。 */
    public List<String> localDirectories() {
        return localDirectories;
    }

    /** 蓝图列表版本号；界面据此判断是否需要重建列表。 */
    public int blueprintRevision() {
        return blueprintRevision;
    }

    public boolean isLocalBlueprintSelected() {
        return !localBlueprintPath.isEmpty() && localMeta.containsKey(localBlueprintPath);
    }

    private void clampIndices() {
        int presets = StructureTemplateRegistry.presetCount();
        if (presets <= 0) return;
        presetIndex = Mth.clamp(presetIndex, 0, presets - 1);
        int structs = structureCount(presetIndex);
        structureIndex = Mth.clamp(structureIndex, 0, Math.max(0, structs - 1));
    }

    /**
     * 选择蓝图。
     *
     * @param kind 0 = 内置结构（用 preset/index），1 = 本地蓝图（用 path）
     * @param path 本地蓝图相对路径
     */
    public void applySelection(int kind, int preset, int index, String path) {
        if (kind == 1) {
            if (!localMeta.containsKey(path)) return;
            localBlueprintPath = path;
        } else if (kind == 0) {
            if (preset < 0 || preset >= StructureTemplateRegistry.presetCount()) return;
            if (index < 0 || index >= structureCount(preset)) return;
            localBlueprintPath = "";
            presetIndex = preset;
            structureIndex = index;
        } else {
            return;
        }
        clampIndices();
        examineMaterials();
        markChanged();
    }

    private void rebuildLocalBlueprints() {
        localBlueprints = LocalBlueprintLibrary.entries();
        localDirectories = LocalBlueprintLibrary.directories();
    }

    /**
     * 选中一个本地蓝图：尺寸与方块数由客户端解析后带来（客户端没有文件时由服务端自己读）。
     *
     * @param sizeX 等 <= 0 表示客户端没读到，需要服务端解析
     */
    public void applyLocal(String path, int sizeX, int sizeY, int sizeZ, int blocks) {
        if (level == null || level.isClientSide || path.isEmpty()) return;
        int[] meta = sizeX > 0 && sizeY > 0 && sizeZ > 0 ? new int[] { sizeX, sizeY, sizeZ, Math.max(0, blocks) } :
                parseLocalMeta(path, false);
        if (meta == null) return;
        selectLocal(path, meta);
    }

    /** 玩家确认风险后强制读取一个超大蓝图。 */
    public void applyLocalForced(String path) {
        if (level == null || level.isClientSide || path.isEmpty()) return;
        int[] meta = parseLocalMeta(path, true);
        if (meta == null) return;
        selectLocal(path, meta);
    }

    private void selectLocal(String path, int[] meta) {
        Map<String, int[]> map = new LinkedHashMap<>(localMeta);
        map.put(path, meta);
        localMeta = Map.copyOf(map);
        localBlueprintPath = path;
        clampIndices();
        examineMaterials();
        markChanged();
    }

    @Nullable
    private int[] parseLocalMeta(String path, boolean forced) {
        Path file = LocalBlueprintLibrary.resolve(path);
        if (file == null || !java.nio.file.Files.isRegularFile(file)) return null;
        try {
            BlueprintBundle.Bundle bundle = forced ? BlueprintIO.loadForced(file) : BlueprintIO.load(file);
            return new int[] { bundle.sizeX(), bundle.sizeY(), bundle.sizeZ(), bundle.blocks() };
        } catch (Exception | OutOfMemoryError failure) {
            GTLCore.LOGGER.warn("Blueprint {} could not be read: {}", path, failure.toString());
            return null;
        }
    }

    /** 扫描本地蓝图目录；文件列表/大小/修改时间没变时直接跳过（签名比较，不读文件内容）。 */
    public void refreshBlueprints() {
        if (level == null || level.isClientSide) return;
        if (!LocalBlueprintLibrary.refresh()) return;
        onBlueprintsChanged();
    }

    /** 界面「刷新」按钮：忽略签名，强制重扫本地蓝图目录。 */
    public void rescanBlueprints() {
        if (level == null || level.isClientSide) return;
        LocalBlueprintLibrary.rescan();
        onBlueprintsChanged();
    }

    private void onBlueprintsChanged() {
        rebuildLocalBlueprints();
        if (!localBlueprintPath.isEmpty()) {
            boolean exists = false;
            for (LocalBlueprintLibrary.Entry entry : localBlueprints) {
                if (entry.path().equals(localBlueprintPath)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                localBlueprintPath = "";
                localMeta = Map.of();
            }
        }
        clampIndices();
        examineMaterials();
        blueprintRevision++;
        markChanged();
    }

    /** 放置起点：机器区块对齐 + 区块偏移 + Y 方块偏移。 */
    public BlockPos startPos() {
        StructureDefinition.Structure s = currentStructure();
        if (s == null) return worldPosition;
        BlockPos pos = getBlockPos();
        int chunkMinX = (pos.getX() >> 4) << 4;
        int chunkMinZ = (pos.getZ() >> 4) << 4;
        int centerOffsetX = (s.sizeX() - 1) / 32;
        int centerOffsetZ = (s.sizeZ() - 1) / 32;
        int startX = chunkMinX - centerOffsetX * 16 + offsetX * 16;
        int startZ = chunkMinZ - centerOffsetZ * 16 + offsetZ * 16;
        int startY = pos.getY() + offsetY;
        return new BlockPos(startX, startY, startZ);
    }

    public StructureTransform.BlockPosBounds placementBounds() {
        StructureDefinition.Structure s = currentStructure();
        if (s == null) return new StructureTransform.BlockPosBounds(worldPosition, worldPosition);
        return StructureTransform.transformedBounds(
                startPos(), s.sizeX(), s.sizeY(), s.sizeZ(), rotation, xMirror, zMirror);
    }

    @Nullable
    private BlockPos readCoord(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof CoordinatePositioningCardItem)) {
            return null;
        }
        return CoordinatePositioningCardItem.getCoordinate(stack);
    }

    private BlockPos[] coords() {
        BlockPos a = null, b = null;
        for (int i = 0; i < coordinateInventory.getSlots(); i++) {
            BlockPos p = readCoord(coordinateInventory.getStackInSlot(i));
            if (p == null) continue;
            if (a == null) a = p;
            else {
                b = p;
                break;
            }
        }
        return new BlockPos[] { a, b };
    }

    public boolean canExport() {
        BlockPos[] c = coords();
        return c[0] != null && c[1] != null;
    }

    // -------------------------------------------------------------------------
    // 材料银行（逻辑在 StructureMaterialOps；此处只做持久化/同步桥接）
    // -------------------------------------------------------------------------

    private StructureMaterialTable bankTable() {
        int[] ensured = StructureMaterialOps.ensureBankSize(materialBank);
        if (ensured != materialBank) {
            materialBank = ensured;
            markChanged();
        }
        return StructureMaterialOps.tableOf(materialBank);
    }

    private void writeBank(StructureMaterialTable table) {
        materialBank = table.toArray();
        markChanged();
    }

    /** 槽内结构组件 -> 点数银行。 */
    public void loadingMaterial() {
        writeBank(StructureMaterialOps.loadFromSlots(bankTable(), materialInventory));
        examineMaterials();
        markChanged();
    }

    /** 点数银行 -> 空槽兑回组件（优先高档）。 */
    public void unloadingMaterial() {
        writeBank(StructureMaterialOps.unloadToSlots(bankTable(), materialInventory));
        examineMaterials();
        markChanged();
    }

    /** 检查材料表 + extraMaterials 是否充足。 */
    public void examineMaterials() {
        StructureDefinition.Structure structure = currentStructure();
        if (StructureTemplateRegistry.isEmpty() || structure == null) {
            materialsAdequate = false;
            return;
        }
        materialsAdequate = StructureMaterialOps.isAdequate(
                bankTable(), structure, materialInventory);
    }

    /** 扣除材料表与额外物品；失败返回 false。 */
    private boolean consumeResources() {
        StructureDefinition.Structure structure = currentStructure();
        if (StructureTemplateRegistry.isEmpty() || structure == null) return false;
        StructureMaterialTable next = StructureMaterialOps.consume(
                bankTable(), structure, materialInventory);
        if (next == null) {
            examineMaterials();
            return false;
        }
        writeBank(next);
        examineMaterials();
        return true;
    }

    // -------------------------------------------------------------------------
    // 操作
    // -------------------------------------------------------------------------

    public void startPlacement() {
        Level level = getLevel();
        if (level == null || level.isClientSide || !taskCompleted) return;
        if (StructureTemplateRegistry.isEmpty()) return;

        StructureDefinition.Structure structure = currentStructure();
        if (structure == null) return;
        examineMaterials();
        if (!materialsAdequate) return;

        // 先加载资源，再扣材料，避免加载失败白扣费
        StructurePlacer.LoadedStructure loaded;
        try {
            loaded = StructurePlacer.load(structure);
        } catch (Exception e) {
            GTLCore.LOGGER.error(
                    "Structure load failed (materials not consumed): {} / {}",
                    structure.name(), structure.resource(), e);
            return;
        }

        if (!consumeResources()) return;

        progress = 0;
        taskCompleted = false;
        syncCountdown = SYNC_INTERVAL;
        markChanged();

        try {
            activePlacement = new StructurePlacer.PlacementTask(
                    (ServerLevel) level,
                    startPos(),
                    loaded,
                    Math.max(10000, speed * 10000),
                    skipOccupied,
                    skipAir,
                    updateLight,
                    updateHeightmap,
                    zMirror,
                    xMirror,
                    rotation);
        } catch (Exception e) {
            GTLCore.LOGGER.error(
                    "Structure placement failed after consume: {} / {}",
                    structure.name(), structure.resource(), e);
            taskCompleted = true;
            markChanged();
        }
    }

    public void exportRegion() {
        if (!(getLevel() instanceof ServerLevel serverLevel)) return;
        if (activeExport != null) return;
        BlockPos[] c = coords();
        if (c[0] == null || c[1] == null) {
            exportState = 3;
            exportProgress = 0;
            exportFile = "";
            exportError = "ui.quantum_chromodynamic_charge.structure.export_need_cards";
            announceExport(false, null);
            markChanged();
            return;
        }
        exportState = 1;
        exportProgress = 0;
        exportFile = "";
        exportError = "";
        exportSyncCountdown = SYNC_INTERVAL;
        activeExport = new StructureExporter.ExportTask(serverLevel, c[0], c[1], xMirror, zMirror, rotation);
        GTLCore.LOGGER.info("Structure export started: {} -> {} ({}x{}x{})",
                c[0], c[1], activeExport.outSizeX(), activeExport.outSizeY(), activeExport.outSizeZ());
        markChanged();
    }

    public boolean isExporting() {
        return activeExport != null;
    }

    public int exportState() {
        return exportState;
    }

    public int exportProgress() {
        return exportProgress;
    }

    public String exportFileName() {
        return exportFile;
    }

    public String exportError() {
        return exportError;
    }

    /** 导出页状态行。 */
    public Component exportStatusText() {
        return switch (exportState) {
            case 1 -> Component.translatable(
                    "ui.quantum_chromodynamic_charge.structure.export_progress", exportProgress);
            case 2 -> Component.translatable(
                    "ui.quantum_chromodynamic_charge.structure.export_done", exportFile)
                    .withStyle(ChatFormatting.GREEN);
            case 3 -> Component.translatable(
                    "ui.quantum_chromodynamic_charge.structure.export_failed",
                    Component.translatable(exportError))
                    .withStyle(ChatFormatting.RED);
            default -> Component.translatable("ui.quantum_chromodynamic_charge.structure.idle");
        };
    }

    /** 单个坐标卡槽的坐标文本。 */
    public Component exportCardText(int slot) {
        BlockPos pos = slot >= 0 && slot < coordinateInventory.getSlots() ?
                readCoord(coordinateInventory.getStackInSlot(slot)) : null;
        if (pos == null) {
            return Component.translatable(
                    "ui.quantum_chromodynamic_charge.structure.export_card_empty", slot + 1)
                    .withStyle(ChatFormatting.GRAY);
        }
        return Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.export_card",
                slot + 1, pos.getX(), pos.getY(), pos.getZ());
    }

    /** 两张卡围成的区域尺寸文本。 */
    public Component exportRegionText() {
        BlockPos[] c = coords();
        if (c[0] == null || c[1] == null) {
            return Component.translatable(
                    "ui.quantum_chromodynamic_charge.structure.export_need_cards")
                    .withStyle(ChatFormatting.RED);
        }
        int dx = Math.abs(c[0].getX() - c[1].getX()) + 1;
        int dy = Math.abs(c[0].getY() - c[1].getY()) + 1;
        int dz = Math.abs(c[0].getZ() - c[1].getZ()) + 1;
        return Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.export_region", dx, dy, dz);
    }

    // -------------------------------------------------------------------------
    // UI 操作入口
    // -------------------------------------------------------------------------

    public void setPresetIndex(int index) {
        presetIndex = index;
        structureIndex = 0;
        clampIndices();
        examineMaterials();
        markChanged();
    }

    public void setStructureIndex(int index) {
        structureIndex = index;
        clampIndices();
        examineMaterials();
        markChanged();
    }

    public void nudgeOffset(int axis, int delta) {
        switch (axis) {
            case 0 -> offsetX += delta;
            case 1 -> offsetY += delta;
            case 2 -> offsetZ += delta;
            default -> {}
        }
        markChanged();
    }

    public void setSkipAir(boolean v) {
        skipAir = v;
        markChanged();
    }

    public void setSkipOccupied(boolean v) {
        skipOccupied = v;
        markChanged();
    }

    public void setUpdateLight(boolean v) {
        updateLight = v;
        markChanged();
    }

    public void setUpdateHeightmap(boolean v) {
        updateHeightmap = v;
        markChanged();
    }

    public void setXMirror(boolean v) {
        xMirror = v;
        markChanged();
    }

    public void setZMirror(boolean v) {
        zMirror = v;
        markChanged();
    }

    public void cycleRotation() {
        rotation = (rotation + 90) % 360;
        markChanged();
    }

    public void nudgeSpeed(int delta) {
        speed = Mth.clamp(speed + delta, 1, 100);
        markChanged();
    }

    // -------------------------------------------------------------------------
    // 只读状态（UI 使用）
    // -------------------------------------------------------------------------

    public ItemStackTransfer getCoordinateInventory() {
        return coordinateInventory;
    }

    public ItemStackTransfer getMaterialInventory() {
        return materialInventory;
    }

    public int getPresetIndex() {
        return presetIndex;
    }

    public int getStructureIndex() {
        return structureIndex;
    }

    public int getOffsetX() {
        return offsetX;
    }

    public int getOffsetY() {
        return offsetY;
    }

    public int getOffsetZ() {
        return offsetZ;
    }

    public boolean isSkipAir() {
        return skipAir;
    }

    public boolean isSkipOccupied() {
        return skipOccupied;
    }

    public boolean isUpdateLight() {
        return updateLight;
    }

    public boolean isUpdateHeightmap() {
        return updateHeightmap;
    }

    public boolean isXMirror() {
        return xMirror;
    }

    public boolean isZMirror() {
        return zMirror;
    }

    public int getRotation() {
        return rotation;
    }

    public int getSpeed() {
        return speed;
    }

    public boolean isTaskCompleted() {
        return taskCompleted;
    }

    public int getProgress() {
        return progress;
    }

    public boolean isMaterialsAdequate() {
        return materialsAdequate;
    }

    public int bankValue(StructureMaterialType type) {
        return bankTable().get(type);
    }

    public int costValue(StructureMaterialType type) {
        StructureDefinition.Structure s = currentStructure();
        return s == null ? 0 : s.materialCost(type);
    }

    /** 材料预览条目：图标、现有、需求。 */
    public record MaterialPreviewEntry(ItemStack stack, int have, int need) {}

    /**
     * 当前结构的材料预览（需求 ∪ 存量），额外材料使用其自身物品图标。
     */
    public List<MaterialPreviewEntry> materialPreviewEntries() {
        List<MaterialPreviewEntry> list = new java.util.ArrayList<>();
        StructureDefinition.Structure structure = currentStructure();
        for (StructureMaterialType type : StructureMaterialType.values()) {
            int need = structure == null ? 0 : structure.materialCost(type);
            int have = bankValue(type);
            if (need > 0 || have > 0) {
                list.add(new MaterialPreviewEntry(
                        new ItemStack(StructureMaterials.item(type, StructureMaterialTier.BASIC)), have, need));
            }
        }
        if (structure != null) {
            Map<Item, Integer> counts = StructureMaterialOps.countSlots(materialInventory);
            for (ExtraMaterial extra : structure.extraMaterials()) {
                if (extra.amount() <= 0) continue;
                list.add(new MaterialPreviewEntry(extra.item().copy(),
                        counts.getOrDefault(extra.getItem(), 0), extra.amount()));
            }
        }
        return list;
    }

    /** 仅列出「银行有存量」或「结构有需求」的材料类型，以及额外物品需求/存量。 */
    public Component materialVisibleLines() {
        if (presetCount() <= 0) {
            return Component.translatable("ui.quantum_chromodynamic_charge.structure.no_presets");
        }
        StructureDefinition.Structure structure = currentStructure();
        if (structure == null) return Component.empty();
        StructureMaterialTable bank = bankTable();
        StructureMaterialTable cost = structure.materials();
        List<StructureMaterialType> visible = bank.visibleWith(cost);

        if (visible.isEmpty() && structure.extraMaterials().isEmpty()) {
            return Component.translatable("ui.quantum_chromodynamic_charge.structure.material.none_active");
        }
        Component out = Component.empty();
        boolean first = true;
        for (StructureMaterialType type : visible) {
            Component line = Component.translatable(
                    "ui.quantum_chromodynamic_charge.structure.material.line",
                    type.displayName(), bank.get(type), cost.get(type));
            out = first ? line : Component.empty().append(out).append(Component.literal(" · ")).append(line);
            first = false;
        }
        Map<Item, Integer> slotCounts = StructureMaterialOps.countSlots(materialInventory);
        for (ExtraMaterial extra : structure.extraMaterials()) {
            if (extra.amount() <= 0) continue;
            int have = slotCounts.getOrDefault(extra.getItem(), 0);
            Component line = Component.translatable(
                    "ui.quantum_chromodynamic_charge.structure.material.extra_line",
                    extra.item().getHoverName(), have, extra.amount());
            out = first ? line : Component.empty().append(out).append(Component.literal(" · ")).append(line);
            first = false;
        }
        return out;
    }

    public Component statusText() {
        if (!taskCompleted) {
            return Component.translatable("ui.quantum_chromodynamic_charge.structure.progress", progress);
        }
        if (isExporting()) {
            return Component.translatable("ui.quantum_chromodynamic_charge.structure.exporting");
        }
        if (!materialsAdequate) {
            return Component.translatable("ui.quantum_chromodynamic_charge.structure.material.insufficient");
        }
        return Component.translatable("ui.quantum_chromodynamic_charge.structure.idle");
    }

    /** 方块边长 -> 覆盖的区块数（向上取整，至少 0）。 */
    public static int blocksToChunks(int blocks) {
        if (blocks <= 0) return 0;
        return (blocks + CHUNK_EDGE_BLOCKS - 1) / CHUNK_EDGE_BLOCKS;
    }

    /** 蓝图页第一行：当前蓝图名称。 */
    public Component blueprintCurrentLine() {
        StructureDefinition.Structure s = currentStructure();
        if (s == null) {
            return Component.translatable("ui.quantum_chromodynamic_charge.structure.blueprint.none");
        }
        return Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.blueprint.current",
                s.title());
    }

    /** 蓝图页第二行：来源（内置预设第几个 / 本地相对路径）。 */
    public Component blueprintSourceLine() {
        if (isLocalBlueprintSelected()) {
            return Component.translatable(
                    "ui.quantum_chromodynamic_charge.structure.blueprint.local",
                    localBlueprintPath);
        }
        StructureDefinition.Structure s = currentStructure();
        if (s == null) return Component.empty();
        return Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.blueprint.builtin",
                presetTitle(presetIndex), structureIndex + 1, structureCount(presetIndex));
    }

    /** 蓝图页第三行：尺寸（方块 + 覆盖区块数）。 */
    public Component blueprintSizeLine() {
        StructureDefinition.Structure s = currentStructure();
        if (s == null) return Component.empty();
        Component size = Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.size_short",
                s.sizeX(), s.sizeY(), s.sizeZ(), blocksToChunks(s.sizeX()), blocksToChunks(s.sizeZ()));
        if (s.type() != null) {
            return Component.translatable(
                    "ui.quantum_chromodynamic_charge.structure.summary_type_size",
                    s.type(), size);
        }
        return Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.summary_size", size);
    }

    /** 蓝图页第四行：材料需求（本地蓝图按方块数等比折算，向上取整到 10 的倍数）。 */
    public Component blueprintCostLine() {
        StructureDefinition.Structure structure = currentStructure();
        if (structure == null) return Component.empty();
        StructureMaterialType type = StructureMaterialType.FRAME;
        int need = structure.materialCost(type);
        if (need <= 0) return Component.empty();
        return Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.material.line",
                type.displayName(), bankValue(type), need);
    }

    /** 下拉项标签：title · description（有描述时），否则仅 title。 */
    public static Component structureDropdownLabel(StructureDefinition.Structure s) {
        Component title = s.title();
        if (s.description() != null) {
            return Component.empty()
                    .append(title)
                    .append(Component.literal(" · "))
                    .append(s.description());
        }
        return title;
    }

    // -------------------------------------------------------------------------
    // IUIHolder
    // -------------------------------------------------------------------------

    @Nullable
    @Override
    public ModularUI createUI(net.minecraft.world.entity.player.Player player) {
        // 打开界面时补一次蓝图目录扫描，刚放进文件夹的蓝图立刻可见（客户端调用为空操作）
        refreshBlueprints();
        clampIndices();
        return StructurePlacerUI.create(this, player);
    }

    @Override
    public boolean isInvalid() {
        return isRemoved();
    }

    @Override
    public boolean isRemote() {
        return level != null && level.isClientSide;
    }

    @Override
    public void markAsDirty() {
        markChanged();
    }

    /**
     * 槽位内容变更时会补发 {@link ItemStackTransfer#setOnContentsChanged}（父类
     * {@code setStackInSlot} 不触发回调），保证方块实体存档与材料状态及时更新。
     */
    private final class Transfer extends ItemStackTransfer {

        private Transfer(int size) {
            super(size);
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            super.setStackInSlot(slot, stack);
            onContentsChanged(slot);
        }
    }
}
