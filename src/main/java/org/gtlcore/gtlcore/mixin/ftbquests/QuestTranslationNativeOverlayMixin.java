package org.gtlcore.gtlcore.mixin.ftbquests;

import org.gtlcore.gtlcore.client.ftbquests.QuestTranslationNativeEditor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftblibrary.config.ui.EditStringConfigOverlay", remap = false)
public abstract class QuestTranslationNativeOverlayMixin {

    @Inject(method = "onAccepted", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$awaitServerSave(@Coerce Object button, @Coerce Object mouseButton, CallbackInfo ci) {
        if (QuestTranslationNativeEditor.accept(this)) ci.cancel();
    }

    @Inject(method = "onCancelled", at = @At("HEAD"), remap = false)
    private void gtlcore$clearTranslationEdit(@Coerce Object button, @Coerce Object mouseButton, CallbackInfo ci) {
        QuestTranslationNativeEditor.cancelled(this);
    }

    @Inject(method = "alignWidgets", at = @At("TAIL"), remap = false)
    private void gtlcore$fitStatusText(CallbackInfo ci) {
        QuestTranslationNativeEditor.align(this);
    }
}
