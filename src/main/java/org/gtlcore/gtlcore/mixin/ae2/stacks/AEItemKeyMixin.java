package org.gtlcore.gtlcore.mixin.ae2.stacks;

import org.gtlcore.gtlcore.integration.ae2.BlankDataMedia;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

import appeng.api.stacks.AEItemKey;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 代码参考自gto
 * &#064;line <a href="https://github.com/GregTech-Odyssey/GTOCore">...</a>
 */

@Mixin(AEItemKey.class)
public abstract class AEItemKeyMixin {

    private int fuzzySearchMaxValue = -1;

    @Shadow(remap = false)
    @Final
    @Mutable
    private final Item item;

    public AEItemKeyMixin(Item item) {
        this.item = item;
    }

    @ModifyVariable(method = "of(Lnet/minecraft/world/level/ItemLike;Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/nbt/CompoundTag;)Lappeng/api/stacks/AEItemKey;",
                    at = @At("HEAD"),
                    argsOnly = true,
                    ordinal = 0,
                    remap = false)
    private static CompoundTag gtlcore$blankDataTag(CompoundTag value, ItemLike item, CompoundTag tag, CompoundTag caps) {
        return BlankDataMedia.normalize(item, value);
    }

    // Packet decoding constructs its interned key directly instead of using of().
    @Inject(method = "fromPacket", at = @At("RETURN"), cancellable = true, remap = false)
    private static void gtlcore$blankPacketTag(FriendlyByteBuf data, CallbackInfoReturnable<AEItemKey> cir) {
        AEItemKey key = cir.getReturnValue();
        if (key != null && key.getTag() != null && key.getTag().isEmpty() && BlankDataMedia.isMedia(key.getItem()))
            cir.setReturnValue(AEItemKey.of(key.toStack()));
    }

    @Inject(method = "matches(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$matchBlankData(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (stack.isEmpty() || !BlankDataMedia.isMedia(item)) return;
        CompoundTag tag = stack.getTag();
        if (tag == null || tag.isEmpty()) cir.setReturnValue(((AEItemKey) (Object) this).equals(AEItemKey.of(stack)));
    }

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public int getFuzzySearchMaxValue() {
        if (this.fuzzySearchMaxValue < 0) this.fuzzySearchMaxValue = this.item.getMaxDamage();
        return fuzzySearchMaxValue;
    }
}
