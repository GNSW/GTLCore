package org.gtlcore.gtlcore.client.monitor;

import org.gtlcore.gtlcore.mixin.mc.client.MonitorRenderStateAccessor;
import org.gtlcore.gtlcore.mixin.mc.client.MonitorRenderTypeAccessor;
import org.gtlcore.gtlcore.mixin.mc.client.MonitorTextureStateInvoker;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps native item models and textures while submitting their lit faces as screen content. */
final class MachineMonitorIconRenderer implements MultiBufferSource {

    // Foil and custom item renderers can hold several consumers at once, so each layer needs its own builder.
    private final Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
    private final MultiBufferSource.BufferSource buffers = MultiBufferSource.immediateWithBuffers(builders,
            new BufferBuilder(RenderType.textBackground().bufferSize()));

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        RenderType screenType = screenType(type);
        builders.computeIfAbsent(screenType, layer -> new BufferBuilder(layer.bufferSize()));
        VertexConsumer buffer = buffers.getBuffer(screenType);
        return screenType == type ? buffer : new ScreenVertices(buffer);
    }

    void endBatch() {
        buffers.endBatch();
    }

    private static RenderType screenType(RenderType type) {
        // Glint and other effects without a lightmap keep their native render state.
        if (type.mode() != VertexFormat.Mode.QUADS || !type.format().hasUV(2) ||
                !(type instanceof MonitorRenderTypeAccessor access))
            return type;
        if (!type.format().hasUV(0)) return RenderType.textBackground();
        var state = (MonitorRenderStateAccessor) (Object) access.gtlcore$getMonitorState();
        var texture = (MonitorTextureStateInvoker) state.gtlcore$getMonitorTextureState();
        return texture.gtlcore$getMonitorTexture().map(RenderType::text).orElse(type);
    }

    private record ScreenVertices(VertexConsumer delegate) implements VertexConsumer {

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            delegate.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            delegate.color(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            delegate.uv(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            delegate.uv2(LightTexture.FULL_BRIGHT);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {
            delegate.endVertex();
        }

        @Override
        public void defaultColor(int red, int green, int blue, int alpha) {
            delegate.defaultColor(red, green, blue, alpha);
        }

        @Override
        public void unsetDefaultColor() {
            delegate.unsetDefaultColor();
        }
    }
}
