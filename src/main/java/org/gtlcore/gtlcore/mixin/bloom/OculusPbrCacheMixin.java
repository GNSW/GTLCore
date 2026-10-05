package org.gtlcore.gtlcore.mixin.bloom;

import org.gtlcore.gtlcore.client.bloom.ShaderEmissionBridge;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Covers our toggle and Oculus' own F3+T/shader-reload cache clears. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.texture.pbr.PBRTextureManager", remap = false)
public abstract class OculusPbrCacheMixin {

    @Inject(method = "clear()V", at = @At("HEAD"), require = 1)
    private void gtlcore$bloom$cacheClearing(CallbackInfo ci) {
        ShaderEmissionBridge.pbrCacheClearing();
    }

    @Inject(method = "clear()V", at = @At("RETURN"), require = 1)
    private void gtlcore$bloom$cacheCleared(CallbackInfo ci) {
        ShaderEmissionBridge.pbrCacheCleared();
    }
}
