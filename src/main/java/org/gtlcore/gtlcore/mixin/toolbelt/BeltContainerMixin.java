package org.gtlcore.gtlcore.mixin.toolbelt;

import org.gtlcore.gtlcore.mixin.mc.SlotAccessor;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.gigaherz.toolbelt.common.BeltContainer", remap = false)
public abstract class BeltContainerMixin extends AbstractContainerMenu {

    @Shadow
    @Final
    public int beltSlots;

    protected BeltContainerMixin(MenuType<?> type, int containerId) {
        super(type, containerId);
    }

    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;ILnet/minecraft/world/item/ItemStack;)V",
            at = @At("RETURN"))
    private void gtlcore$arrangeExpandedBelt(CallbackInfo ci) {
        if (beltSlots != 18) return;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            SlotAccessor accessor = (SlotAccessor) slot;
            if (i < beltSlots) {
                accessor.gtlcore$setX(8 + (i % 9) * 18);
                accessor.gtlcore$setY(20 + (i / 9) * 18);
            } else {
                accessor.gtlcore$setY(slot.y + 18);
            }
        }
    }
}
