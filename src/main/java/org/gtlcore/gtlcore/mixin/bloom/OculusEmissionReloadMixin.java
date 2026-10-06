package org.gtlcore.gtlcore.mixin.bloom;

import org.gtlcore.gtlcore.client.bloom.ShaderEmissionBridge;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Oculus shader reload alone otherwise retains cached PBR textures from the old pack. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public abstract class OculusEmissionReloadMixin {

    @Inject(method = "reload", at = @At("HEAD"), require = 1)
    private static void gtlcore$bloom$reload(CallbackInfo ci) {
        ShaderEmissionBridge.shaderReload();
    }
}
