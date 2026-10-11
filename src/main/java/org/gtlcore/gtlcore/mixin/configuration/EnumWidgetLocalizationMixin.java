package org.gtlcore.gtlcore.mixin.configuration;

import org.gtlcore.gtlcore.config.AEGraphInventoryLockBehavior;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import dev.toma.configuration.client.widget.EnumWidget;
import dev.toma.configuration.config.value.EnumValue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = EnumWidget.class, remap = false)
public abstract class EnumWidgetLocalizationMixin {

    @Shadow
    @Final
    private EnumValue<?> value;

    @Inject(method = "updateText", at = @At("RETURN"))
    private void gtlcore$translateInventoryLockBehavior(CallbackInfo ci) {
        if (value.get() instanceof AEGraphInventoryLockBehavior behavior) {
            ((AbstractWidget) (Object) this).setMessage(Component.translatable(behavior.translationKey()));
        }
    }
}
