package org.gtlcore.gtlcore.mixin.ftbquests;

import org.gtlcore.gtlcore.integration.ftbquests.QuestSearchText;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftblibrary.ui.misc.AbstractButtonListScreen", remap = false)
public abstract class QuestSearchMixin {

    @Inject(method = "getFilterText", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtlcore$includeQuestContents(@Coerce Object widget, CallbackInfoReturnable<String> cir) {
        String contents = QuestSearchText.forButton(widget);
        if (!contents.isEmpty()) {
            cir.setReturnValue(cir.getReturnValue() + contents);
        }
    }
}
