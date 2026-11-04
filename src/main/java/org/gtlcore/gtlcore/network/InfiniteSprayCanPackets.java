package org.gtlcore.gtlcore.network;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.client.spray.InfiniteSprayCanClient;
import org.gtlcore.gtlcore.common.item.InfiniteSprayCanBehavior;
import org.gtlcore.gtlcore.common.item.InfiniteSprayCanItem;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

public final class InfiniteSprayCanPackets {

    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            GTLCore.id("infinite_spray_can"), () -> VERSION, VERSION::equals, VERSION::equals);

    private InfiniteSprayCanPackets() {}

    public static void register() {
        CHANNEL.registerMessage(0, ToggleMode.class, (packet, buffer) -> {}, buffer -> new ToggleMode(),
                ToggleMode::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Selection.class, Selection::write, Selection::read,
                Selection::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void toggleMode() {
        CHANNEL.sendToServer(new ToggleMode());
    }

    public static void syncSelection(ServerPlayer player, ItemStack stack) {
        BlockPos start = InfiniteSprayCanBehavior.getRangeStart(stack);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new Selection(InfiniteSprayCanBehavior.isRangeMode(stack), start,
                        InfiniteSprayCanBehavior.getRangeDimension(stack)));
    }

    private static ItemStack findSprayCan(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.getItem() instanceof InfiniteSprayCanItem) return mainHand;
        ItemStack offHand = player.getOffhandItem();
        return offHand.getItem() instanceof InfiniteSprayCanItem ? offHand : ItemStack.EMPTY;
    }

    private record ToggleMode() {

        private void handle(Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null) return;
                ItemStack stack = findSprayCan(player);
                if (stack.isEmpty()) return;
                boolean rangeMode = InfiniteSprayCanBehavior.toggleRangeMode(stack);
                player.displayClientMessage(Component.translatable(rangeMode ?
                        "message.gtlcore.infinite_spray_can.mode.range" :
                        "message.gtlcore.infinite_spray_can.mode.single"), true);
                player.getInventory().setChanged();
                syncSelection(player, stack);
            });
            context.setPacketHandled(true);
        }
    }

    public record Selection(boolean rangeMode, BlockPos start, String dimension) {

        private void write(FriendlyByteBuf buffer) {
            buffer.writeBoolean(rangeMode);
            buffer.writeBoolean(start != null);
            if (start != null) {
                buffer.writeBlockPos(start);
                buffer.writeUtf(dimension);
            }
        }

        private static Selection read(FriendlyByteBuf buffer) {
            boolean rangeMode = buffer.readBoolean();
            if (!buffer.readBoolean()) return new Selection(rangeMode, null, "");
            return new Selection(rangeMode, buffer.readBlockPos(), buffer.readUtf());
        }

        private void handle(Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> InfiniteSprayCanClient.receiveSelection(this)));
            context.setPacketHandled(true);
        }
    }
}
