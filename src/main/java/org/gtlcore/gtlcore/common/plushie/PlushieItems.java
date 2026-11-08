package org.gtlcore.gtlcore.common.plushie;

import org.gtlcore.gtlcore.common.data.GTLPlushies;
import org.gtlcore.gtlcore.common.item.WearablePlushieItem;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Identifies the four plushies that share the music-locked bounce behaviour. */
public final class PlushieItems {

    private PlushieItems() {}

    public static boolean isPlushie(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof WearablePlushieItem || isExtendedFishbig(stack));
    }

    public static boolean isExtendedFishbig(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return GTLPlushies.EXTENDED_FISHBIG_ID.equals(id);
    }
}
