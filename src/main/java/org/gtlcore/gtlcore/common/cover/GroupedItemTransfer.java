package org.gtlcore.gtlcore.common.cover;

import com.gregtechceu.gtceu.api.cover.filter.SimpleItemFilter;
import com.gregtechceu.gtceu.common.pipelike.item.ItemNetHandler;

import com.lowdragmc.lowdraglib.side.item.IItemTransfer;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** A complete, slot-addressed batch. Planning never changes either inventory. */
public record GroupedItemTransfer(List<Move> moves, long amount) {

    public static GroupedItemTransfer plan(IItemTransfer source, IItemTransfer target, SimpleItemFilter filter) {
        if (source == target || source instanceof ItemNetHandler || target instanceof ItemNetHandler) return null;
        int[] extracted = new int[source.getSlots()];
        ItemStack[] inserted = new ItemStack[target.getSlots()];
        List<Move> moves = new ArrayList<>();
        long total = 0;
        for (ItemStack match : filter.getMatches()) {
            if (match.isEmpty()) continue;
            int remaining = match.getCount();
            for (int from = 0; from < source.getSlots() && remaining > 0; from++) {
                ItemStack stored = source.getStackInSlot(from);
                if (!(filter.isIgnoreNbt() ? ItemStack.isSameItem(match, stored) : ItemStack.isSameItemSameTags(match, stored))) continue;
                int requested = (int) Math.min((long) remaining + extracted[from], Integer.MAX_VALUE);
                ItemStack available = source.extractItem(from, requested, true);
                int count = Math.min(remaining, available.getCount() - extracted[from]);
                if (count <= 0) continue;
                int unassigned = count;
                // Match the usual insertion order: occupied stacks before empty slots.
                for (int pass = 0; pass < 2 && unassigned > 0; pass++) {
                    for (int to = 0; to < target.getSlots() && unassigned > 0; to++) {
                        ItemStack occupied = inserted[to] == null ? target.getStackInSlot(to) : inserted[to];
                        if (occupied.isEmpty() != (pass == 1)) continue;
                        if (!occupied.isEmpty() && !ItemStack.isSameItemSameTags(occupied, available)) continue;
                        int reserved = inserted[to] == null ? 0 : inserted[to].getCount();
                        ItemStack request = available.copyWithCount((int) Math.min((long) unassigned + reserved, Integer.MAX_VALUE));
                        int accepted = request.getCount() - target.insertItem(to, request, true).getCount() - reserved;
                        int moved = Math.min(unassigned, Math.max(0, accepted));
                        if (moved == 0) continue;
                        inserted[to] = available.copyWithCount(reserved + moved);
                        moves.add(new Move(from, to, moved));
                        unassigned -= moved;
                    }
                }
                count -= unassigned;
                extracted[from] += count;
                remaining -= count;
                total += count;
            }
            if (remaining > 0) return null;
        }
        return total == 0 ? null : new GroupedItemTransfer(moves, total);
    }

    public void execute(IItemTransfer source, IItemTransfer target) {
        for (Move move : moves) {
            ItemStack stack = source.extractItem(move.from, move.amount, false, false);
            target.insertItem(move.to, stack, false, false);
        }
        source.onContentsChanged();
        target.onContentsChanged();
    }

    public record Move(int from, int to, int amount) {}
}
