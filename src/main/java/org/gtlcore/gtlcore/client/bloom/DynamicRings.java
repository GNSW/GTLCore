package org.gtlcore.gtlcore.client.bloom;

import net.minecraft.client.renderer.MultiBufferSource;

import com.mojang.blaze3d.vertex.*;

import java.util.Arrays;

/** Copies the actual GTM ring vertices, retaining its recipe colour, orientation and pulse animation. */
public final class DynamicRings {

    private final BufferBuilder builder = new BufferBuilder(32 * 1024);
    private boolean collecting;
    private RawMesh mesh;
    private int lastVertexCount;

    public void begin() {
        clear();
        builder.begin(VertexFormat.Mode.TRIANGLES, EmissionFormat.BLOCK);
        collecting = true;
    }

    public MultiBufferSource wrap(MultiBufferSource original) {
        if (!collecting || !BloomClient.active() || ShaderCompat.shadowPass()) return original;
        return type -> {
            var consumer = original.getBuffer(type);
            // This wrapper is installed only on FusionReactorRenderer. RenderType.toString()
            // includes implementation-specific prefixes and is not a stable identifier.
            if (type.mode() != VertexFormat.Mode.TRIANGLE_STRIP) return consumer;
            return VertexMultiConsumer.create(consumer, new Strip());
        };
    }

    public void finish() {
        if (!collecting) return;
        collecting = false;
        var result = builder.endOrDiscardIfEmpty();
        lastVertexCount = result == null ? 0 : result.drawState().vertexCount();
        if (result != null) {
            try {
                mesh = RawMesh.upload(result, false);
            } finally {
                result.release();
            }
        }
    }

    public boolean hasMesh() {
        return mesh != null;
    }

    public int lastVertexCount() {
        return lastVertexCount;
    }

    void draw() {
        if (mesh != null) mesh.draw();
    }

    public void clear() {
        if (collecting) {
            var discarded = builder.endOrDiscardIfEmpty();
            if (discarded != null) discarded.release();
            builder.discard();
            collecting = false;
        }
        if (mesh != null) {
            mesh.close();
            mesh = null;
        }
    }

    private final class Strip implements VertexConsumer {

        private final float[][] previous = { new float[7], new float[7] };
        private final float[] current = { 0, 0, 0, 255, 255, 255, 255 };
        private int count;

        public VertexConsumer vertex(double x, double y, double z) {
            current[0] = (float) x;
            current[1] = (float) y;
            current[2] = (float) z;
            return this;
        }

        public VertexConsumer color(int r, int g, int b, int a) {
            current[3] = r;
            current[4] = g;
            current[5] = b;
            current[6] = a;
            return this;
        }

        public VertexConsumer uv(float u, float v) {
            return this;
        }

        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        public void defaultColor(int r, int g, int b, int a) {
            color(r, g, b, a);
        }

        public void unsetDefaultColor() {
            Arrays.fill(current, 3, 7, 255);
        }

        public void endVertex() {
            if (!collecting) return;
            if (count >= 2) {
                emit(previous[count & 1]);
                emit(previous[(count + 1) & 1]);
                emit(current);
            }
            System.arraycopy(current, 0, previous[count & 1], 0, 7);
            count++;
        }

        private void emit(float[] v) {
            builder.vertex(v[0], v[1], v[2]).color((int) v[3], (int) v[4], (int) v[5], (int) v[6])
                    .uv(0, 0).uv2(0x00F000F0).normal(0, 1, 0).endVertex();
        }
    }
}
