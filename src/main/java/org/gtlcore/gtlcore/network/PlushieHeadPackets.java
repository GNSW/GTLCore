package org.gtlcore.gtlcore.network;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.client.plushie.PlushieHeadClient;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/** Keeps the head plushie bounce and its optional BGM in sync with nearby clients. */
public final class PlushieHeadPackets {

    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            GTLCore.id("plushie_head"), () -> VERSION, VERSION::equals, VERSION::equals);

    private PlushieHeadPackets() {}

    public static void register() {
        CHANNEL.registerMessage(0, JumpState.class, JumpState::write, JumpState::read,
                JumpState::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void send(ServerPlayer player, boolean active, boolean bgm) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new JumpState(player.getId(), active, bgm));
    }

    public static void sendTo(ServerPlayer player, ServerPlayer receiver, boolean active, boolean bgm) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> receiver),
                new JumpState(player.getId(), active, bgm));
    }

    private record JumpState(int entityId, boolean active, boolean bgm) {

        private void write(FriendlyByteBuf buffer) {
            buffer.writeVarInt(entityId);
            buffer.writeBoolean(active);
            buffer.writeBoolean(bgm);
        }

        private static JumpState read(FriendlyByteBuf buffer) {
            return new JumpState(buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean());
        }

        private void handle(Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> PlushieHeadClient.receive(entityId, active, bgm)));
            context.setPacketHandled(true);
        }
    }
}
