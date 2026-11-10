package org.gtlcore.gtlcore.integration.ae2.wireless;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public final class WirelessNetworkBindingToolItem extends Item {

    private static final String TAG_NETWORK = "WirelessNetworkId";
    private static final String TAG_NETWORK_NAME = "WirelessNetworkName";

    public WirelessNetworkBindingToolItem() {
        super(new Properties().stacksTo(1));
    }

    static InteractionResult saveNetwork(ServerPlayer player, ItemStack stack, UUID frequency) {
        String networkName = WirelessAeSavedData.get(player.server).getNetworkName(frequency);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putUUID(TAG_NETWORK, frequency);
        tag.putString(TAG_NETWORK_NAME, networkName);
        player.inventoryMenu.broadcastChanges();
        return message(player, "message.gtlcore.wireless_binding_tool.saved", networkName);
    }

    /** Network currently stored in the tool, or {@code null} when nothing is saved yet. */
    public static UUID savedNetwork(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.hasUUID(TAG_NETWORK) ? tag.getUUID(TAG_NETWORK) : null;
    }

    public static void clearSavedNetwork(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof WirelessNetworkBindingToolItem)) return;
        clearSavedNetwork(player, stack);
    }

    /** Clears the saved network from the given tool stack, wherever it is carried. */
    public static void clearSavedNetwork(ServerPlayer player, ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.hasUUID(TAG_NETWORK)) return;
        if (tag != null) {
            tag.remove(TAG_NETWORK);
            tag.remove(TAG_NETWORK_NAME);
            if (tag.isEmpty()) stack.setTag(null);
        }
        player.inventoryMenu.broadcastChanges();
        message(player, "message.gtlcore.wireless_binding_tool.cleared");
    }

    /** Opens the picker for the tool the player carries, wherever that is. */
    public static void openPickerFor(ServerPlayer player) {
        ItemStack stack = WirelessBindingToolLocator.find(player);
        if (stack.isEmpty()) {
            return;
        }
        WirelessNetworkPickerMenu.open(player, stack);
    }

    /**
     * Applies a pick from {@link WirelessNetworkPickerMenu}: stores the network, or clears the saved
     * one when it is picked again.
     */
    public static void applyPickerChoice(ServerPlayer player, UUID frequency) {
        ItemStack stack = WirelessBindingToolLocator.find(player);
        if (stack.isEmpty()) {
            return;
        }
        if (!player.containerMenu.stillValid(player) || !player.mayBuild()) {
            return;
        }
        if (frequency.equals(savedNetwork(stack))) {
            clearSavedNetwork(player, stack);
            return;
        }
        if (!WirelessAeNetworkRuntime.canAccessNetwork(player, frequency)) {
            message(player, "message.gtlcore.wireless_binding_tool.no_access");
            return;
        }
        if (WirelessAeSavedData.get(player.server).getCore(frequency) == null) {
            message(player, "message.gtlcore.wireless_target.missing_core");
            return;
        }
        saveNetwork(player, stack, frequency);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        var level = context.getLevel();
        var player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        // Run before block use so binding never opens the machine's GUI.
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        if (!player.mayBuild() || !level.mayInteract(player, context.getClickedPos())) {
            return InteractionResult.FAIL;
        }

        // Sneaking selects a network on cores and allows replacing a hatch binding.
        if (player.isShiftKeyDown() &&
                level.getBlockEntity(context.getClickedPos()) instanceof WirelessNetworkCoreBlockEntity core) {
            UUID frequency = core.getFrequency();
            if (!WirelessAeNetworkRuntime.canAccessNetwork(serverPlayer, frequency)) {
                return message(serverPlayer, "message.gtlcore.wireless_binding_tool.no_access");
            }
            return saveNetwork(serverPlayer, stack, frequency);
        }

        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.hasUUID(TAG_NETWORK)) {
            return message(serverPlayer, "message.gtlcore.wireless_binding_tool.select_core");
        }
        UUID frequency = tag.getUUID(TAG_NETWORK);
        var server = serverPlayer.server;
        var data = WirelessAeSavedData.get(server);
        if (data.getCore(frequency) == null) {
            return message(serverPlayer, "message.gtlcore.wireless_target.missing_core");
        }
        if (!WirelessAeNetworkRuntime.canAccessNetwork(serverPlayer, frequency)) {
            return message(serverPlayer, "message.gtlcore.wireless_binding_tool.no_access");
        }
        tag.putString(TAG_NETWORK_NAME, data.getNetworkName(frequency));
        var target = WirelessAeNetworkRuntime.resolveWirelessTarget(serverPlayer.serverLevel(),
                context.getClickedPos(), context.getClickedFace(), context.getClickLocation());
        if (!level.mayInteract(player, target.blockPos())) return InteractionResult.FAIL;
        if (!WirelessAeNetworkRuntime.canBindAsWirelessTarget(serverPlayer.serverLevel(), target.blockPos(), target.side())) {
            return message(serverPlayer, "message.gtlcore.wireless_target.invalid_target");
        }
        return bindTo(serverPlayer, target, frequency, serverPlayer.isShiftKeyDown());
    }

    /**
     * Binds an already resolved target to {@code frequency}. Shared by the tool's right-click path and
     * by sneak-placing a target, which cannot go through {@link #onItemUseFirst}.
     */
    static InteractionResult bindTo(ServerPlayer player, WirelessAeSavedData.MemberKey target, UUID frequency,
                                    boolean replace) {
        var server = player.server;
        var data = WirelessAeSavedData.get(server);
        UUID currentNetwork = data.getMemberNetwork(target);
        UUID wiredNetwork = WirelessAeNetworkRuntime.findWiredNetworkFrequency(server, target);
        UUID connectedNetwork = wiredNetwork == null ? WirelessAeNetworkRuntime.findConnectedNetworkFrequency(server, target) : wiredNetwork;
        // Persisted bindings count even when the core is offline or reconnection is pending.
        if (!replace && (currentNetwork != null || connectedNetwork != null)) {
            return message(player, "message.gtlcore.wireless_binding_tool.already_bound");
        }
        if (wiredNetwork != null || (connectedNetwork != null &&
                (!connectedNetwork.equals(currentNetwork) || !WirelessAeNetworkRuntime.hasWirelessConnection(connectedNetwork, target)))) {
            return message(player, "message.gtlcore.wireless_binding_tool.existing_connection");
        }
        if (currentNetwork != null && !WirelessAeNetworkRuntime.canAccessNetwork(player, currentNetwork)) {
            return message(player, "message.gtlcore.wireless_binding_tool.no_access");
        }
        var core = WirelessAeNetworkRuntime.getLoadedCore(server, frequency);
        if (core == null) return message(player, "message.gtlcore.wireless_target.missing_core");
        if (!core.isLinkedToAeNetwork()) {
            return message(player, "message.gtlcore.wireless_target.core_not_connected");
        }
        if (frequency.equals(currentNetwork) && WirelessAeNetworkRuntime.hasWirelessConnection(frequency, target)) {
            return message(player, "message.gtlcore.wireless_target.linked_target", data.getNetworkName(frequency));
        }
        for (UUID removedNetwork : data.removeMembersAt(target.pos())) {
            WirelessAeNetworkRuntime.disconnectMembersAt(removedNetwork, target.pos());
        }
        var result = WirelessAeNetworkRuntime.connectMemberNow(server, frequency, target);
        data.addMember(frequency, target);
        WirelessAeNetworkRuntime.requestReconnect(frequency);
        boolean connected = result == WirelessAeNetworkRuntime.ConnectionResult.CONNECTED ||
                result == WirelessAeNetworkRuntime.ConnectionResult.ALREADY_CONNECTED;
        return message(player, connected ? "message.gtlcore.wireless_target.linked_target" :
                "message.gtlcore.wireless_target.linked_target_pending", data.getNetworkName(frequency));
    }

    /**
     * Right-clicking the air opens the network picker.
     * Right-clicking a block never reaches here because {@link #onItemUseFirst} consumes it first.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.pass(stack);
        }
        if (!player.mayBuild()) {
            return InteractionResultHolder.fail(stack);
        }
        WirelessNetworkPickerMenu.open(serverPlayer);
        return InteractionResultHolder.success(stack);
    }

    private static InteractionResult message(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
        return InteractionResult.CONSUME;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        // Refresh renamed networks and migrate tools that only stored the network ID.
        // Keep the cached name in NBT so it is also available to the client and after reloads.
        if (!(level instanceof ServerLevel serverLevel) || level.getGameTime() % 20 != 0) return;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.hasUUID(TAG_NETWORK)) return;
        UUID frequency = tag.getUUID(TAG_NETWORK);
        var data = WirelessAeSavedData.get(serverLevel.getServer());
        if (data.getCore(frequency) != null) {
            String networkName = data.getNetworkName(frequency);
            if (!networkName.equals(tag.getString(TAG_NETWORK_NAME))) {
                tag.putString(TAG_NETWORK_NAME, networkName);
            }
        }
    }

    private static Component savedNetworkName(CompoundTag tag) {
        String networkName = tag.getString(TAG_NETWORK_NAME);
        return networkName.isEmpty() ? Component.translatable("tooltip.gtlcore.wireless_binding_tool.unknown_name") :
                Component.literal(networkName);
    }

    @Override
    public Component getName(ItemStack stack) {
        var name = Component.translatable(getDescriptionId(stack)).withStyle(ChatFormatting.GOLD);
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.hasUUID(TAG_NETWORK)) {
            name.append(Component.literal(" [").append(savedNetworkName(tag)).append("]").withStyle(ChatFormatting.AQUA));
        }
        return name;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.gtlcore.wireless_binding_tool.select").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.gtlcore.wireless_binding_tool.pick").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.gtlcore.wireless_binding_tool.bind").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.gtlcore.wireless_binding_tool.rebind").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.gtlcore.wireless_binding_tool.offhand").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.gtlcore.wireless_binding_tool.menu_controls").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.gtlcore.wireless_binding_tool.clear").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.gtlcore.wireless_binding_tool.disconnect").withStyle(ChatFormatting.GRAY));
        CompoundTag tag = stack.getTag();
        tooltip.add(tag != null && tag.hasUUID(TAG_NETWORK) ?
                Component.translatable("tooltip.gtlcore.wireless_binding_tool.network", savedNetworkName(tag)).withStyle(ChatFormatting.AQUA) :
                Component.translatable("tooltip.gtlcore.wireless_binding_tool.empty").withStyle(ChatFormatting.YELLOW));
    }
}
