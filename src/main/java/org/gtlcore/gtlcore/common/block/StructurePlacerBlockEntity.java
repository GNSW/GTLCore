package org.gtlcore.gtlcore.common.block;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.client.gui.StructurePlacerUI;
import org.gtlcore.gtlcore.common.data.GTLStructureContent;
import org.gtlcore.gtlcore.common.item.CoordinatePositioningCardItem;
import org.gtlcore.gtlcore.common.structure.export.StructureExporter;
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

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

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
            activeExport = null;
            markChanged();
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
    }

    // -------------------------------------------------------------------------
    // 结构 / 位置
    // -------------------------------------------------------------------------

    public @Nullable StructureDefinition.Structure currentStructure() {
        clampIndices();
        return StructureTemplateRegistry.tryGetStructure(presetIndex, structureIndex);
    }

    private void clampIndices() {
        int presets = StructureTemplateRegistry.presetCount();
        if (presets <= 0) return;
        presetIndex = Mth.clamp(presetIndex, 0, presets - 1);
        int structs = StructureTemplateRegistry.getPreset(presetIndex).structures().size();
        structureIndex = Mth.clamp(structureIndex, 0, Math.max(0, structs - 1));
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
        if (c[0] == null || c[1] == null) return;
        activeExport = new StructureExporter.ExportTask(serverLevel, c[0], c[1], xMirror, zMirror, rotation);
        markChanged();
    }

    public boolean isExporting() {
        return activeExport != null;
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
        if (StructureTemplateRegistry.presetCount() <= 0) {
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

    public Component blueprintSummaryPreset() {
        if (StructureTemplateRegistry.isEmpty()) {
            return Component.translatable("ui.quantum_chromodynamic_charge.structure.no_presets");
        }
        return Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.summary_preset",
                StructureTemplateRegistry.getPreset(presetIndex).title());
    }

    public Component blueprintSummaryTemplate() {
        StructureDefinition.Structure s = currentStructure();
        if (s == null) return Component.empty();
        return Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.summary_template",
                structureDropdownLabel(s));
    }

    public Component blueprintSummaryTypeSize() {
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

    public Component blueprintSummarySource() {
        StructureDefinition.Structure s = currentStructure();
        if (s == null || s.source() == null) return Component.empty();
        return Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.source", s.source());
    }

    public Component blueprintSummaryIndex() {
        StructureDefinition.Structure s = currentStructure();
        if (s == null) return Component.empty();
        StructureDefinition.Preset p = StructureTemplateRegistry.getPreset(presetIndex);
        return Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.template_index",
                structureIndex + 1,
                p.structures().size());
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
