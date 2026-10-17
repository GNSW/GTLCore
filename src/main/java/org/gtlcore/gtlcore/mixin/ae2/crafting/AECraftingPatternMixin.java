package org.gtlcore.gtlcore.mixin.ae2.crafting;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.stacks.AEItemKey;
import appeng.crafting.pattern.AECraftingPattern;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Native pattern handles are shared by asynchronous legacy planning and assemblers. */
@Mixin(AECraftingPattern.class)
public abstract class AECraftingPatternMixin {

    @Shadow(remap = false)
    @Final
    private CraftingContainer testFrame;

    @WrapMethod(method = "isItemValid", remap = false)
    private boolean gtlcore$isolateValidation(int slot, AEItemKey key, Level level, Operation<Boolean> original) {
        synchronized (this) {
            // AE temporarily replaces a slot and caches the resulting verdict.
            // Another caller must not see a partially updated crafting frame.
            ItemStack previous = testFrame.getItem(slot);
            try {
                return original.call(slot, key, level);
            } finally {
                testFrame.setItem(slot, previous);
            }
        }
    }

    @WrapMethod(method = "getRecipeRemainder", remap = false)
    private ItemStack gtlcore$isolateRemainder(int slot, AEItemKey key, Operation<ItemStack> original) {
        synchronized (this) {
            ItemStack previous = testFrame.getItem(slot);
            try {
                return original.call(slot, key);
            } finally {
                testFrame.setItem(slot, previous);
            }
        }
    }

    @WrapMethod(method = "assemble", remap = false)
    private ItemStack gtlcore$isolateAssembly(Container inventory, Level level, Operation<ItemStack> original) {
        synchronized (this) {
            // Special recipes use a second shared frame; ordinary recipes call
            // isItemValid and enter this same per-pattern monitor reentrantly.
            return original.call(inventory, level);
        }
    }
}
