package org.gtlcore.gtlcore.mixin.bloom;

import org.gtlcore.gtlcore.client.bloom.ShaderEmissionBridge;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** Audited optional Oculus 1.7.0/1.8.0 hook. No global ResourceManager interception. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.texture.pbr.loader.AtlasPBRLoader", remap = false)
public abstract class OculusAtlasEmissionMixin {

    private static final String LOAD = "load(Lnet/minecraft/client/renderer/texture/TextureAtlas;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/irisshaders/iris/texture/pbr/loader/PBRTextureLoader$PBRTextureConsumer;)V";

    @Inject(method = LOAD, at = @At("HEAD"), require = 1)
    private void gtlcore$bloom$begin(TextureAtlas atlas, ResourceManager resources, @Coerce Object consumer, CallbackInfo ci) {
        ShaderEmissionBridge.beginAtlas(atlas);
    }

    @Redirect(method = "createPBRSprite", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/ResourceManager;getResource(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;", remap = true), require = 1)
    private Optional<Resource> gtlcore$bloom$resource(ResourceManager resources, ResourceLocation location) {
        return ShaderEmissionBridge.resource(resources, location);
    }

    @Inject(method = "createPBRSprite", at = @At("RETURN"), require = 1)
    private void gtlcore$bloom$decoded(TextureAtlasSprite sprite, ResourceManager resources, TextureAtlas atlas,
                                       int width, int height, int mip, @Coerce Object type, CallbackInfoReturnable<Object> cir) {
        ShaderEmissionBridge.decodedSprite(sprite.contents().name(), cir.getReturnValue() != null && type.toString().equals("SPECULAR"));
        ShaderEmissionBridge.decodedNormalSprite(sprite.contents().name(), cir.getReturnValue() != null && type.toString().equals("NORMAL"));
    }

    @Inject(method = LOAD, at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/texture/pbr/loader/PBRTextureLoader$PBRTextureConsumer;acceptSpecularTexture(Lnet/minecraft/client/renderer/texture/AbstractTexture;)V"), require = 1)
    private void gtlcore$bloom$uploaded(TextureAtlas atlas, ResourceManager resources, @Coerce Object consumer, CallbackInfo ci) {
        ShaderEmissionBridge.uploadedAtlas();
    }

    @Inject(method = LOAD, at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/texture/pbr/loader/PBRTextureLoader$PBRTextureConsumer;acceptNormalTexture(Lnet/minecraft/client/renderer/texture/AbstractTexture;)V"), require = 1)
    private void gtlcore$bloom$uploadedNormal(TextureAtlas atlas, ResourceManager resources, @Coerce Object consumer, CallbackInfo ci) {
        ShaderEmissionBridge.uploadedNormalAtlas();
    }

    @Inject(method = LOAD, at = @At("RETURN"), require = 1)
    private void gtlcore$bloom$end(TextureAtlas atlas, ResourceManager resources, @Coerce Object consumer, CallbackInfo ci) {
        ShaderEmissionBridge.endAtlas();
    }
}
