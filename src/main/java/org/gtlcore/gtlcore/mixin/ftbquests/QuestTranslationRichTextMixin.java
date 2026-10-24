package org.gtlcore.gtlcore.mixin.ftbquests;

import org.gtlcore.gtlcore.client.ftbquests.QuestTranslationRichEditor;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.client.gui.MultilineTextEditorScreen", remap = false)
public abstract class QuestTranslationRichTextMixin {

    @Inject(method = "doLinkInsertion", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$link(long destination, CallbackInfo ci) {
        QuestTranslationRichEditor.link(this, destination);
        ci.cancel();
    }

    @Inject(method = "insertFormatting(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$format(String format, CallbackInfo ci) {
        if (QuestTranslationRichEditor.format(this, format)) ci.cancel();
    }

    @Inject(method = "resetFormatting", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$reset(CallbackInfo ci) {
        if (QuestTranslationRichEditor.format(this, "&r")) ci.cancel();
    }

    // A cursor at the end of a physical line still belongs to that line.
    // FTB's strict upper bound otherwise makes links/JSON conversion silently do nothing
    // when selecting through the last character, including Ctrl+A on a single paragraph.
    @ModifyExpressionValue(method = "getPhysicalLineExtents",
                           at = @At(value = "INVOKE", target = "Ljava/lang/String;length()I", ordinal = 0),
                           remap = false)
    private int gtlcore$includeLineEnd(int length) {
        return length + 1;
    }
}
