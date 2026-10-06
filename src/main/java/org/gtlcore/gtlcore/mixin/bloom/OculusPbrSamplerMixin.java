package org.gtlcore.gtlcore.mixin.bloom;

import org.gtlcore.gtlcore.client.bloom.PbrSamplerState;
import org.gtlcore.gtlcore.client.bloom.ShaderEmissionBridge;

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rebind before any world setup/prepare pass, after Oculus makes its binding path operational.
 * A per-pipeline epoch also covers dormant dimension pipelines and newly created pipelines.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class OculusPbrSamplerMixin implements PbrSamplerState {

    @Shadow
    private int currentNormalTexture;
    @Shadow
    private int currentSpecularTexture;

    @Shadow
    public abstract void onSetShaderTexture(int id);

    @Unique
    private long gtlcore$bloom$seenPbrEpoch = Long.MIN_VALUE;

    @Override
    public void gtlcore$bloom$resetPbrSamplers(int normal, int specular) {
        currentNormalTexture = normal;
        currentSpecularTexture = specular;
    }

    @Inject(method = "beginLevelRendering()V",
            at = @At(value = "INVOKE",
                     target = "Lcom/mojang/blaze3d/systems/RenderSystem;activeTexture(I)V",
                     ordinal = 0,
                     shift = At.Shift.AFTER),
            require = 1)
    private void gtlcore$bloom$rebindPbr(CallbackInfo ci) {
        long epoch = ShaderEmissionBridge.pbrCacheEpoch();
        if (gtlcore$bloom$seenPbrEpoch != epoch) {
            // Here isRenderingWorld is true and texture unit 0 is active. A tick-time call is a no-op.
            onSetShaderTexture(RenderSystem.getShaderTexture(0));
            gtlcore$bloom$seenPbrEpoch = epoch;
        }
    }
}
