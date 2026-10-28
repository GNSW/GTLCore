package org.gtlcore.gtlcore.network;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.item.MatterManipulatorBehavior;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

public final class ManipulatorPackets {

    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(GTLCore.id("manipulator"), () -> VERSION, VERSION::equals, VERSION::equals);

    private ManipulatorPackets() {}

    public static void register() {
        CHANNEL.registerMessage(0, Intent.class, Intent::write, Intent::read, Intent::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    public static void send(MatterManipulatorBehavior.Action action) {
        CHANNEL.sendToServer(new Intent(action));
    }

    private record Intent(MatterManipulatorBehavior.Action action) {

        private void write(FriendlyByteBuf buf) {
            buf.writeEnum(action);
        }

        private static Intent read(FriendlyByteBuf buf) {
            return new Intent(buf.readEnum(MatterManipulatorBehavior.Action.class));
        }

        private void handle(Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                var player = context.getSender();
                if (player == null) return;
                ItemStack stack = player.getMainHandItem();
                if (!(stack.getItem() instanceof MatterManipulatorBehavior)) stack = player.getOffhandItem();
                MatterManipulatorBehavior.action(player, stack, action, -1);
            });
            context.setPacketHandled(true);
        }
    }
}
