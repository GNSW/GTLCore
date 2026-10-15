package org.gtlcore.gtlcore.mixin.ae2.crafting;

import net.minecraft.world.item.crafting.CraftingRecipe;

import appeng.crafting.pattern.AECraftingPattern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AECraftingPattern.class)
public interface AECraftingPatternAccessor {

    @Accessor(value = "recipe", remap = false)
    CraftingRecipe gtlcore$getRecipe();
}
