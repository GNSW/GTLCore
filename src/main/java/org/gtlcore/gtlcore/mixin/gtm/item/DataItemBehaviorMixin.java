package org.gtlcore.gtlcore.mixin.gtm.item;

import com.gregtechceu.gtceu.common.item.DataItemBehavior;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DataItemBehavior.class)
public abstract class DataItemBehaviorMixin {

    @Redirect(method = "appendHoverText",
              at = @At(value = "INVOKE",
                       remap = true,
                       target = "Lnet/minecraft/world/item/ItemStack;getOrCreateTag()Lnet/minecraft/nbt/CompoundTag;"),
              remap = false)
    private CompoundTag gtlcore$readTooltipData(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? new CompoundTag() : tag;
    }
}
