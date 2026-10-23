package org.gtlcore.gtlcore.mixin.ftbquests;

import org.gtlcore.gtlcore.client.ftbquests.QuestTranslationAccess;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.client.gui.quests.ViewQuestPanel", remap = false)
public abstract class QuestTranslationQuickEditMixin {

    @Inject(method = "editTitle", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$editTitle(CallbackInfo ci) {
        if (QuestTranslationAccess.editField(this, "title")) ci.cancel();
    }

    @Inject(method = "editSubtitle", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$editSubtitle(CallbackInfo ci) {
        if (QuestTranslationAccess.editField(this, "subtitle")) ci.cancel();
    }

    @Inject(method = "editDescription", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$editDescription(CallbackInfo ci) {
        if (QuestTranslationAccess.editField(this, "description")) ci.cancel();
    }

    @Inject(method = "editDescLine0", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$editDescriptionLine(@Coerce Object widget, int line, @Coerce Object type, CallbackInfo ci) {
        if (QuestTranslationAccess.editDescriptionLine(this, line)) ci.cancel();
    }
}
