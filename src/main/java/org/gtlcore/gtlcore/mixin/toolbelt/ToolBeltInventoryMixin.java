package org.gtlcore.gtlcore.mixin.toolbelt;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "dev.gigaherz.toolbelt.belt.ToolBeltInventory", remap = false)
public abstract class ToolBeltInventoryMixin {

    @ModifyReturnValue(method = "getSlots", at = @At("RETURN"))
    private int gtlcore$expandFullyUpgradedBelt(int slots) {
        // Keep Size=9 as the seventh upgrade so existing belts, recipes and XP costs remain valid.
        return slots == 9 ? 18 : slots;
    }
}
