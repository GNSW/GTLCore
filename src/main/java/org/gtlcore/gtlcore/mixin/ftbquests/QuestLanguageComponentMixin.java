package org.gtlcore.gtlcore.mixin.ftbquests;

import org.gtlcore.gtlcore.client.ftbquests.QuestTranslationsClient;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A translated child can occur anywhere in a component tree, including JSON click-event text. */
@Mixin(MutableComponent.class)
public abstract class QuestLanguageComponentMixin {

    @Shadow
    private Language decomposedWith;
    @Unique
    private long gtlcore$translationGeneration = -1;

    @Inject(method = "getVisualOrderText", at = @At("HEAD"))
    private void gtlcore$refreshVisualOrder(CallbackInfoReturnable<FormattedCharSequence> cir) {
        long generation = QuestTranslationsClient.generation();
        if (gtlcore$translationGeneration != generation) {
            gtlcore$translationGeneration = generation;
            decomposedWith = null;
        }
    }
}
