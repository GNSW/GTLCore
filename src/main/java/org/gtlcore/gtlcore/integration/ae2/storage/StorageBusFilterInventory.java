package org.gtlcore.gtlcore.integration.ae2.storage;

import net.minecraft.world.item.ItemStack;

import appeng.helpers.externalstorage.GenericStackInv;
import appeng.util.ConfigMenuInventory;
import org.jetbrains.annotations.NotNull;

/** Reject duplicate player edits. */
public final class StorageBusFilterInventory extends ConfigMenuInventory {

    private final boolean clientSide;

    public StorageBusFilterInventory(GenericStackInv inventory, boolean clientSide) {
        super(inventory);
        this.clientSide = clientSide;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        var converted = convertToSuitableStack(stack);
        if (converted == null) {
            return false;
        }
        for (int other = 0; other < size(); other++) {
            if (other != slot && converted.what().equals(getDelegate().getKey(other))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void setItemDirect(int slot, @NotNull ItemStack stack) {
        // Server slot updates can temporarily duplicate a key on the client
        // while a partition/memory-card operation moves it to another slot.
        if (clientSide || isItemValid(slot, stack)) {
            super.setItemDirect(slot, stack);
        }
    }
}
