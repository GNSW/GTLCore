package org.gtlcore.gtlcore.mixin.ftbquests;

import org.gtlcore.gtlcore.client.ftbquests.FtbQuestsMissingHighlight;

import net.minecraft.network.chat.Component;

import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftblibrary.ui.Panel;
import dev.ftb.mods.ftblibrary.ui.SimpleButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.client.gui.quests.OtherButtonsPanelTop", remap = false)
public abstract class FtbQuestsHighlightButtonMixin {

    @Inject(method = "addWidgets", at = @At("RETURN"), remap = false)
    private void gtlcore$addHighlightButton(CallbackInfo ci) {
        Panel panel = (Panel) (Object) this;
        SimpleButton button = new SimpleButton(panel,
                Component.translatable("button.gtlcore.ftbquests.highlight_missing"),
                Icon.getIcon("minecraft:textures/item/spyglass.png"),
                (clicked, mouseButton) -> FtbQuestsMissingHighlight.activate());
        button.setSize(20, 18);
        panel.add(button);
    }
}
