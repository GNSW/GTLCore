package org.gtlcore.gtlcore.integration.ae2;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;
import net.minecraftforge.registries.ForgeRegistries;

/** Empty display-created tags have no data-media semantics. Nonempty data and capabilities do. */
public final class BlankDataMedia {

    private BlankDataMedia() {}

    public static boolean isMedia(Item item) {
        var id = ForgeRegistries.ITEMS.getKey(item);
        if (id == null || !id.getNamespace().equals("gtceu")) return false;
        return switch (id.getPath()) {
            case "data_stick", "data_orb", "data_module" -> true;
            default -> false;
        };
    }

    public static CompoundTag normalize(ItemLike item, CompoundTag tag) {
        return tag != null && tag.isEmpty() && isMedia(item.asItem()) ? null : tag;
    }
}
