package org.gtlcore.gtlcore.common.item.manipulator;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ManipulatorResources {

    private ManipulatorResources() {}

    public static void add(Map<AEKey, Long> map, AEKey key, long amount) {
        if (key != null && amount > 0) map.merge(key, amount, Math::addExact);
    }

    public static void add(Map<AEKey, Long> map, ItemStack stack) {
        if (stack.isEmpty()) return;
        GenericStack wrapped = GenericStack.unwrapItemStack(stack);
        if (wrapped != null) add(map, wrapped.what(), wrapped.amount());
        else add(map, AEItemKey.of(stack), stack.getCount());
    }

    public static ListTag save(Map<AEKey, Long> map) {
        ListTag tag = new ListTag();
        map.forEach((key, amount) -> {
            CompoundTag entry = new CompoundTag();
            entry.put("Key", key.toTagGeneric());
            entry.putLong("Amount", amount);
            tag.add(entry);
        });
        return tag;
    }

    public static Map<AEKey, Long> read(ListTag tag) {
        Map<AEKey, Long> result = new LinkedHashMap<>();
        for (Tag t : tag) {
            CompoundTag entry = (CompoundTag) t;
            add(result, AEKey.fromTagGeneric(entry.getCompound("Key")), entry.getLong("Amount"));
        }
        return result;
    }

    public static long extract(ServerPlayer player, IGrid grid, AEKey key, long amount, boolean simulate) {
        long got = grid == null ? 0 : grid.getStorageService().getInventory().extract(key, amount,
                simulate ? Actionable.SIMULATE : Actionable.MODULATE, IActionSource.ofPlayer(player));
        if (got == amount) return got;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && got < amount; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (key instanceof AEItemKey item && item.matches(stack)) {
                int take = (int) Math.min(amount - got, stack.getCount());
                if (!simulate) inventory.removeItem(slot, take);
                got += take;
            } else if (key instanceof AEFluidKey fluid && stack.getCount() == 1) {
                var handler = FluidUtil.getFluidHandler(stack.copy()).orElse(null);
                if (handler == null) continue;
                var taken = handler.drain(fluid.toStack((int) Math.min(Integer.MAX_VALUE, amount - got)), IFluidHandler.FluidAction.EXECUTE);
                got += taken.getAmount();
                if (!simulate && !taken.isEmpty()) inventory.setItem(slot, handler.getContainer());
            }
        }
        return got;
    }

    /** Returns the unaccepted amount; callers keep it in saved escrow. */
    public static long insert(ServerPlayer player, IGrid grid, AEKey key, long amount, BlockPos dropAt) {
        if (grid != null) amount -= grid.getStorageService().getInventory().insert(key, amount, Actionable.MODULATE, IActionSource.ofPlayer(player));
        if (key instanceof AEItemKey item) {
            while (amount > 0) {
                ItemStack stack = item.toStack((int) Math.min(amount, item.getItem().getMaxStackSize()));
                int count = stack.getCount();
                player.getInventory().add(stack);
                amount -= count - stack.getCount();
                if (!stack.isEmpty()) {
                    if (dropAt == null) return amount;
                    net.minecraft.world.level.block.Block.popResource(player.level(), dropAt, stack);
                    amount -= stack.getCount();
                }
            }
        } else if (key instanceof AEFluidKey fluid) {
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize() && amount > 0; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.getCount() != 1) continue;
                var handler = FluidUtil.getFluidHandler(stack.copy()).orElse(null);
                if (handler == null) continue;
                int filled = handler.fill(fluid.toStack((int) Math.min(Integer.MAX_VALUE, amount)), IFluidHandler.FluidAction.EXECUTE);
                if (filled > 0) {
                    inventory.setItem(slot, handler.getContainer());
                    amount -= filled;
                }
            }
        }
        return amount;
    }
}
