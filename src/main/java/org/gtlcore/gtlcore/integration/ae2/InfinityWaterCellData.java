package org.gtlcore.gtlcore.integration.ae2;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;

import com.glodblock.github.extendedae.common.items.InfinityCell;

/** Use the implicit water record from ExtendedAE as the canonical representation. */
public final class InfinityWaterCellData {

    private InfinityWaterCellData() {}

    public static boolean isCell(Item item) {
        return item instanceof InfinityCell;
    }

    public static CompoundTag normalize(ItemLike item, CompoundTag tag) {
        return tag != null && isCell(item.asItem()) ? normalizeTag(tag) : tag;
    }

    public static CompoundTag normalizeTag(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return null;
        CompoundTag record = tag.getCompound("record");
        // Only strip an exact vanilla water record; custom fluid NBT and unknown data remain distinct.
        if (record.size() != 2 || !"ae2:f".equals(record.getString("#c")) ||
                !"minecraft:water".equals(record.getString("id")))
            return tag;
        CompoundTag normalized = tag.copy();
        normalized.remove("record");
        return normalized.isEmpty() ? null : normalized;
    }
}
