package org.gtlcore.gtlcore.integration.ae2.wireless;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Finds the wireless network binding tool wherever the player carries it: the Curios slots first,
 * then the hands, then the inventory.
 * <p>
 * Reuses {@link CuriosCompat}, so Curios stays optional.
 */
public final class WirelessBindingToolLocator {

    private WirelessBindingToolLocator() {}

    public static ItemStack find(Player player) {
        if (CuriosCompat.isLoaded()) {
            for (ItemStack stack : CuriosCompat.getCurioStacks(player)) {
                if (stack.getItem() instanceof WirelessNetworkBindingToolItem) {
                    return stack;
                }
            }
        }
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.getItem() instanceof WirelessNetworkBindingToolItem) {
            return mainHand;
        }
        ItemStack offHand = player.getOffhandItem();
        if (offHand.getItem() instanceof WirelessNetworkBindingToolItem) {
            return offHand;
        }
        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() instanceof WirelessNetworkBindingToolItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
