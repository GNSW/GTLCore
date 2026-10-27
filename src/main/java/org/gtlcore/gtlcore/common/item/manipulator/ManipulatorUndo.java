package org.gtlcore.gtlcore.common.item.manipulator;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferProxyPartMachine;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.manipulator.ManipulatorConnections;
import org.gtlcore.gtlcore.integration.ae2.manipulator.ManipulatorNetwork;
import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAeNetworkRuntime;
import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAeSavedData;
import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessNetworkCoreBlockEntity;

import com.gregtechceu.gtceu.api.machine.MetaMachine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One saved inverse journal per tool's current task. Only successful commits enter the journal. */
public final class ManipulatorUndo {

    private static ManipulatorTask capturingTask;
    private static ServerLevel capturingLevel;
    private static Frame capturingFrame;
    private final List<Edit> edits = new ArrayList<>();
    public final boolean enabled;
    public boolean active;
    public int total;
    public long bytes;

    public ManipulatorUndo(boolean enabled) {
        this.enabled = enabled;
    }

    public ManipulatorUndo(CompoundTag tag) {
        enabled = tag.getBoolean("Enabled");
        active = tag.getBoolean("Active");
        total = tag.getInt("Total");
        for (Tag value : tag.getList("Edits", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            edits.add(new Edit(Frame.read(entry.getCompound("Before")), Frame.read(entry.getCompound("After")),
                    ManipulatorResources.read(entry.getList("Paid", Tag.TAG_COMPOUND)),
                    ManipulatorResources.read(entry.getList("Recovered", Tag.TAG_COMPOUND))));
            bytes = Math.addExact(bytes, entry.sizeInBytes());
        }
    }

    public boolean available() {
        return enabled && !edits.isEmpty();
    }

    public int completed() {
        return total - edits.size();
    }

    public int pendingCount() {
        return edits.size();
    }

    public static boolean capturing(ServerLevel level) {
        return capturingLevel == level && capturingFrame != null && level.getServer().isSameThread();
    }

    public static void captureBlockChange(ServerLevel level, BlockPos position) {
        if (!capturing(level) || !level.isInWorldBounds(position) || capturingFrame.cells.containsKey(position)) return;
        BlockPos pos = position.immutable();
        capturingTask.failure = pos;
        if (!level.hasChunkAt(pos)) throw new IllegalArgumentException("mm.status.chunks");
        Cell cell = Cell.capture(level, pos);
        capturingFrame.cells.put(pos, cell);
        if (level.getBlockEntity(pos) instanceof WirelessNetworkCoreBlockEntity core) {
            capturingFrame.networks.putIfAbsent(core.getFrequency(), WirelessAeSavedData.get(level.getServer()).captureNetwork(core.getFrequency()));
        }
        MetaMachine machine = MetaMachine.getMachine(level, pos);
        if (machine instanceof MEPatternBufferProxyPartMachine proxy && proxy.getBufferPos() != null) captureBlockChange(level, proxy.getBufferPos());
        if (machine instanceof MEPatternBufferPartMachineBase buffer) {
            for (BlockPos proxy : buffer.getBoundProxyPositions()) captureBlockChange(level, proxy);
        }
        capturingTask.undo.checkBudget(capturingTask, capturingFrame.save().sizeInBytes());
    }

    public static boolean discardDrops(ServerLevel level, BlockPos pos) {
        Cell before = capturingFrame.cells.get(pos);
        return (before == null ? level.getBlockState(pos) : before.state).is(net.minecraftforge.common.Tags.Blocks.ORES);
    }

    public void beginCapture(ManipulatorTask task, ServerLevel level, Frame frame) {
        if (!enabled) return;
        capturingTask = task;
        capturingLevel = level;
        capturingFrame = frame;
    }

    public void endCapture() {
        if (!enabled) return;
        capturingFrame = null;
        capturingLevel = null;
        capturingTask = null;
    }

    public void requirements(Map<AEKey, Long> remaining) {
        remaining.clear();
        for (Edit edit : edits) edit.recovered.forEach((key, amount) -> ManipulatorResources.add(remaining, key, amount));
    }

    public Frame capture(ManipulatorTask task, ServerLevel level, ManipulatorTask.Operation op) {
        if (!enabled) return null;
        Set<BlockPos> positions = new LinkedHashSet<>();
        positions.add(op.target());
        if (task.settings.mode == ManipulatorSettings.Mode.MOVE) positions.add(op.source());
        // A new proxy can attach to an existing buffer outside the selection.
        CompoundTag snapshot = op.snapshot().data();
        if (snapshot.contains("ProxySource")) {
            BlockPos source = BlockPos.of(snapshot.getLong("ProxySource"));
            positions.add(source);
            BlockPos local = source.subtract(task.settings.min());
            if (task.settings.template() && inside(local, task.settings.size())) {
                BlockPos offset = op.target().subtract(ManipulatorGeometry.target(op.source().subtract(task.settings.min()), 0, task.settings));
                positions.add(ManipulatorGeometry.target(local, 0, task.settings).offset(offset));
            }
        }
        if (task.settings.mode == ManipulatorSettings.Mode.MOVE) {
            for (long value : snapshot.getLongArray("BufferProxies")) {
                BlockPos pos = BlockPos.of(value);
                positions.add(pos);
                BlockPos local = pos.subtract(task.settings.min());
                if (inside(local, task.settings.size())) positions.add(ManipulatorGeometry.target(local, 0, task.settings));
            }
        }
        // Follow the existing proxy/buffer API, including the inverse membership list.
        List<BlockPos> pending = new ArrayList<>(positions);
        for (int index = 0; index < pending.size(); index++) {
            BlockPos pos = pending.get(index);
            task.failure = pos;
            if (!task.loaded(level, pos)) return null;
            MetaMachine machine = MetaMachine.getMachine(level, pos);
            if (machine instanceof MEPatternBufferProxyPartMachine proxy && proxy.getBufferPos() != null && positions.add(proxy.getBufferPos())) pending.add(proxy.getBufferPos());
            if (machine instanceof MEPatternBufferPartMachineBase buffer) {
                for (BlockPos proxy : buffer.getBoundProxyPositions()) if (positions.add(proxy)) pending.add(proxy);
            }
        }
        Frame frame = Frame.capture(level, positions, Set.of());
        checkBudget(task, frame.save().sizeInBytes());
        return frame;
    }

    private static boolean inside(BlockPos pos, BlockPos size) {
        return pos.getX() >= 0 && pos.getX() < size.getX() && pos.getY() >= 0 && pos.getY() < size.getY() && pos.getZ() >= 0 && pos.getZ() < size.getZ();
    }

    private void checkBudget(ManipulatorTask task, long extra) {
        if (Math.addExact(Math.addExact(task.snapshotBytes(), bytes), extra) > ConfigHolder.INSTANCE.manipulator.maxSnapshotBytes) throw new IllegalArgumentException("mm.error.undo_size");
    }

    public void record(ManipulatorTask task, ServerLevel level, Frame before, Map<AEKey, Long> paid, Map<AEKey, Long> recovered) {
        if (!enabled) return;
        Frame after = Frame.capture(level, before.cells.keySet(), before.networks.keySet());
        // Newly generated core identities did not exist before placement.
        for (UUID frequency : after.networks.keySet()) before.networks.putIfAbsent(frequency, new CompoundTag());
        Frame original = new Frame();
        before.cells.forEach((pos, cell) -> {
            if (!cell.equals(after.cells.get(pos))) original.cells.put(pos, cell);
        });
        after.cells.keySet().retainAll(original.cells.keySet());
        before.networks.forEach((frequency, data) -> {
            if (!data.equals(after.networks.get(frequency))) original.networks.put(frequency, data);
        });
        after.networks.keySet().retainAll(original.networks.keySet());
        if (original.cells.isEmpty() && original.networks.isEmpty() && paid.isEmpty() && recovered.isEmpty()) return;
        Edit edit = new Edit(original, after, new LinkedHashMap<>(paid), new LinkedHashMap<>(recovered));
        long size = edit.save().sizeInBytes();
        checkBudget(task, size);
        edits.add(edit);
        bytes = Math.addExact(bytes, size);
        task.changed();
    }

    public boolean step(ManipulatorTask task, ServerPlayer player, ItemStack tool, IGrid grid) {
        Edit edit = edits.get(edits.size() - 1);
        ServerLevel level = player.serverLevel();
        for (BlockPos pos : edit.after.cells.keySet()) {
            task.failure = pos;
            if (!task.loaded(level, pos)) return false;
            if (!task.allowed(level, player, pos)) return stop(task, "mm.error.permission");
            if (!edit.after.cells.get(pos).equals(Cell.capture(level, pos))) return stop(task, "mm.error.undo_conflict");
        }
        var wireless = WirelessAeSavedData.get(player.server);
        for (UUID frequency : edit.after.networks.keySet()) {
            if (!edit.after.networks.get(frequency).equals(wireless.captureNetwork(frequency)) ||
                    !wireless.canRestoreNetwork(frequency, edit.before.networks.get(frequency), edit.after.networks.get(frequency)))
                return stop(task, "mm.error.undo_conflict");
        }
        for (var entry : edit.recovered.entrySet()) {
            long needed = entry.getValue() - task.escrow.getOrDefault(entry.getKey(), 0L);
            if (needed <= 0) continue;
            long taken = ManipulatorResources.extract(player, grid, entry.getKey(), needed, false);
            ManipulatorResources.add(task.escrow, entry.getKey(), taken);
            task.changed();
            if (taken < needed) {
                task.status(grid == null ? "mm.status.materials_offline" : "mm.status.materials");
                return false;
            }
        }
        long eu = 0;
        Map<BlockPos, BlockSnapshot> events = new LinkedHashMap<>();
        for (BlockPos pos : edit.after.cells.keySet()) {
            Cell current = edit.after.cells.get(pos);
            Cell original = edit.before.cells.get(pos);
            eu = Math.addExact(eu, task.energy(level, player, pos, current.state, original.state));
            if (current.state != original.state) {
                if (!current.state.isAir() && ForgeHooks.onBlockBreakEvent(level, player.gameMode.getGameModeForPlayer(), player, pos) < 0) return stop(task, "mm.error.permission");
                events.put(pos, BlockSnapshot.create(level.dimension(), level, pos));
            }
        }
        if (!player.isCreative() && !ManipulatorNetwork.charge(tool, grid, eu)) {
            task.status("mm.status.energy");
            return false;
        }
        boolean success = false;
        try {
            edit.before.restore(level);
            for (var entry : events.entrySet()) {
                if (!edit.before.cells.get(entry.getKey()).state.isAir() && ForgeEventFactory.onBlockPlace(player, entry.getValue(), Direction.UP)) return stop(task, "mm.error.permission");
            }
            success = true;
        } finally {
            if (!success) {
                edit.after.restore(level);
                if (!player.isCreative()) ManipulatorNetwork.refund(tool, eu);
                task.paused = true;
                task.changed();
            }
        }
        edit.recovered.forEach((key, amount) -> {
            task.escrow.computeIfPresent(key, (k, n) -> n - amount);
            task.remaining.computeIfPresent(key, (k, n) -> n - amount);
        });
        edit.paid.forEach((key, amount) -> ManipulatorResources.add(task.escrow, key, amount));
        edits.remove(edits.size() - 1);
        bytes -= edit.save().sizeInBytes();
        task.refundSurplus(player, grid);
        task.status("mm.status.undoing");
        task.changed();
        return true;
    }

    private static boolean stop(ManipulatorTask task, String message) {
        task.paused = true;
        task.status(message);
        return false;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Enabled", enabled);
        tag.putBoolean("Active", active);
        tag.putInt("Total", total);
        ListTag list = new ListTag();
        edits.forEach(edit -> list.add(edit.save()));
        tag.put("Edits", list);
        return tag;
    }

    private record Edit(Frame before, Frame after, Map<AEKey, Long> paid, Map<AEKey, Long> recovered) {

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.put("Before", before.save());
            tag.put("After", after.save());
            tag.put("Paid", ManipulatorResources.save(paid));
            tag.put("Recovered", ManipulatorResources.save(recovered));
            return tag;
        }
    }

    public static final class Frame {

        private final Map<BlockPos, Cell> cells = new LinkedHashMap<>();
        private final Map<UUID, CompoundTag> networks = new LinkedHashMap<>();

        private static Frame capture(ServerLevel level, Set<BlockPos> positions, Set<UUID> knownNetworks) {
            Frame frame = new Frame();
            Set<UUID> frequencies = new LinkedHashSet<>(knownNetworks);
            for (BlockPos pos : positions) {
                frame.cells.put(pos, Cell.capture(level, pos));
                if (level.getBlockEntity(pos) instanceof WirelessNetworkCoreBlockEntity core) frequencies.add(core.getFrequency());
            }
            var saved = WirelessAeSavedData.get(level.getServer());
            for (UUID frequency : frequencies) frame.networks.put(frequency, saved.captureNetwork(frequency));
            return frame;
        }

        public void restore(ServerLevel level) {
            // Detach all entities before setting blocks; neither inventories nor machine removal refunds may run.
            for (BlockPos pos : cells.keySet()) {
                if (MetaMachine.getMachine(level, pos) instanceof MEPatternBufferProxyPartMachine proxy) proxy.setBuffer(null);
                level.removeBlockEntity(pos);
            }
            for (var entry : cells.entrySet()) entry.getValue().restore(level, entry.getKey());
            var saved = WirelessAeSavedData.get(level.getServer());
            networks.forEach(saved::restoreNetwork);
            for (var entry : cells.entrySet()) {
                GlobalPos global = GlobalPos.of(level.dimension(), entry.getKey());
                for (UUID frequency : saved.removeMembersAt(global)) WirelessAeNetworkRuntime.disconnectMembersAt(frequency, global);
                for (Tag value : entry.getValue().wireless.getList("Wireless", Tag.TAG_COMPOUND)) {
                    CompoundTag binding = (CompoundTag) value;
                    UUID frequency = binding.getUUID("Frequency");
                    Direction side = binding.contains("Side") ? Direction.values()[binding.getInt("Side")] : null;
                    saved.addMember(frequency, WirelessAeSavedData.MemberKey.of(level.dimension(), entry.getKey(), side));
                    WirelessAeNetworkRuntime.requestReconnect(frequency);
                }
            }
            for (BlockPos pos : cells.keySet()) {
                if (MetaMachine.getMachine(level, pos) instanceof MEPatternBufferProxyPartMachine proxy && proxy.getBufferPos() != null &&
                        MetaMachine.getMachine(level, proxy.getBufferPos()) instanceof MEPatternBufferPartMachineBase)
                    proxy.setBuffer(proxy.getBufferPos());
            }
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            ListTag blocks = new ListTag();
            cells.forEach((pos, cell) -> {
                CompoundTag entry = cell.save();
                entry.putLong("Pos", pos.asLong());
                blocks.add(entry);
            });
            tag.put("Cells", blocks);
            CompoundTag wireless = new CompoundTag();
            networks.forEach((frequency, data) -> wireless.put(frequency.toString(), data));
            tag.put("Networks", wireless);
            return tag;
        }

        private static Frame read(CompoundTag tag) {
            Frame frame = new Frame();
            for (Tag value : tag.getList("Cells", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) value;
                frame.cells.put(BlockPos.of(entry.getLong("Pos")), new Cell(
                        NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), entry.getCompound("State")),
                        entry.contains("Entity") ? entry.getCompound("Entity") : null, entry.getCompound("Wireless")));
            }
            CompoundTag networks = tag.getCompound("Networks");
            for (String key : networks.getAllKeys()) frame.networks.put(UUID.fromString(key), networks.getCompound(key));
            return frame;
        }
    }

    private record Cell(BlockState state, CompoundTag entity, CompoundTag wireless) {

        static Cell capture(ServerLevel level, BlockPos pos) {
            BlockEntity be = level.getBlockEntity(pos);
            CompoundTag wireless = new CompoundTag();
            ManipulatorConnections.captureWireless(level, pos, wireless);
            return new Cell(level.getBlockState(pos), be == null ? null : be.saveWithFullMetadata(), wireless);
        }

        void restore(ServerLevel level, BlockPos pos) {
            int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
            if (!level.setBlock(pos, state, flags) && level.getBlockState(pos) != state) throw new IllegalStateException("Unable to undo manipulator block at " + pos);
            if (entity != null) {
                level.removeBlockEntity(pos);
                BlockEntity restored = BlockEntity.loadStatic(pos, state, entity.copy());
                if (restored == null) throw new IllegalStateException("Unable to undo manipulator entity at " + pos);
                level.setBlockEntity(restored);
                restored.setChanged();
            }
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.put("State", NbtUtils.writeBlockState(state));
            if (entity != null) tag.put("Entity", entity);
            tag.put("Wireless", wireless);
            return tag;
        }
    }
}
