package org.gtlcore.gtlcore.integration.ae2.manipulator;

import org.gtlcore.gtlcore.common.item.manipulator.ManipulatorGeometry;
import org.gtlcore.gtlcore.common.item.manipulator.ManipulatorSettings;
import org.gtlcore.gtlcore.common.item.manipulator.ManipulatorTask;
import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAeNetworkRuntime;
import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAeSavedData;
import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessNetworkCoreBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import appeng.api.parts.IPartHost;
import appeng.me.service.P2PService;
import appeng.parts.p2p.P2PTunnelPart;
import appeng.util.SettingsFrom;

import java.util.UUID;

/** Resolves identities after all affected block entities and nodes have been created. */
public final class ManipulatorConnections {

    private ManipulatorConnections() {}

    public static void initializeCore(ServerLevel level, BlockPos pos, ServerPlayer player) {
        if (level.getBlockEntity(pos) instanceof WirelessNetworkCoreBlockEntity core) {
            core.setOwner(player);
            WirelessAeSavedData.get(level.getServer()).setCore(core.getFrequency(), GlobalPos.of(level.dimension(), pos));
        }
    }

    public static void captureWireless(ServerLevel level, BlockPos pos, CompoundTag snapshot) {
        var saved = WirelessAeSavedData.get(level.getServer());
        ListTag bindings = new ListTag();
        for (int index = 0; index <= Direction.values().length; index++) {
            Direction side = index == Direction.values().length ? null : Direction.values()[index];
            var member = WirelessAeSavedData.MemberKey.of(level.dimension(), pos, side);
            UUID frequency = saved.getExactMemberNetwork(member);
            if (frequency == null) continue;
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Frequency", frequency);
            if (member.side() != null) entry.putInt("Side", member.side().ordinal());
            var core = saved.getCore(frequency);
            if (core != null) {
                entry.putString("CoreDimension", core.dimension().location().toString());
                entry.putLong("CorePos", core.pos().asLong());
            }
            bindings.add(entry);
        }
        if (!bindings.isEmpty()) snapshot.put("Wireless", bindings);
        if (level.getBlockEntity(pos) instanceof WirelessNetworkCoreBlockEntity core) {
            snapshot.putUUID("CoreFrequency", core.getFrequency());
        }
    }

    /** Each call processes one operation, under the same task budget as block placement. */
    public static boolean finish(ManipulatorTask task, ManipulatorTask.Operation op, ServerPlayer player, int phase) {
        ServerLevel level = player.serverLevel();
        if (!level.hasChunkAt(op.target())) {
            task.status("mm.status.chunks");
            return false;
        }
        var s = task.settings;
        var snapshot = op.snapshot().data();
        var destination = level.getBlockEntity(op.target());
        if (phase == 0) {
            initializeCore(level, op.target(), player);
            if (destination instanceof IPartHost host && s.mode == ManipulatorSettings.Mode.COPY) {
                for (Tag t : snapshot.getList("Parts", Tag.TAG_COMPOUND)) {
                    CompoundTag part = (CompoundTag) t;
                    if (!part.contains("InputGroup") || part.getBoolean("Output")) continue;
                    Direction side = side(part, s);
                    if (!(host.getPart(side) instanceof P2PTunnelPart<?> tunnel) || tunnel.getGridNode() == null) {
                        task.status("mm.status.network");
                        return false;
                    }
                    short frequency = P2PService.get(tunnel.getGridNode().getGrid()).newFrequency();
                    P2PService.get(tunnel.getGridNode().getGrid()).updateFreq(tunnel, frequency);
                    CompoundTag configuration = new CompoundTag();
                    tunnel.exportSettings(SettingsFrom.MEMORY_CARD, configuration);
                    task.p2pBindings.put(group(op, part, task), configuration);
                }
            }
            return true;
        }
        if (destination instanceof IPartHost host && s.mode == ManipulatorSettings.Mode.COPY) {
            for (Tag t : snapshot.getList("Parts", Tag.TAG_COMPOUND)) {
                CompoundTag part = (CompoundTag) t;
                if (!part.contains("InputGroup") || !part.getBoolean("Output")) continue;
                if (host.getPart(side(part, s)) instanceof P2PTunnelPart<?> tunnel) {
                    CompoundTag configuration = task.p2pBindings.getOrDefault(group(op, part, task), part.getCompound("Configuration"));
                    tunnel.importSettings(SettingsFrom.MEMORY_CARD, configuration, player);
                }
            }
        }
        var saved = WirelessAeSavedData.get(level.getServer());
        for (Tag t : snapshot.getList("Wireless", Tag.TAG_COMPOUND)) {
            CompoundTag binding = (CompoundTag) t;
            UUID frequency = binding.getUUID("Frequency");
            BlockPos sourceCore = BlockPos.of(binding.getLong("CorePos"));
            BlockPos relative = sourceCore.subtract(s.min());
            boolean internal = binding.getString("CoreDimension").equals(s.dimension) && inside(relative, s.size());
            if (internal) {
                BlockPos corePos = ManipulatorGeometry.target(relative, array(op, task), s);
                if (!level.hasChunkAt(corePos) || !(level.getBlockEntity(corePos) instanceof WirelessNetworkCoreBlockEntity core)) {
                    task.status("mm.status.network");
                    return false;
                }
                frequency = core.getFrequency();
            } else if (s.mode != ManipulatorSettings.Mode.MOVE) continue;
            Direction side = binding.contains("Side") ? ManipulatorGeometry.direction(Direction.values()[binding.getInt("Side")], s) : null;
            var member = WirelessAeSavedData.MemberKey.of(level.dimension(), op.target(), side);
            saved.addMember(frequency, member);
            WirelessAeNetworkRuntime.connectMemberNow(level.getServer(), frequency, member);
            if (s.mode == ManipulatorSettings.Mode.MOVE) saved.removeMember(binding.getUUID("Frequency"), WirelessAeSavedData.MemberKey.of(level.dimension(), op.source(), binding.contains("Side") ? Direction.values()[binding.getInt("Side")] : null));
        }
        if (s.mode == ManipulatorSettings.Mode.MOVE && snapshot.contains("BufferProxies")) {
            for (long position : snapshot.getLongArray("BufferProxies")) {
                BlockPos pos = BlockPos.of(position);
                BlockPos relative = pos.subtract(s.min());
                if (inside(relative, s.size())) pos = ManipulatorGeometry.target(relative, 0, s);
                if (!level.hasChunkAt(pos)) {
                    task.status("mm.status.chunks");
                    return false;
                }
                if (com.gregtechceu.gtceu.api.machine.MetaMachine.getMachine(level, pos) instanceof org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferProxyPartMachine proxy) proxy.setBuffer(op.target());
            }
        }
        return true;
    }

    private static Direction side(CompoundTag tag, ManipulatorSettings settings) {
        return tag.getInt("Side") == Direction.values().length ? null : ManipulatorGeometry.direction(Direction.values()[tag.getInt("Side")], settings);
    }

    private static String group(ManipulatorTask.Operation op, CompoundTag part, ManipulatorTask task) {
        return array(op, task) + ":" + part.getString("InputGroup");
    }

    private static int array(ManipulatorTask.Operation op, ManipulatorTask task) {
        BlockPos original = ManipulatorGeometry.target(op.source().subtract(task.settings.min()), 0, task.settings);
        BlockPos extent = ManipulatorGeometry.extent(task.settings);
        int x = task.settings.repeat[0] == 1 ? 0 : (op.target().getX() - original.getX()) / (extent.getX() + task.settings.spacing[0]);
        int y = task.settings.repeat[1] == 1 ? 0 : (op.target().getY() - original.getY()) / (extent.getY() + task.settings.spacing[1]);
        int z = task.settings.repeat[2] == 1 ? 0 : (op.target().getZ() - original.getZ()) / (extent.getZ() + task.settings.spacing[2]);
        return x + task.settings.repeat[0] * (y + task.settings.repeat[1] * z);
    }

    private static boolean inside(BlockPos p, BlockPos size) {
        return p.getX() >= 0 && p.getX() < size.getX() && p.getY() >= 0 && p.getY() < size.getY() && p.getZ() >= 0 && p.getZ() < size.getZ();
    }
}
