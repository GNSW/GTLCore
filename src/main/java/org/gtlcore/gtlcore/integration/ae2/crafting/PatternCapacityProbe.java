package org.gtlcore.gtlcore.integration.ae2.crafting;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.helpers.patternprovider.PatternProviderTarget;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Reuses simulations within one synchronous batch-size search. Never retain this probe
 * across searches or actual insertions: the target inventory may have changed by then.
 */
public final class PatternCapacityProbe {

    private final PatternProviderTarget target;
    private final Map<AEKey, Long> capacities = new HashMap<>();
    private final Map<IItemHandler, SlotCounts> handlers = new IdentityHashMap<>();

    public PatternCapacityProbe(PatternProviderTarget target) {
        this.target = target;
    }

    public long available(AEKey key) {
        return capacities.computeIfAbsent(key, k -> target.insert(k, Long.MAX_VALUE, Actionable.SIMULATE));
    }

    public int slots(IItemHandler handler) {
        return slotCounts(handler).slots;
    }

    public int usableSlots(IItemHandler handler, AEItemKey key) {
        SlotCounts counts = slotCounts(handler);
        return counts.usable.computeIfAbsent(key, k -> {
            ItemStack representative = k.toStack();
            int usable = 0;
            for (int i = 0; i < counts.slots; i++) {
                ItemStack current = handler.getStackInSlot(i);
                if (!current.isEmpty() && !ItemStack.isSameItem(current, representative)) {
                    continue;
                }
                if (handler.insertItem(i, representative.copyWithCount(1), true).isEmpty()) {
                    usable++;
                }
            }
            return usable;
        });
    }

    private SlotCounts slotCounts(IItemHandler handler) {
        return handlers.computeIfAbsent(handler, h -> new SlotCounts(h.getSlots()));
    }

    private static final class SlotCounts {

        final int slots;
        final Map<AEItemKey, Integer> usable = new HashMap<>();

        SlotCounts(int slots) {
            this.slots = slots;
        }
    }
}
