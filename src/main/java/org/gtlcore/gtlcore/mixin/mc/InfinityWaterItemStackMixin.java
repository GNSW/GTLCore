package org.gtlcore.gtlcore.mixin.mc;

import org.gtlcore.gtlcore.integration.ae2.InfinityWaterCellData;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStack.class)
public abstract class InfinityWaterItemStackMixin {

    @Shadow
    private CompoundTag tag;

    @ModifyVariable(method = "setTag", at = @At("HEAD"), argsOnly = true)
    private CompoundTag gtlcore$normalizeInfinityWaterTag(CompoundTag value) {
        return InfinityWaterCellData.normalize(((ItemStack) (Object) this).getItem(), value);
    }

    // The save-data constructor writes the field directly instead of calling setTag().
    @Inject(method = "<init>(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("RETURN"))
    private void gtlcore$loadInfinityWaterTag(CompoundTag saved, CallbackInfo ci) {
        tag = InfinityWaterCellData.normalize(((ItemStack) (Object) this).getItem(), tag);
    }
}
