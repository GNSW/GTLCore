package org.gtlcore.gtlcore.mixin.ftbquests;

import org.gtlcore.gtlcore.client.ftbquests.QuestTranslationsClient;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.contents.TranslatableContents;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TranslatableContents.class)
public abstract class QuestLanguageContentsMixin {

    @Shadow
    @Final
    private String key;
    @Shadow
    private Language decomposedWith;
    @Unique
    private long gtlcore$translationGeneration = -1;

    @Inject(method = "decompose", at = @At("HEAD"))
    private void gtlcore$refreshQuestTranslation(CallbackInfo ci) {
        long generation = QuestTranslationsClient.generation();
        if (gtlcore$translationGeneration != generation) {
            gtlcore$translationGeneration = generation;
            if (key.startsWith("gtlcore.quest.")) decomposedWith = null;
        }
    }
}
