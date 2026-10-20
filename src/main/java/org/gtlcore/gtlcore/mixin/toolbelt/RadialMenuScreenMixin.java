package org.gtlcore.gtlcore.mixin.toolbelt;

import net.minecraftforge.items.IItemHandler;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "dev.gigaherz.toolbelt.client.RadialMenuScreen", remap = false)
public abstract class RadialMenuScreenMixin {

    @Shadow
    private IItemHandler inventory;

    @ModifyExpressionValue(method = { "render", "m_88315_" },
                           at = @At(value = "FIELD", target = "Ldev/gigaherz/toolbelt/ConfigData;displayEmptySlots:Z"))
    private boolean gtlcore$showAllExpandedSlots(boolean displayEmptySlots) {
        // Include empty slots and suppress the extra INSERT entry: each sector keeps its real slot index.
        return displayEmptySlots || (inventory != null && inventory.getSlots() == 18);
    }
}
