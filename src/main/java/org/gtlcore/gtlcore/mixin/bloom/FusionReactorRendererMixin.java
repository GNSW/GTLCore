package org.gtlcore.gtlcore.mixin.bloom;

import org.gtlcore.gtlcore.client.bloom.BloomClient;

import net.minecraft.client.renderer.MultiBufferSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Pseudo
@Mixin(targets = "com.gregtechceu.gtceu.client.renderer.machine.FusionReactorRenderer", remap = false)
public abstract class FusionReactorRendererMixin {

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0, remap = false)
    private MultiBufferSource gtlcore$bloom$captureRing(MultiBufferSource original) {
        return BloomClient.RINGS.wrap(original);
    }
}
