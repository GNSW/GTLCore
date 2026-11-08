package org.gtlcore.gtlcore.mixin.ftbquests;

import org.gtlcore.gtlcore.client.ftbquests.FtbQuestsMissingHighlight;

import net.minecraft.client.gui.GuiGraphics;

import dev.ftb.mods.ftblibrary.ui.Theme;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.client.gui.quests.QuestButton", remap = false)
public abstract class FtbQuestsHighlightButtonRenderMixin {

    @Inject(method = "draw", at = @At("HEAD"), remap = false)
    private void gtlcore$drawMissingHighlight(GuiGraphics graphics, Theme theme, int x, int y, int width, int height,
                                              CallbackInfo ci) {
        FtbQuestsMissingHighlight.drawFor(this, graphics, x, y, width, height);
    }
}
