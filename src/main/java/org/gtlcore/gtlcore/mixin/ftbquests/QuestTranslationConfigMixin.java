package org.gtlcore.gtlcore.mixin.ftbquests;

import org.gtlcore.gtlcore.client.ftbquests.QuestTranslationAccess;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = { "dev.ftb.mods.ftbquests.quest.QuestObjectBase", "dev.ftb.mods.ftbquests.quest.Quest",
        "dev.ftb.mods.ftbquests.quest.Chapter" },
       remap = false)
public abstract class QuestTranslationConfigMixin {

    @Inject(method = "fillConfigGroup", at = @At("TAIL"), remap = false)
    private void gtlcore$translationEditor(@Coerce Object config, CallbackInfo ci) {
        QuestTranslationAccess.configure(this, config);
    }
}
