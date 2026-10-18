package org.gtlcore.gtlcore.mixin.jei;

import org.gtlcore.gtlcore.integration.jei.grouping.TagGroupOverlay;

import net.minecraft.client.gui.screens.Screen;

import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.gui.input.ClientInputHandler;
import mezz.jei.gui.input.UserInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(value = ClientInputHandler.class, remap = false)
public abstract class TagGroupInputMixin {

    @Inject(method = "onGuiMouseScroll", at = @At("HEAD"), cancellable = true)
    private void gtlcore$scrollExpandedGroup(double mouseX, double mouseY, double delta, CallbackInfoReturnable<Boolean> cir) {
        var overlay = TagGroupOverlay.active();
        if (overlay != null && overlay.scroll(mouseX, mouseY, delta)) cir.setReturnValue(true);
    }

    @Inject(method = "onGuiMouseClicked", at = @At("HEAD"), cancellable = true)
    private void gtlcore$blockClicksThroughPopup(Screen screen, UserInput input, CallbackInfoReturnable<Boolean> cir) {
        var overlay = TagGroupOverlay.active();
        if (overlay != null && overlay.contains(input.getMouseX(), input.getMouseY()) &&
                overlay.hoveredSlot(input.getMouseX(), input.getMouseY()).isEmpty()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "onKeyboardKeyPressedPre", at = @At("HEAD"), cancellable = true)
    private void gtlcore$escapeExpandedGroup(Screen screen, UserInput input, CallbackInfoReturnable<Boolean> cir) {
        if (TagGroupOverlay.active() != null && input.getKey().getType() == InputConstants.Type.KEYSYM &&
                input.getKey().getValue() == GLFW.GLFW_KEY_ESCAPE) {
            TagGroupOverlay.dismissActive();
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "onInitGui", at = @At("HEAD"))
    private void gtlcore$closeOnScreenChange(CallbackInfo ci) {
        TagGroupOverlay.closeActive();
    }
}
