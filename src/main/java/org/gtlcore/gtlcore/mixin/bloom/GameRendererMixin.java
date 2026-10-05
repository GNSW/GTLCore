package org.gtlcore.gtlcore.mixin.bloom;

import org.gtlcore.gtlcore.client.bloom.BloomClient;

import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void gtlcore$bloom$begin(CallbackInfo ci) {
        BloomClient.beginFrame();
    }

    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
                     shift = At.Shift.AFTER))
    private void gtlcore$bloom$afterWorld(CallbackInfo ci) {
        BloomClient.afterLevel();
    }
}
