package org.gtlcore.gtlcore.mixin.jei;

import org.gtlcore.gtlcore.integration.jei.grouping.TagGroupOverlay;

import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.gui.input.CombinedRecipeFocusSource;
import mezz.jei.gui.input.IClickableIngredientInternal;
import mezz.jei.gui.input.UserInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.stream.Stream;

@Pseudo
@Mixin(value = CombinedRecipeFocusSource.class, remap = false)
public abstract class TagGroupFocusMixin {

    @Inject(method = "getIngredientUnderMouse", at = @At("HEAD"), cancellable = true)
    private void gtlcore$preferExpandedGroup(UserInput input, IInternalKeyMappings keyBindings,
                                             CallbackInfoReturnable<Stream<IClickableIngredientInternal<?>>> cir) {
        var overlay = TagGroupOverlay.active();
        if (overlay != null && overlay.contains(input.getMouseX(), input.getMouseY())) {
            cir.setReturnValue(overlay.clickable(input.getMouseX(), input.getMouseY()));
        }
    }
}
