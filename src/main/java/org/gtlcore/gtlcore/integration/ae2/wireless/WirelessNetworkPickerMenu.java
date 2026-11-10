package org.gtlcore.gtlcore.integration.ae2.wireless;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkHooks;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lists the wireless ME networks the player may use, for the binding tool's saved network.
 * <p>
 * Opened by right-clicking the air with the tool. Clicking an entry stores that network in the tool;
 * clicking the network that is already saved clears it again, so the tool can be reset without
 * looking for a core.
 */
public class WirelessNetworkPickerMenu extends AbstractContainerMenu {

    private final BlockPos originPos;
    private final UUID savedNetwork;
    private final List<Entry> networks;

    public WirelessNetworkPickerMenu(int containerId, Inventory inventory, FriendlyByteBuf data) {
        super(GTLWirelessAeContent.WIRELESS_NETWORK_PICKER_MENU.get(), containerId);
        this.originPos = data.readBlockPos();
        this.savedNetwork = data.readBoolean() ? data.readUUID() : null;
        int size = data.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(data.readUUID(), data.readUtf(32)));
        }
        this.networks = List.copyOf(entries);
    }

    public WirelessNetworkPickerMenu(int containerId, Inventory inventory, BlockPos originPos,
                                     UUID savedNetwork, List<Entry> networks) {
        super(GTLWirelessAeContent.WIRELESS_NETWORK_PICKER_MENU.get(), containerId);
        this.originPos = originPos;
        this.savedNetwork = savedNetwork;
        this.networks = List.copyOf(networks);
    }

    public static void open(ServerPlayer player) {
        ItemStack stack = WirelessBindingToolLocator.find(player);
        if (stack.isEmpty()) {
            return;
        }
        open(player, stack);
    }

    public static void open(ServerPlayer player, ItemStack stack) {
        if (!(stack.getItem() instanceof WirelessNetworkBindingToolItem)) {
            return;
        }
        BlockPos originPos = player.blockPosition();
        UUID savedNetwork = WirelessNetworkBindingToolItem.savedNetwork(stack);
        List<Entry> networks = new ArrayList<>();
        for (WirelessAeSavedData.NetworkInfo network : WirelessAeNetworkRuntime.getAccessibleNetworkInfo(player)) {
            networks.add(new Entry(network.frequency(), network.name()));
        }
        NetworkHooks.openScreen(
                player,
                new MenuProvider() {

                    @Override
                    public @NotNull Component getDisplayName() {
                        return Component.translatable("screen.gtlcore.wireless_binding_picker");
                    }

                    @Override
                    public AbstractContainerMenu createMenu(int containerId, @NotNull Inventory inventory, @NotNull Player menuPlayer) {
                        return new WirelessNetworkPickerMenu(containerId, inventory, originPos, savedNetwork, networks);
                    }
                },
                buffer -> write(buffer, originPos, savedNetwork, networks));
    }

    private static void write(FriendlyByteBuf buffer, BlockPos originPos, UUID savedNetwork, List<Entry> networks) {
        buffer.writeBlockPos(originPos);
        buffer.writeBoolean(savedNetwork != null);
        if (savedNetwork != null) {
            buffer.writeUUID(savedNetwork);
        }
        buffer.writeVarInt(networks.size());
        for (Entry network : networks) {
            buffer.writeUUID(network.frequency());
            buffer.writeUtf(network.name());
        }
    }

    public BlockPos getOriginPos() {
        return this.originPos;
    }

    public UUID getSavedNetwork() {
        return this.savedNetwork;
    }

    public List<Entry> getNetworks() {
        return this.networks;
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (player.level().isClientSide) {
            return true;
        }
        if (WirelessBindingToolLocator.find(player).isEmpty()) {
            return false;
        }
        return player.distanceToSqr(
                this.originPos.getX() + 0.5D,
                this.originPos.getY() + 0.5D,
                this.originPos.getZ() + 0.5D) <= 64.0D;
    }

    public record Entry(UUID frequency, String name) {}
}
