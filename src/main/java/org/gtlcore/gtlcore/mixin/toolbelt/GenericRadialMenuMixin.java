package org.gtlcore.gtlcore.mixin.toolbelt;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Pseudo
@Mixin(targets = "dev.gigaherz.toolbelt.client.radial.GenericRadialMenu", remap = false)
public abstract class GenericRadialMenuMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    public abstract int getVisibleItemCount();

    @ModifyConstant(method = "draw", constant = @Constant(floatValue = 30.0F))
    private float gtlcore$spaceExpandedWheel(float radius) {
        if (getVisibleItemCount() != 18) return radius;
        var window = minecraft.getWindow();
        int shortestSide = Math.min(window.getGuiScaledWidth(), window.getGuiScaledHeight());
        // The original derives both the drawn ring and mouse hit area from this inner radius.
        return Math.max(radius, Math.min(50.0F, (shortestSide - 16) / 4.0F));
    }
}
