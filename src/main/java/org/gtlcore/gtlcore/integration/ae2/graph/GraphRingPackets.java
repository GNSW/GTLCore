package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAePackets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import appeng.hooks.ticking.TickHandler;
import appeng.menu.me.crafting.CraftConfirmMenu;

import java.util.*;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public final class GraphRingPackets {

    private static final Map<ServerPlayer, long[]> RATES = new WeakHashMap<>();

    private GraphRingPackets() {}

    public static void register(SimpleChannel channel, IntSupplier ids) {
        channel.registerMessage(ids.getAsInt(), Request.class, Request::write, Request::read, Request::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(ids.getAsInt(), Response.class, Response::write, Response::read, Response::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        channel.registerMessage(ids.getAsInt(), Failure.class, Failure::write, Failure::read, Failure::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public record Request(int container, UUID plan, int offset) {

        private void write(FriendlyByteBuf buffer) {
            buffer.writeVarInt(container);
            buffer.writeUUID(plan);
            buffer.writeVarInt(offset);
        }

        private static Request read(FriendlyByteBuf buffer) {
            return new Request(buffer.readVarInt(), buffer.readUUID(), buffer.readVarInt());
        }

        private void handle(Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                var player = context.getSender();
                if (player == null || !(player.containerMenu instanceof CraftConfirmMenu menu) || menu.containerId != container || !menu.isValidMenu()) return;
                var selected = ((GraphPlanMenu) menu).gtlcore$graphPlan();
                if (selected == null || !selected.id().equals(plan)) {
                    failure(player, "PLAN_CHANGED");
                    return;
                }
                if (offset < 0 || offset > GraphRingView.MAX_ROWS) return;
                long tick = TickHandler.instance().getCurrentTick();
                long[] rate = RATES.computeIfAbsent(player, ignored -> new long[2]);
                if (rate[0] != tick) {
                    rate[0] = tick;
                    rate[1] = 0;
                }
                if (++rate[1] > 2) return;
                long start = System.nanoTime();
                var server = player.getServer();
                if (server == null) return;
                selected.displayAsync().whenComplete((view, error) -> server.execute(() -> {
                    if (player.containerMenu != menu || !menu.isValidMenu()) return;
                    if (((GraphPlanMenu) menu).gtlcore$graphPlan() != selected) {
                        failure(player, "PLAN_CHANGED");
                        return;
                    }
                    if (error != null) {
                        failure(player, "VIEW_LIMIT");
                        GTLCore.LOGGER.warn("Crafting Ring view {} could not be built", plan, error);
                        return;
                    }
                    GraphRingView.Page page;
                    try {
                        page = view.page(offset);
                    } catch (IllegalArgumentException e) {
                        failure(player, "INVALID_PAGE");
                        return;
                    }
                    if (offset == 0 && ConfigHolder.INSTANCE.ae2GraphDiagnosticLogging)
                        GTLCore.LOGGER.info("[Graph Crafting] view={} rows={} first_page_ms={}", plan, page.total(), (System.nanoTime() - start) / 1_000_000.0);
                    WirelessAePackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Response(container, page));
                }));
            });
            context.setPacketHandled(true);
        }

        private void failure(ServerPlayer player, String reason) {
            WirelessAePackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Failure(container, plan, reason));
        }
    }

    public record Failure(int container, UUID plan, String reason) {

        private void write(FriendlyByteBuf buffer) {
            buffer.writeVarInt(container);
            buffer.writeUUID(plan);
            buffer.writeUtf(reason, 64);
        }

        private static Failure read(FriendlyByteBuf buffer) {
            return new Failure(buffer.readVarInt(), buffer.readUUID(), buffer.readUtf(64));
        }

        private void handle(Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> org.gtlcore.gtlcore.client.ae2.graph.CraftingRingScreen.fail(this)));
            context.setPacketHandled(true);
        }
    }

    public record Response(int container, GraphRingView.Page page) {

        private void write(FriendlyByteBuf buffer) {
            buffer.writeVarInt(container);
            GraphRingView.write(page, buffer);
        }

        private static Response read(FriendlyByteBuf buffer) {
            return new Response(buffer.readVarInt(), GraphRingView.read(buffer));
        }

        private void handle(Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> org.gtlcore.gtlcore.client.ae2.graph.CraftingRingScreen.receive(this)));
            context.setPacketHandled(true);
        }
    }
}
