package org.gtlcore.gtlcore.integration.monitor;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.client.monitor.MachineMonitorClient;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

public final class MachineMonitorPackets {

    // The reference layout has room for nine ten-unit rows below its input/output headings.
    public static final int ROWS_PER_PAGE = 9;
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            GTLCore.id("machine_monitor"), () -> VERSION, VERSION::equals, VERSION::equals);

    private MachineMonitorPackets() {}

    public static void register() {
        CHANNEL.registerMessage(0, Snapshot.class, Snapshot::write, Snapshot::read, Snapshot::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, Action.class, Action::write, Action::read, Action::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    public static void send(ServerPlayer player, ResourceLocation dimension, CompoundTag data, boolean open) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Snapshot(dimension, data, open));
    }

    public static void action(BlockPos clicked, int page, boolean unlink) {
        CHANNEL.sendToServer(new Action(clicked, page, unlink));
    }

    private record Snapshot(ResourceLocation dimension, CompoundTag data, boolean open) {

        private void write(FriendlyByteBuf buffer) {
            buffer.writeResourceLocation(dimension);
            buffer.writeNbt(data);
            buffer.writeBoolean(open);
        }

        private static Snapshot read(FriendlyByteBuf buffer) {
            return new Snapshot(buffer.readResourceLocation(), buffer.readNbt(), buffer.readBoolean());
        }

        private void handle(Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MachineMonitorClient.receive(dimension, data, open)));
            context.setPacketHandled(true);
        }
    }

    private record Action(BlockPos clicked, int page, boolean unlink) {

        private void write(FriendlyByteBuf buffer) {
            buffer.writeBlockPos(clicked);
            buffer.writeVarInt(page);
            buffer.writeBoolean(unlink);
        }

        private static Action read(FriendlyByteBuf buffer) {
            return new Action(buffer.readBlockPos(), buffer.readVarInt(), buffer.readBoolean());
        }

        private void handle(Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                var player = context.getSender();
                if (player != null) MachineMonitorRuntime.get(player.serverLevel()).action(player, clicked, page, unlink);
            });
            context.setPacketHandled(true);
        }
    }
}
