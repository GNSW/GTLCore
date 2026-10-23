package org.gtlcore.gtlcore.mixin.ftbquests;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.client.gui.MultilineTextEditorScreen", remap = false)
public abstract class QuestTranslationRichTextMixin {

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
