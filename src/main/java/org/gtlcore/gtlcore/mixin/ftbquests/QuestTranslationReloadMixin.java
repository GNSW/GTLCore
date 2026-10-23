package org.gtlcore.gtlcore.mixin.ftbquests;

import org.gtlcore.gtlcore.integration.ftbquests.QuestTranslationNetwork;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.quest.ServerQuestFile", remap = false)
public abstract class QuestTranslationReloadMixin {

    @Shadow
    @Final
    public MinecraftServer server;

    @Inject(method = "load", at = @At("TAIL"), remap = false)
    private void gtlcore$reloadTranslations(CallbackInfo ci) {
        QuestTranslationNetwork.questFileReloaded(this, server);
    }
}
