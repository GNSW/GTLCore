package org.gtlcore.gtlcore.mixin.ftbquests;

import org.gtlcore.gtlcore.client.ftbquests.QuestTranslationAccess;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftblibrary.config.ListConfig", remap = false)
public abstract class QuestTranslationListConfigMixin {

    @Inject(method = "onClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$editManagedDescription(@Coerce Object widget, @Coerce Object button,
                                                @Coerce Object callback, CallbackInfo ci) {
        if (QuestTranslationAccess.openConfig(this, widget, callback)) ci.cancel();
    }
}
