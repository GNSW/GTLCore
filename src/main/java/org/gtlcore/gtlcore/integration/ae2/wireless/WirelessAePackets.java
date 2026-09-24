package org.gtlcore.gtlcore.integration.ae2.wireless;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.client.gui.PatterEncodingTermMenuModify;
import org.gtlcore.gtlcore.integration.ae2.chamber.MEChamberConfigurator;
import org.gtlcore.gtlcore.integration.ae2.chamber.MEChamberManagerTerminalMenu;
import org.gtlcore.gtlcore.integration.ae2.emitter.EmitterManagerTerminalMenu;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadSelectionMenu;
import org.gtlcore.gtlcore.integration.ae2.throughput.ThroughputMonitorTerminalMenu;
import org.gtlcore.gtlcore.integration.ae2.throughput.ThroughputMonitorUpdateInterval;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import appeng.api.stacks.AEKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class WirelessAePackets {

    private static final String PROTOCOL_VERSION = "26";
    private static final MeInventoryRequestLimiter<ServerPlayer> TARGET_REQUEST_LIMITER = new MeInventoryRequestLimiter<>(8, 20);
    private static int nextPacketId;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(GTLCore.MOD_ID, "wireless_ae"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private WirelessAePackets() {}

    public static void register() {
        registerPacket(UnbindWithToolPacket.class, (packet, buffer) -> buffer.writeBlockPos(packet.pos()),
                buffer -> new UnbindWithToolPacket(buffer.readBlockPos()), UnbindWithToolPacket::handle, NetworkDirection.PLAY_TO_SERVER);
        registerPacket(ClearBindingToolPacket.class, (packet, buffer) -> {}, buffer -> new ClearBindingToolPacket(),
                ClearBindingToolPacket::handle, NetworkDirection.PLAY_TO_SERVER);
        registerPacket(RenameNetworkPacket.class, RenameNetworkPacket::encode, RenameNetworkPacket::decode,
                RenameNetworkPacket::handle, NetworkDirection.PLAY_TO_SERVER);
        registerPacket(SetFavoriteNetworkPacket.class, SetFavoriteNetworkPacket::encode, SetFavoriteNetworkPacket::decode,
                SetFavoriteNetworkPacket::handle, NetworkDirection.PLAY_TO_SERVER);
        registerPacket(ConnectTargetPacket.class, ConnectTargetPacket::encode, ConnectTargetPacket::decode,
                ConnectTargetPacket::handle, NetworkDirection.PLAY_TO_SERVER);
        registerPacket(OpenTargetMenuPacket.class, OpenTargetMenuPacket::encode, OpenTargetMenuPacket::decode,
                OpenTargetMenuPacket::handle, NetworkDirection.PLAY_TO_SERVER);
        registerPacket(OpenNormalTargetMenuPacket.class, OpenNormalTargetMenuPacket::encode,
                OpenNormalTargetMenuPacket::decode, OpenNormalTargetMenuPacket::handle, NetworkDirection.PLAY_TO_SERVER);
        registerPacket(RequestTargetNetworksPacket.class, RequestTargetNetworksPacket::encode,
                RequestTargetNetworksPacket::decode, RequestTargetNetworksPacket::handle, NetworkDirection.PLAY_TO_SERVER);
        registerPacket(SyncTargetNetworksPacket.class, SyncTargetNetworksPacket::encode, SyncTargetNetworksPacket::decode,
                SyncTargetNetworksPacket::handle, NetworkDirection.PLAY_TO_CLIENT);
        registerPacket(OpenPatternQuickUploadSelectionPacket.class, OpenPatternQuickUploadSelectionPacket::encode,
                OpenPatternQuickUploadSelectionPacket::decode, OpenPatternQuickUploadSelectionPacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
        registerPacket(SelectPatternQuickUploadTargetPacket.class, SelectPatternQuickUploadTargetPacket::encode,
                SelectPatternQuickUploadTargetPacket::decode, SelectPatternQuickUploadTargetPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        registerPacket(PatternQuickUploadDuplicatePacket.class, PatternQuickUploadDuplicatePacket::encode,
                PatternQuickUploadDuplicatePacket::decode, PatternQuickUploadDuplicatePacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
        registerPacket(SyncThroughputMonitorTerminalPacket.class, SyncThroughputMonitorTerminalPacket::encode,
                SyncThroughputMonitorTerminalPacket::decode, SyncThroughputMonitorTerminalPacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
        registerPacket(SetThroughputMonitorSourceTrackingPacket.class, SetThroughputMonitorSourceTrackingPacket::encode,
                SetThroughputMonitorSourceTrackingPacket::decode, SetThroughputMonitorSourceTrackingPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        registerPacket(SetThroughputMonitorUpdateIntervalPacket.class, SetThroughputMonitorUpdateIntervalPacket::encode,
                SetThroughputMonitorUpdateIntervalPacket::decode, SetThroughputMonitorUpdateIntervalPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        registerPacket(SyncThroughputMonitorSourcesPacket.class, SyncThroughputMonitorSourcesPacket::encode,
                SyncThroughputMonitorSourcesPacket::decode, SyncThroughputMonitorSourcesPacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
        registerPacket(SyncEmitterManagerTerminalPacket.class, SyncEmitterManagerTerminalPacket::encode,
                SyncEmitterManagerTerminalPacket::decode, SyncEmitterManagerTerminalPacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
        registerPacket(SetEmitterSettingPacket.class, SetEmitterSettingPacket::encode, SetEmitterSettingPacket::decode,
                SetEmitterSettingPacket::handle, NetworkDirection.PLAY_TO_SERVER);
        registerPacket(SetEmitterValuePacket.class, SetEmitterValuePacket::encode, SetEmitterValuePacket::decode,
                SetEmitterValuePacket::handle, NetworkDirection.PLAY_TO_SERVER);
        registerPacket(SelectEmitterPacket.class, SelectEmitterPacket::encode, SelectEmitterPacket::decode,
                SelectEmitterPacket::handle, NetworkDirection.PLAY_TO_SERVER);
        registerPacket(SyncMEChamberManagerEntriesPacket.class, SyncMEChamberManagerEntriesPacket::encode,
                SyncMEChamberManagerEntriesPacket::decode, SyncMEChamberManagerEntriesPacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
        registerPacket(SelectMEChamberPacket.class, SelectMEChamberPacket::encode, SelectMEChamberPacket::decode,
                SelectMEChamberPacket::handle, NetworkDirection.PLAY_TO_SERVER);
        registerPacket(SetMEChamberSlotAmountPacket.class, SetMEChamberSlotAmountPacket::encode,
                SetMEChamberSlotAmountPacket::decode, SetMEChamberSlotAmountPacket::handle, NetworkDirection.PLAY_TO_SERVER);
        registerPacket(SetMEChamberSlotConfigPacket.class, SetMEChamberSlotConfigPacket::encode,
                SetMEChamberSlotConfigPacket::decode, SetMEChamberSlotConfigPacket::handle, NetworkDirection.PLAY_TO_SERVER);
        registerPacket(SetMEChamberControlPacket.class, SetMEChamberControlPacket::encode,
                SetMEChamberControlPacket::decode, SetMEChamberControlPacket::handle, NetworkDirection.PLAY_TO_SERVER);
        registerPacket(MEChamberConfiguratorActionPacket.class, MEChamberConfiguratorActionPacket::encode,
                MEChamberConfiguratorActionPacket::decode, MEChamberConfiguratorActionPacket::handle,
                NetworkDirection.PLAY_TO_SERVER);
        registerPacket(SyncMEChamberManagerContentsPacket.class, SyncMEChamberManagerContentsPacket::encode,
                SyncMEChamberManagerContentsPacket::decode, SyncMEChamberManagerContentsPacket::handle,
                NetworkDirection.PLAY_TO_CLIENT);
        registerPacket(SyncMEChamberSnapshotPacket.class, SyncMEChamberSnapshotPacket::encode,
                SyncMEChamberSnapshotPacket::decode, SyncMEChamberSnapshotPacket::handle, NetworkDirection.PLAY_TO_CLIENT);
        MeInventoryAmountPackets.register(CHANNEL, () -> nextPacketId++);
        FastCellDisplayPackets.register(CHANNEL, () -> nextPacketId++);
        org.gtlcore.gtlcore.integration.ae2.graph.GraphRingPackets.register(CHANNEL, () -> nextPacketId++);
        JeiWirelessTerminalOrderPackets.register(CHANNEL, () -> nextPacketId++);
        JeiPatternQuery.register(CHANNEL, () -> nextPacketId++);
    }

    public record ClearBindingToolPacket() {

        private static void handle(ClearBindingToolPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            var context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null && !player.isSpectator()) {
                    WirelessNetworkBindingToolItem.clearSavedNetwork(player);
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record UnbindWithToolPacket(BlockPos pos) {

        private static void handle(UnbindWithToolPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            var context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null && player.canReach(packet.pos(), 0)) {
                    WirelessAeNetworkRuntime.unbindWithTool(player, packet.pos());
                }
            });
            context.setPacketHandled(true);
        }
    }

    private static <P> void registerPacket(Class<P> packetType, BiConsumer<P, FriendlyByteBuf> encoder,
                                           Function<FriendlyByteBuf, P> decoder,
                                           BiConsumer<P, Supplier<NetworkEvent.Context>> handler,
                                           NetworkDirection direction) {
        CHANNEL.registerMessage(nextPacketId++, packetType, encoder, decoder, handler, Optional.of(direction));
    }

    private static void dispatchClient(NetworkEvent.Context context, Runnable handler) {
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> handler));
        context.setPacketHandled(true);
    }

    public record RenameNetworkPacket(BlockPos corePos, String name) {

        private static void encode(RenameNetworkPacket packet, FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.corePos);
            buffer.writeUtf(packet.name);
        }

        private static RenameNetworkPacket decode(FriendlyByteBuf buffer) {
            return new RenameNetworkPacket(buffer.readBlockPos(), buffer.readUtf(32));
        }

        private static void handle(RenameNetworkPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || isCloseEnough(player, packet.corePos)) {
                    return;
                }

                ServerLevel level = player.serverLevel();
                BlockEntity blockEntity = level.getBlockEntity(packet.corePos);
                if (!(blockEntity instanceof WirelessNetworkCoreBlockEntity core)) {
                    return;
                }

                WirelessAeSavedData data = WirelessAeSavedData.get(level.getServer());
                UUID frequency = core.getFrequency();
                if (!WirelessAeNetworkRuntime.canAccessNetwork(player, frequency)) {
                    return;
                }
                data.setCore(frequency, GlobalPos.of(level.dimension(), packet.corePos));
                data.setNetworkName(frequency, packet.name);
                WirelessAeNetworkRuntime.requestReconnect(frequency);
                player.displayClientMessage(
                        Component.translatable("message.gtlcore.wireless_core.name_saved", data.getNetworkName(frequency)),
                        true);
            });
            context.setPacketHandled(true);
        }
    }

    public record SetFavoriteNetworkPacket(BlockPos bookmarkPos, UUID frequency) {

        private static void encode(SetFavoriteNetworkPacket packet, FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.bookmarkPos);
            buffer.writeUUID(packet.frequency);
        }

        private static SetFavoriteNetworkPacket decode(FriendlyByteBuf buffer) {
            return new SetFavoriteNetworkPacket(buffer.readBlockPos(), buffer.readUUID());
        }

        private static void handle(SetFavoriteNetworkPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || isCloseEnough(player, packet.bookmarkPos)) {
                    return;
                }

                ServerLevel level = player.serverLevel();
                if (!(level.getBlockEntity(packet.bookmarkPos) instanceof WirelessNetworkBookmarkBlockEntity)) {
                    return;
                }

                WirelessAeSavedData data = WirelessAeSavedData.get(level.getServer());
                if (!data.getFrequencies().contains(packet.frequency) || !WirelessAeNetworkRuntime.canAccessNetwork(player, packet.frequency)) {
                    return;
                }
                boolean removeFavorite = packet.frequency.equals(data.getFavoriteNetwork(player));
                String networkName = data.getNetworkName(packet.frequency);
                data.setFavoriteNetwork(player, removeFavorite ? null : packet.frequency);
                player.displayClientMessage(
                        Component.translatable(
                                removeFavorite ?
                                        "message.gtlcore.wireless_bookmark.favorite_removed" :
                                        "message.gtlcore.wireless_bookmark.saved",
                                networkName),
                        true);
            });
            context.setPacketHandled(true);
        }
    }

    public record ConnectTargetPacket(BlockPos targetPos, Direction targetSide, Vec3 hitLocation,
                                      UUID frequency, boolean disconnect) {

        public ConnectTargetPacket(BlockPos targetPos, UUID frequency, boolean disconnect) {
            this(targetPos, null, null, frequency, disconnect);
        }

        private static void encode(ConnectTargetPacket packet, FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.targetPos);
            writeDirection(buffer, packet.targetSide);
            writeVec3(buffer, packet.hitLocation);
            buffer.writeUUID(packet.frequency);
            buffer.writeBoolean(packet.disconnect);
        }

        private static ConnectTargetPacket decode(FriendlyByteBuf buffer) {
            return new ConnectTargetPacket(
                    buffer.readBlockPos(),
                    readDirection(buffer),
                    readVec3(buffer),
                    buffer.readUUID(),
                    buffer.readBoolean());
        }

        private static void handle(ConnectTargetPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || isCloseEnough(player, packet.targetPos)) {
                    return;
                }
                if (!TARGET_REQUEST_LIMITER.tryAcquire(player, player.serverLevel().getGameTime())) return;

                ServerLevel level = player.serverLevel();
                WirelessAeSavedData.MemberKey target = WirelessAeNetworkRuntime.resolveWirelessTarget(
                        level,
                        packet.targetPos,
                        packet.targetSide,
                        packet.hitLocation);
                BlockPos targetPos = target.blockPos();
                GlobalPos targetGlobalPos = target.pos();
                WirelessAeSavedData data = WirelessAeSavedData.get(level.getServer());
                if (!WirelessAeNetworkRuntime.canAccessNetwork(player, packet.frequency)) {
                    return;
                }
                UUID currentNetwork = data.getMemberNetwork(target);
                UUID wiredNetwork = WirelessAeNetworkRuntime.findWiredNetworkFrequency(level.getServer(), target);
                UUID connectedNetwork = wiredNetwork == null ? WirelessAeNetworkRuntime.findConnectedNetworkFrequency(level.getServer(), target) : wiredNetwork;
                boolean canModifyCurrentConnection = wiredNetwork == null && connectedNetwork != null && connectedNetwork.equals(currentNetwork) && WirelessAeNetworkRuntime.hasWirelessConnection(connectedNetwork, target);

                if (packet.disconnect) {
                    if (!canModifyCurrentConnection || !packet.frequency.equals(currentNetwork)) {
                        return;
                    }
                    for (UUID removedNetwork : data.removeMembersAt(targetGlobalPos)) {
                        WirelessAeNetworkRuntime.disconnectMembersAt(removedNetwork, targetGlobalPos);
                    }
                    player.displayClientMessage(
                            Component.translatable("message.gtlcore.wireless_target.disconnected"),
                            true);
                    return;
                }

                if (connectedNetwork != null && !canModifyCurrentConnection) {
                    return;
                }

                if (!WirelessAeNetworkRuntime.canBindAsWirelessTarget(level, targetPos, target.side())) {
                    player.displayClientMessage(
                            Component.translatable("message.gtlcore.wireless_target.invalid_target"),
                            true);
                    return;
                }

                WirelessNetworkCoreBlockEntity core = WirelessAeNetworkRuntime.getLoadedCore(
                        level.getServer(),
                        packet.frequency);
                if (core == null) {
                    player.displayClientMessage(
                            Component.translatable("message.gtlcore.wireless_target.missing_core"),
                            true);
                    return;
                }
                if (!core.isLinkedToAeNetwork()) {
                    player.displayClientMessage(
                            Component.translatable("message.gtlcore.wireless_target.core_not_connected"),
                            true);
                    return;
                }

                for (UUID removedNetwork : data.removeMembersAt(targetGlobalPos)) {
                    WirelessAeNetworkRuntime.disconnectMembersAt(removedNetwork, targetGlobalPos);
                }

                WirelessAeNetworkRuntime.ConnectionResult result = WirelessAeNetworkRuntime.connectMemberNow(
                        level.getServer(),
                        packet.frequency,
                        target);
                data.addMember(packet.frequency, target);
                WirelessAeNetworkRuntime.requestReconnect(packet.frequency);

                boolean pending = result == WirelessAeNetworkRuntime.ConnectionResult.TARGET_MISSING || result == WirelessAeNetworkRuntime.ConnectionResult.FAILED;
                player.displayClientMessage(
                        Component.translatable(
                                pending ? "message.gtlcore.wireless_target.linked_target_pending" : "message.gtlcore.wireless_target.linked_target",
                                data.getNetworkName(packet.frequency)),
                        true);
            });
            context.setPacketHandled(true);
        }
    }

    public record OpenTargetMenuPacket(BlockPos targetPos, Direction targetSide, Vec3 hitLocation) {

        public OpenTargetMenuPacket(BlockPos targetPos) {
            this(targetPos, null, null);
        }

        private static void encode(OpenTargetMenuPacket packet, FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.targetPos);
            writeDirection(buffer, packet.targetSide);
            writeVec3(buffer, packet.hitLocation);
        }

        private static OpenTargetMenuPacket decode(FriendlyByteBuf buffer) {
            return new OpenTargetMenuPacket(buffer.readBlockPos(), readDirection(buffer), readVec3(buffer));
        }

        private static void handle(OpenTargetMenuPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || isCloseEnough(player, packet.targetPos)) {
                    return;
                }
                if (!TARGET_REQUEST_LIMITER.tryAcquire(player, player.serverLevel().getGameTime())) return;

                ServerLevel level = player.serverLevel();
                WirelessAeSavedData.MemberKey target = WirelessAeNetworkRuntime.resolveWirelessTarget(
                        level,
                        packet.targetPos,
                        packet.targetSide,
                        packet.hitLocation);
                if (WirelessAeNetworkRuntime.canBindAsWirelessTarget(level, target.blockPos(), target.side())) {
                    WirelessAeTargetMenu.open(player, level, packet.targetPos, packet.targetSide, packet.hitLocation);
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record OpenNormalTargetMenuPacket(BlockPos targetPos) {

        private static void encode(OpenNormalTargetMenuPacket packet, FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.targetPos);
        }

        private static OpenNormalTargetMenuPacket decode(FriendlyByteBuf buffer) {
            return new OpenNormalTargetMenuPacket(buffer.readBlockPos());
        }

        private static void handle(OpenNormalTargetMenuPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || isCloseEnough(player, packet.targetPos)) {
                    return;
                }
                ServerLevel level = player.serverLevel();
                if (!level.hasChunkAt(packet.targetPos)) {
                    return;
                }

                BlockState state = level.getBlockState(packet.targetPos);
                BlockHitResult hit = new BlockHitResult(
                        Vec3.atCenterOf(packet.targetPos),
                        Direction.UP,
                        packet.targetPos,
                        false);
                InteractionResult result = state.use(level, player, InteractionHand.MAIN_HAND, hit);
                if (result.consumesAction()) {
                    return;
                }

                MenuProvider provider = state.getMenuProvider(level, packet.targetPos);
                if (provider != null) {
                    player.openMenu(provider);
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record RequestTargetNetworksPacket(BlockPos targetPos, Direction targetSide, Vec3 hitLocation) {

        public RequestTargetNetworksPacket(BlockPos targetPos) {
            this(targetPos, null, null);
        }

        private static void encode(RequestTargetNetworksPacket packet, FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.targetPos);
            writeDirection(buffer, packet.targetSide);
            writeVec3(buffer, packet.hitLocation);
        }

        private static RequestTargetNetworksPacket decode(FriendlyByteBuf buffer) {
            return new RequestTargetNetworksPacket(buffer.readBlockPos(), readDirection(buffer), readVec3(buffer));
        }

        private static void handle(RequestTargetNetworksPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || isCloseEnough(player, packet.targetPos)) {
                    return;
                }
                if (!TARGET_REQUEST_LIMITER.tryAcquire(player, player.serverLevel().getGameTime())) return;

                ServerLevel level = player.serverLevel();
                WirelessAeSavedData.MemberKey target = WirelessAeNetworkRuntime.resolveWirelessTarget(
                        level,
                        packet.targetPos,
                        packet.targetSide,
                        packet.hitLocation);
                if (!WirelessAeNetworkRuntime.canBindAsWirelessTarget(level, target.blockPos(), target.side())) {
                    return;
                }

                CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new SyncTargetNetworksPacket(packet.targetPos, buildTargetEntries(player, target)));
            });
            context.setPacketHandled(true);
        }
    }

    public record SyncTargetNetworksPacket(BlockPos targetPos, List<TargetNetworkEntry> entries) {

        private static void encode(SyncTargetNetworksPacket packet, FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.targetPos);
            buffer.writeVarInt(packet.entries.size());
            for (TargetNetworkEntry entry : packet.entries) {
                buffer.writeUUID(entry.frequency());
                buffer.writeUtf(entry.name());
                buffer.writeBoolean(entry.connected());
                buffer.writeBoolean(entry.disconnectable());
            }
        }

        private static SyncTargetNetworksPacket decode(FriendlyByteBuf buffer) {
            BlockPos targetPos = buffer.readBlockPos();
            int size = buffer.readVarInt();
            List<TargetNetworkEntry> entries = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                entries.add(new TargetNetworkEntry(
                        buffer.readUUID(),
                        buffer.readUtf(32),
                        buffer.readBoolean(),
                        buffer.readBoolean()));
            }
            return new SyncTargetNetworksPacket(targetPos, entries);
        }

        private static void handle(SyncTargetNetworksPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            dispatchClient(context, () -> org.gtlcore.gtlcore.client.ae2.wireless.WirelessAeClientPacketHandler
                    .handleTargetNetworks(packet));
        }
    }

    public record TargetNetworkEntry(UUID frequency, String name, boolean connected, boolean disconnectable) {}

    public record OpenPatternQuickUploadSelectionPacket(ItemStack patternStack,
                                                        List<PatternQuickUploadSelectionMenu.Entry> entries) {

        private static void encode(OpenPatternQuickUploadSelectionPacket packet, FriendlyByteBuf buffer) {
            buffer.writeItem(packet.patternStack);
            writePatternQuickUploadEntries(buffer, packet.entries);
        }

        private static OpenPatternQuickUploadSelectionPacket decode(FriendlyByteBuf buffer) {
            return new OpenPatternQuickUploadSelectionPacket(buffer.readItem(), readPatternQuickUploadEntries(buffer));
        }

        private static void handle(OpenPatternQuickUploadSelectionPacket packet,
                                   Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            dispatchClient(context, () -> org.gtlcore.gtlcore.client.ae2.wireless.WirelessAeClientPacketHandler
                    .handlePatternQuickUploadSelection(packet));
        }
    }

    public record SelectPatternQuickUploadTargetPacket(int index) {

        private static void encode(SelectPatternQuickUploadTargetPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.index);
        }

        private static SelectPatternQuickUploadTargetPacket decode(FriendlyByteBuf buffer) {
            return new SelectPatternQuickUploadTargetPacket(buffer.readVarInt());
        }

        private static void handle(SelectPatternQuickUploadTargetPacket packet,
                                   Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null && player.containerMenu instanceof PatternQuickUploadSelectionMenu menu) {
                    menu.select(player, packet.index);
                } else if (player != null && player.containerMenu instanceof PatterEncodingTermMenuModify menuModify) {
                    menuModify.gTLCore$selectQuickUploadTarget(packet.index);
                }
            });
            context.setPacketHandled(true);
        }
    }

    public static void sendPatternQuickUploadDuplicate(ServerPlayer player, Component targetName) {
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PatternQuickUploadDuplicatePacket(targetName));
    }

    public record PatternQuickUploadDuplicatePacket(Component targetName) {

        private static void encode(PatternQuickUploadDuplicatePacket packet, FriendlyByteBuf buffer) {
            buffer.writeComponent(packet.targetName);
        }

        private static PatternQuickUploadDuplicatePacket decode(FriendlyByteBuf buffer) {
            return new PatternQuickUploadDuplicatePacket(buffer.readComponent());
        }

        private static void handle(PatternQuickUploadDuplicatePacket packet,
                                   Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            dispatchClient(context, () -> org.gtlcore.gtlcore.client.ae2.wireless.WirelessAeClientPacketHandler
                    .handlePatternQuickUploadDuplicate(packet));
        }
    }

    public record SyncThroughputMonitorTerminalPacket(
                                                      int containerId,
                                                      List<ThroughputMonitorTerminalMenu.Entry> entries) {

        private static void encode(SyncThroughputMonitorTerminalPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.containerId);
            ThroughputMonitorTerminalMenu.writeEntries(buffer, packet.entries);
        }

        private static SyncThroughputMonitorTerminalPacket decode(FriendlyByteBuf buffer) {
            return new SyncThroughputMonitorTerminalPacket(
                    buffer.readVarInt(),
                    ThroughputMonitorTerminalMenu.readEntries(buffer));
        }

        private static void handle(SyncThroughputMonitorTerminalPacket packet,
                                   Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            dispatchClient(context, () -> org.gtlcore.gtlcore.client.ae2.wireless.WirelessAeClientPacketHandler
                    .handleThroughputMonitorTerminal(packet));
        }
    }

    public record SetThroughputMonitorSourceTrackingPacket(int containerId, AEKey key, boolean track) {

        private static void encode(SetThroughputMonitorSourceTrackingPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.containerId);
            AEKey.writeKey(buffer, packet.key);
            buffer.writeBoolean(packet.track);
        }

        private static SetThroughputMonitorSourceTrackingPacket decode(FriendlyByteBuf buffer) {
            return new SetThroughputMonitorSourceTrackingPacket(
                    buffer.readVarInt(),
                    AEKey.readKey(buffer),
                    buffer.readBoolean());
        }

        private static void handle(SetThroughputMonitorSourceTrackingPacket packet,
                                   Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null && player.containerMenu instanceof ThroughputMonitorTerminalMenu menu &&
                        menu.containerId == packet.containerId) {
                    menu.trackSources(player, packet.key, packet.track);
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record SetThroughputMonitorUpdateIntervalPacket(
                                                           int containerId,
                                                           ThroughputMonitorUpdateInterval updateInterval) {

        private static void encode(SetThroughputMonitorUpdateIntervalPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.containerId);
            buffer.writeEnum(packet.updateInterval);
        }

        private static SetThroughputMonitorUpdateIntervalPacket decode(FriendlyByteBuf buffer) {
            return new SetThroughputMonitorUpdateIntervalPacket(
                    buffer.readVarInt(),
                    buffer.readEnum(ThroughputMonitorUpdateInterval.class));
        }

        private static void handle(SetThroughputMonitorUpdateIntervalPacket packet,
                                   Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null && player.containerMenu instanceof ThroughputMonitorTerminalMenu menu &&
                        menu.containerId == packet.containerId) {
                    menu.setUpdateInterval(player, packet.updateInterval);
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record SyncThroughputMonitorSourcesPacket(
                                                     int containerId,
                                                     AEKey key,
                                                     List<ThroughputMonitorTerminalMenu.SourceEntry> sources) {

        private static void encode(SyncThroughputMonitorSourcesPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.containerId);
            AEKey.writeKey(buffer, packet.key);
            ThroughputMonitorTerminalMenu.writeSourceEntries(buffer, packet.sources);
        }

        private static SyncThroughputMonitorSourcesPacket decode(FriendlyByteBuf buffer) {
            return new SyncThroughputMonitorSourcesPacket(
                    buffer.readVarInt(),
                    AEKey.readKey(buffer),
                    ThroughputMonitorTerminalMenu.readSourceEntries(buffer));
        }

        private static void handle(SyncThroughputMonitorSourcesPacket packet,
                                   Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            dispatchClient(context, () -> org.gtlcore.gtlcore.client.ae2.wireless.WirelessAeClientPacketHandler
                    .handleThroughputMonitorSources(packet));
        }
    }

    public record SyncEmitterManagerTerminalPacket(
                                                   int containerId,
                                                   List<EmitterManagerTerminalMenu.Entry> entries) {

        private static void encode(SyncEmitterManagerTerminalPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.containerId);
            EmitterManagerTerminalMenu.writeEntries(buffer, packet.entries);
        }

        private static SyncEmitterManagerTerminalPacket decode(FriendlyByteBuf buffer) {
            return new SyncEmitterManagerTerminalPacket(
                    buffer.readVarInt(),
                    EmitterManagerTerminalMenu.readEntries(buffer));
        }

        private static void handle(SyncEmitterManagerTerminalPacket packet,
                                   Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            dispatchClient(context, () -> org.gtlcore.gtlcore.client.ae2.wireless.WirelessAeClientPacketHandler
                    .handleEmitterManagerTerminal(packet));
        }
    }

    public record SetEmitterSettingPacket(int containerId, EmitterManagerTerminalMenu.Address address,
                                          String settingName, String valueName) {

        private static final int MAX_SETTING_NAME_LENGTH = 64;

        private static void encode(SetEmitterSettingPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.containerId);
            packet.address.write(buffer);
            buffer.writeUtf(packet.settingName, MAX_SETTING_NAME_LENGTH);
            buffer.writeUtf(packet.valueName, MAX_SETTING_NAME_LENGTH);
        }

        private static SetEmitterSettingPacket decode(FriendlyByteBuf buffer) {
            return new SetEmitterSettingPacket(
                    buffer.readVarInt(),
                    EmitterManagerTerminalMenu.Address.read(buffer),
                    buffer.readUtf(MAX_SETTING_NAME_LENGTH),
                    buffer.readUtf(MAX_SETTING_NAME_LENGTH));
        }

        private static void handle(SetEmitterSettingPacket packet,
                                   Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null && player.containerMenu instanceof EmitterManagerTerminalMenu menu &&
                        menu.containerId == packet.containerId) {
                    menu.setEmitterSetting(player, packet.address, packet.settingName, packet.valueName);
                }
            });
            context.setPacketHandled(true);
        }
    }

    /** Tells the server which emitter the terminal has selected, so its upgrade slots back the card slots. */
    public record SelectEmitterPacket(int containerId,
                                      @org.jetbrains.annotations.Nullable EmitterManagerTerminalMenu.Address address) {

        private static void encode(SelectEmitterPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.containerId);
            buffer.writeBoolean(packet.address != null);
            if (packet.address != null) {
                packet.address.write(buffer);
            }
        }

        private static SelectEmitterPacket decode(FriendlyByteBuf buffer) {
            int containerId = buffer.readVarInt();
            return new SelectEmitterPacket(
                    containerId,
                    buffer.readBoolean() ? EmitterManagerTerminalMenu.Address.read(buffer) : null);
        }

        private static void handle(SelectEmitterPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null && player.containerMenu instanceof EmitterManagerTerminalMenu menu &&
                        menu.containerId == packet.containerId) {
                    menu.setSelectedAddress(packet.address);
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record SetEmitterValuePacket(int containerId, EmitterManagerTerminalMenu.Address address,
                                        EmitterManagerTerminalMenu.ValueKind kind, long value) {

        private static void encode(SetEmitterValuePacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.containerId);
            packet.address.write(buffer);
            buffer.writeEnum(packet.kind);
            buffer.writeVarLong(packet.value);
        }

        private static SetEmitterValuePacket decode(FriendlyByteBuf buffer) {
            return new SetEmitterValuePacket(
                    buffer.readVarInt(),
                    EmitterManagerTerminalMenu.Address.read(buffer),
                    buffer.readEnum(EmitterManagerTerminalMenu.ValueKind.class),
                    buffer.readVarLong());
        }

        private static void handle(SetEmitterValuePacket packet,
                                   Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null && player.containerMenu instanceof EmitterManagerTerminalMenu menu &&
                        menu.containerId == packet.containerId) {
                    menu.setEmitterValue(player, packet.address, packet.kind, packet.value);
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record SyncMEChamberManagerEntriesPacket(
                                                    int containerId,
                                                    List<MEChamberManagerTerminalMenu.Entry> entries) {

        private static void encode(SyncMEChamberManagerEntriesPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.containerId);
            MEChamberManagerTerminalMenu.writeEntries(buffer, packet.entries);
        }

        private static SyncMEChamberManagerEntriesPacket decode(FriendlyByteBuf buffer) {
            return new SyncMEChamberManagerEntriesPacket(
                    buffer.readVarInt(),
                    MEChamberManagerTerminalMenu.readEntries(buffer));
        }

        private static void handle(SyncMEChamberManagerEntriesPacket packet,
                                   Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            dispatchClient(context, () -> org.gtlcore.gtlcore.client.ae2.wireless.WirelessAeClientPacketHandler
                    .handleMEChamberManagerEntries(packet));
        }
    }

    public record SelectMEChamberPacket(int containerId, MEChamberManagerTerminalMenu.Address address) {

        private static void encode(SelectMEChamberPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.containerId);
            packet.address.write(buffer);
        }

        private static SelectMEChamberPacket decode(FriendlyByteBuf buffer) {
            return new SelectMEChamberPacket(
                    buffer.readVarInt(),
                    MEChamberManagerTerminalMenu.Address.read(buffer));
        }

        private static void handle(SelectMEChamberPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null && player.containerMenu instanceof MEChamberManagerTerminalMenu menu &&
                        menu.containerId == packet.containerId) {
                    menu.selectChamber(player, packet.address);
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record SetMEChamberSlotAmountPacket(int containerId, MEChamberManagerTerminalMenu.Address address,
                                               MEChamberManagerTerminalMenu.StorageKind storage, int slot,
                                               long amount) {

        private static void encode(SetMEChamberSlotAmountPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.containerId);
            packet.address.write(buffer);
            buffer.writeEnum(packet.storage);
            buffer.writeVarInt(packet.slot);
            buffer.writeVarLong(packet.amount);
        }

        private static SetMEChamberSlotAmountPacket decode(FriendlyByteBuf buffer) {
            return new SetMEChamberSlotAmountPacket(
                    buffer.readVarInt(),
                    MEChamberManagerTerminalMenu.Address.read(buffer),
                    buffer.readEnum(MEChamberManagerTerminalMenu.StorageKind.class),
                    buffer.readVarInt(),
                    buffer.readVarLong());
        }

        private static void handle(SetMEChamberSlotAmountPacket packet,
                                   Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null && player.containerMenu instanceof MEChamberManagerTerminalMenu menu &&
                        menu.containerId == packet.containerId) {
                    menu.setSlotAmount(player, packet.address, packet.storage, packet.slot, packet.amount);
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record SetMEChamberSlotConfigPacket(int containerId, MEChamberManagerTerminalMenu.Address address,
                                               MEChamberManagerTerminalMenu.StorageKind storage, int slot,
                                               @org.jetbrains.annotations.Nullable AEKey key) {

        private static void encode(SetMEChamberSlotConfigPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.containerId);
            packet.address.write(buffer);
            buffer.writeEnum(packet.storage);
            buffer.writeVarInt(packet.slot);
            AEKey.writeOptionalKey(buffer, packet.key);
        }

        private static SetMEChamberSlotConfigPacket decode(FriendlyByteBuf buffer) {
            return new SetMEChamberSlotConfigPacket(
                    buffer.readVarInt(),
                    MEChamberManagerTerminalMenu.Address.read(buffer),
                    buffer.readEnum(MEChamberManagerTerminalMenu.StorageKind.class),
                    buffer.readVarInt(),
                    AEKey.readOptionalKey(buffer));
        }

        private static void handle(SetMEChamberSlotConfigPacket packet,
                                   Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null && player.containerMenu instanceof MEChamberManagerTerminalMenu menu &&
                        menu.containerId == packet.containerId) {
                    menu.setSlotConfig(player, packet.address, packet.storage, packet.slot, packet.key);
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record SetMEChamberControlPacket(int containerId, MEChamberManagerTerminalMenu.Address address,
                                            MEChamberManagerTerminalMenu.ControlKind control, int value) {

        private static void encode(SetMEChamberControlPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.containerId);
            packet.address.write(buffer);
            buffer.writeEnum(packet.control);
            buffer.writeVarInt(packet.value);
        }

        private static SetMEChamberControlPacket decode(FriendlyByteBuf buffer) {
            return new SetMEChamberControlPacket(
                    buffer.readVarInt(),
                    MEChamberManagerTerminalMenu.Address.read(buffer),
                    buffer.readEnum(MEChamberManagerTerminalMenu.ControlKind.class),
                    buffer.readVarInt());
        }

        private static void handle(SetMEChamberControlPacket packet,
                                   Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null && player.containerMenu instanceof MEChamberManagerTerminalMenu menu &&
                        menu.containerId == packet.containerId) {
                    menu.setControl(player, packet.address, packet.control, packet.value);
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record MEChamberConfiguratorActionPacket(int containerId, MEChamberManagerTerminalMenu.Address address,
                                                    MEChamberConfigurator.Kind kind, int actionId,
                                                    byte[] actionData) {

        private static void encode(MEChamberConfiguratorActionPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.containerId);
            packet.address.write(buffer);
            buffer.writeEnum(packet.kind);
            buffer.writeVarInt(packet.actionId);
            buffer.writeByteArray(packet.actionData);
        }

        private static MEChamberConfiguratorActionPacket decode(FriendlyByteBuf buffer) {
            return new MEChamberConfiguratorActionPacket(
                    buffer.readVarInt(),
                    MEChamberManagerTerminalMenu.Address.read(buffer),
                    buffer.readEnum(MEChamberConfigurator.Kind.class),
                    buffer.readVarInt(),
                    buffer.readByteArray(MEChamberConfigurator.MAX_ACTION_BYTES));
        }

        private static void handle(MEChamberConfiguratorActionPacket packet,
                                   Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null && player.containerMenu instanceof MEChamberManagerTerminalMenu menu &&
                        menu.containerId == packet.containerId) {
                    menu.handleConfiguratorAction(
                            player, packet.address, packet.kind, packet.actionId, packet.actionData);
                }
            });
            context.setPacketHandled(true);
        }
    }

    public record SyncMEChamberSnapshotPacket(int containerId, boolean contents, long revision,
                                              int totalBytes, int offset, byte[] data) {

        private static void encode(SyncMEChamberSnapshotPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.containerId);
            buffer.writeBoolean(packet.contents);
            buffer.writeVarLong(packet.revision);
            buffer.writeVarInt(packet.totalBytes);
            buffer.writeVarInt(packet.offset);
            buffer.writeByteArray(packet.data);
        }

        private static SyncMEChamberSnapshotPacket decode(FriendlyByteBuf buffer) {
            return new SyncMEChamberSnapshotPacket(buffer.readVarInt(), buffer.readBoolean(), buffer.readVarLong(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readByteArray(
                            org.gtlcore.gtlcore.integration.ae2.chamber.ChamberSnapshotTransfer.MAX_CHUNK_BYTES));
        }

        private static void handle(SyncMEChamberSnapshotPacket packet, Supplier<NetworkEvent.Context> supplier) {
            dispatchClient(supplier.get(), () -> org.gtlcore.gtlcore.client.ae2.wireless.WirelessAeClientPacketHandler
                    .handleMEChamberSnapshot(packet));
        }
    }

    public record SyncMEChamberManagerContentsPacket(
                                                     int containerId,
                                                     @org.jetbrains.annotations.Nullable MEChamberManagerTerminalMenu.Address address,
                                                     List<MEChamberManagerTerminalMenu.SlotContent> contents,
                                                     MEChamberManagerTerminalMenu.ChamberDetails details) {

        private static void encode(SyncMEChamberManagerContentsPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.containerId);
            buffer.writeBoolean(packet.address != null);
            if (packet.address != null) {
                packet.address.write(buffer);
            }
            MEChamberManagerTerminalMenu.writeContents(buffer, packet.contents);
            MEChamberManagerTerminalMenu.writeDetails(buffer, packet.details);
        }

        private static SyncMEChamberManagerContentsPacket decode(FriendlyByteBuf buffer) {
            int containerId = buffer.readVarInt();
            MEChamberManagerTerminalMenu.Address address = buffer.readBoolean() ?
                    MEChamberManagerTerminalMenu.Address.read(buffer) :
                    null;
            return new SyncMEChamberManagerContentsPacket(
                    containerId,
                    address,
                    MEChamberManagerTerminalMenu.readContents(buffer),
                    MEChamberManagerTerminalMenu.readDetails(buffer));
        }

        private static void handle(SyncMEChamberManagerContentsPacket packet,
                                   Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            dispatchClient(context, () -> org.gtlcore.gtlcore.client.ae2.wireless.WirelessAeClientPacketHandler
                    .handleMEChamberManagerContents(packet));
        }
    }

    private static void writePatternQuickUploadEntries(FriendlyByteBuf buffer,
                                                       List<PatternQuickUploadSelectionMenu.Entry> entries) {
        buffer.writeVarInt(entries.size());
        for (PatternQuickUploadSelectionMenu.Entry entry : entries) {
            buffer.writeResourceLocation(entry.levelKey().location());
            buffer.writeBlockPos(entry.bufferPos());
            buffer.writeComponent(entry.targetName());
            buffer.writeResourceLocation(entry.recipeTypeId());
            buffer.writeComponent(entry.recipeTypeName());
            buffer.writeBoolean(entry.showPosition());
        }
    }

    private static List<PatternQuickUploadSelectionMenu.Entry> readPatternQuickUploadEntries(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<PatternQuickUploadSelectionMenu.Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new PatternQuickUploadSelectionMenu.Entry(
                    ResourceKey.create(Registries.DIMENSION, buffer.readResourceLocation()),
                    buffer.readBlockPos(),
                    buffer.readComponent(),
                    buffer.readResourceLocation(),
                    buffer.readComponent(),
                    buffer.readBoolean()));
        }
        return entries;
    }

    private static List<TargetNetworkEntry> buildTargetEntries(ServerPlayer player,
                                                               WirelessAeSavedData.MemberKey target) {
        ServerLevel level = player.serverLevel();
        WirelessAeSavedData data = WirelessAeSavedData.get(level.getServer());
        UUID currentNetwork = data.getMemberNetwork(target);
        UUID wiredNetwork = WirelessAeNetworkRuntime.findWiredNetworkFrequency(level.getServer(), target);
        UUID connectedNetwork = wiredNetwork == null ? WirelessAeNetworkRuntime.findConnectedNetworkFrequency(level.getServer(), target) : wiredNetwork;
        List<TargetNetworkEntry> entries = new ArrayList<>();
        for (WirelessAeSavedData.NetworkInfo network : WirelessAeNetworkRuntime.getAccessibleNetworkInfo(player)) {
            boolean connected = network.frequency().equals(connectedNetwork);
            boolean disconnectable = wiredNetwork == null && connected && network.frequency().equals(currentNetwork) && WirelessAeNetworkRuntime.hasWirelessConnection(network.frequency(), target);
            entries.add(new TargetNetworkEntry(
                    network.frequency(),
                    network.name(),
                    connected,
                    disconnectable));
        }
        return entries;
    }

    private static void writeDirection(FriendlyByteBuf buffer, Direction direction) {
        buffer.writeBoolean(direction != null);
        if (direction != null) {
            buffer.writeEnum(direction);
        }
    }

    private static Direction readDirection(FriendlyByteBuf buffer) {
        return buffer.readBoolean() ? buffer.readEnum(Direction.class) : null;
    }

    private static void writeVec3(FriendlyByteBuf buffer, Vec3 vec) {
        buffer.writeBoolean(vec != null);
        if (vec != null) {
            buffer.writeDouble(vec.x);
            buffer.writeDouble(vec.y);
            buffer.writeDouble(vec.z);
        }
    }

    private static Vec3 readVec3(FriendlyByteBuf buffer) {
        return buffer.readBoolean() ? new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()) : null;
    }

    private static boolean isCloseEnough(ServerPlayer player, BlockPos pos) {
        return !(player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64.0D);
    }
}
