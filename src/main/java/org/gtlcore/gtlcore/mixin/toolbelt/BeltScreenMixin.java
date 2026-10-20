package org.gtlcore.gtlcore.mixin.toolbelt;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.gigaherz.toolbelt.common.BeltScreen", remap = false)
public abstract class BeltScreenMixin extends AbstractContainerScreen<AbstractContainerMenu> {

    @Unique
    private static final ResourceLocation GTLCORE$BELT_TEXTURE = new ResourceLocation("toolbelt", "textures/gui/belt.png");

    protected BeltScreenMixin(AbstractContainerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void gtlcore$resizeExpandedBelt(CallbackInfo ci) {
        if (menu.slots.size() != 18 + 36) return;
        imageHeight += 18;
        inventoryLabelY += 18;
    }

    @Inject(method = { "renderBg", "m_7286_" }, at = @At("HEAD"), cancellable = true)
    private void gtlcore$drawExpandedBelt(GuiGraphics graphics, float partialTick, int mouseX, int mouseY,
                                          CallbackInfo ci) {
        if (menu.slots.size() != 18 + 36) return;
        // Insert one blank row above the player inventory, reusing Tool Belt's own texture.
        graphics.blit(GTLCORE$BELT_TEXTURE, leftPos, topPos, 0, 0, imageWidth, 39);
        graphics.blit(GTLCORE$BELT_TEXTURE, leftPos, topPos + 39, 0, 20, imageWidth, 18);
        graphics.blit(GTLCORE$BELT_TEXTURE, leftPos, topPos + 57, 0, 39, imageWidth, 94);
        for (int row = 0; row < 2; row++) {
            graphics.blit(GTLCORE$BELT_TEXTURE, leftPos + 7, topPos + 19 + row * 18, 0, 133, 162, 18);
        }
        ci.cancel();
    }
}
