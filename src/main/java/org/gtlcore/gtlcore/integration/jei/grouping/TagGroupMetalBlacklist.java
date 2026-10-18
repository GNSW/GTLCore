package org.gtlcore.gtlcore.integration.jei.grouping;

import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.data.chemical.material.properties.PropertyKey;
import com.gregtechceu.gtceu.api.data.chemical.material.stack.ItemMaterialInfo;
import com.gregtechceu.gtceu.api.data.chemical.material.stack.UnificationEntry;

import net.minecraft.world.item.Item;

final class TagGroupMetalBlacklist {

    private TagGroupMetalBlacklist() {}

    static boolean isMetal(Item item) {
        return hasMetalMaterial(ChemicalHelper.getUnificationEntry(item), null);
    }

    static boolean hasMetalMaterial(UnificationEntry entry, ItemMaterialInfo info) {
        Material material = entry == null ? null : entry.material;
        // Recycling composition describes what an item contains, not its category.
        // A chest or circuit containing metal must still be allowed to group.
        return material != null && material.hasProperty(PropertyKey.INGOT);
    }
}
