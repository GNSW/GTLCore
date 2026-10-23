package org.gtlcore.gtlcore.mixin.ftbquests;

import org.gtlcore.gtlcore.client.ftbquests.QuestTranslationsClient;

import net.minecraft.client.resources.language.ClientLanguage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLanguage.class)
public abstract class QuestLanguageLookupMixin {

    @Inject(method = "getOrDefault", at = @At("HEAD"), cancellable = true)
    private void gtlcore$questTranslation(String key, String fallback, CallbackInfoReturnable<String> cir) {
        String value = QuestTranslationsClient.lookup(key);
        // These entries originate in literal quest text, not printf templates. Both FTB's
        // I18n.get and vanilla translatable components interpret percent signs downstream.
        if (value != null) cir.setReturnValue(value.replace("%", "%%"));
    }

    @Inject(method = "has", at = @At("HEAD"), cancellable = true)
    private void gtlcore$hasQuestTranslation(String key, CallbackInfoReturnable<Boolean> cir) {
        if (QuestTranslationsClient.lookup(key) != null) cir.setReturnValue(true);
    }
}
