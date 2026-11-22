package org.gtlcore.gtlcore.utils;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.recipe.*;

import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

/**
 * @author EasterFG on 2025/3/21
 */
public class GTLUtil {

    public static ItemStack loadItemStack(CompoundTag compoundTag) {
        try {
            ItemStack stack = Registries.getItemStack(compoundTag.getString("id"));
            if (compoundTag.contains("tag", Tag.TAG_COMPOUND)) {
                stack.setTag(compoundTag.getCompound("tag"));
                if (stack.getTag() != null) {
                    stack.getItem().verifyTagAfterLoad(stack.getTag());
                }
            }

            if (stack.getItem().canBeDepleted()) {
                stack.setDamageValue(stack.getDamageValue());
            }
            return stack;
        } catch (RuntimeException var2) {
            GTCEu.LOGGER.debug("Tried to load invalid item: {}", compoundTag, var2);
            return ItemStack.EMPTY;
        }
    }

    public static Tag serializeNBT(GTRecipe recipe) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", recipe.id.toString());
        tag.put("recipe", GTRecipeSerializer.CODEC.encodeStart(NbtOps.INSTANCE, recipe).result().orElse(new CompoundTag()));
        return tag;
    }

    public static @Nullable GTRecipe deserializeNBT(Tag tag) {
        if (tag instanceof CompoundTag ctag) {
            var id = ResourceLocation.tryParse(ctag.getString("id"));
            var recipe = GTRecipeSerializer.CODEC.parse(NbtOps.INSTANCE, ctag.get("recipe")).result().orElse(null);
            if (recipe == null || id == null) return null;
            recipe.setId(id);
            return recipe;
        }
        return null;
    }
}
