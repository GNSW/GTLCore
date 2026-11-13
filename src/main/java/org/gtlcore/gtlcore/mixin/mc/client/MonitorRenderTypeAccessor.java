package org.gtlcore.gtlcore.mixin.mc.client;

import net.minecraft.client.renderer.RenderType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public interface MonitorRenderTypeAccessor {

    @Accessor("state")
    RenderType.CompositeState gtlcore$getMonitorState();
}
