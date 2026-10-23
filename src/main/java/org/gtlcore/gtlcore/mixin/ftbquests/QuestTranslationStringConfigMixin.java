package org.gtlcore.gtlcore.mixin.ftbquests;

import org.gtlcore.gtlcore.client.ftbquests.QuestTranslationNativeEditor;

import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftblibrary.config.StringConfig", remap = false)
public abstract class QuestTranslationStringConfigMixin {

    @Inject(method = "getStringForGUI(Ljava/lang/String;)Lnet/minecraft/network/chat/Component;", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$translatedFieldValue(String value, CallbackInfoReturnable<Component> cir) {
        Component translated = QuestTranslationNativeEditor.display(this, value);
        if (translated != null) cir.setReturnValue(translated);
    }

    @Inject(method = "addInfo", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$translatedFieldTooltip(@Coerce Object list, CallbackInfo ci) {
        if (QuestTranslationNativeEditor.tooltip(this, list)) ci.cancel();
    }
}
