package org.gtlcore.gtlcore.mixin.mc.client;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderType.CompositeState.class)
public interface MonitorRenderStateAccessor {

    @Accessor("textureState")
    RenderStateShard.EmptyTextureStateShard gtlcore$getMonitorTextureState();
}
