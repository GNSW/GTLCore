package org.gtlcore.gtlcore.mixin.bloom;

import org.gtlcore.gtlcore.client.bloom.EmissiveAliases;

import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** SpriteResourceLoader is the official 1.20.1 name for the atlas sprite-source list. */
@Mixin(SpriteResourceLoader.class)
public abstract class SpriteSourceListAliasMixin {

    @Shadow
    @Final
    private List<SpriteSource> sources;

    @Inject(method = "load", at = @At("RETURN"))
    private static void gtlcore$bloom$appendAliases(ResourceManager resources, ResourceLocation atlas,
                                                    CallbackInfoReturnable<SpriteResourceLoader> cir) {
        SpriteSourceListAliasMixin loaded = (SpriteSourceListAliasMixin) (Object) cir.getReturnValue();
        EmissiveAliases.appendSources(resources, atlas, loaded.sources);
    }
}
