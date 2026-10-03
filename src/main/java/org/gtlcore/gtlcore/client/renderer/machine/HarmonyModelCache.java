package org.gtlcore.gtlcore.client.renderer.machine;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraftforge.client.model.data.ModelData;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Immutable, full-bright OBJ geometry. Instances contain only their original render transforms. */
final class HarmonyModelCache implements AutoCloseable {

    static final String[] NAMES = { "the_nether", "overworld", "the_end", "space", "star" };
    private static final int STRIDE = DefaultVertexFormat.BLOCK.getVertexSize();
    private static final int MAX_INSTANCES = 4096;
    // BufferBuilder owns native memory without a close method. Keep bounded scratch buffers
    // across models and resource reloads instead of abandoning native allocations on every bake.
    private static BufferBuilder quadBuilder, triangleBuilder;
    final BakedModel model;
    final RenderType type;
    final List<Matrix4f> transforms = new ArrayList<>();
    final List<Matrix4f> views = new ArrayList<>();
    int instances;
    private VertexBuffer vertices;
    private boolean attempted;
    private boolean shaderAttempted;
    private HarmonyShaderMesh shaderMesh;
    private float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
    private float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
    private final Matrix4f clip = new Matrix4f();
    private final String name;

    HarmonyModelCache(String name, BakedModel model) {
        this.name = name;
        this.model = model;
        type = name.equals("star") ? RenderType.translucent() : RenderType.solid();
    }

    boolean prepare(ModelBlockRenderer renderer) {
        if (!attempted) {
            attempted = true;
            try {
                if (supportsResources()) compile(renderer);
            } catch (RuntimeException exception) {
                close();
                GTLCore.LOGGER.warn("Could not cache harmony model {}; using the regular model renderer", name, exception);
            }
        }
        return vertices != null;
    }

    boolean writeShader(PoseStack.Pose pose, VertexConsumer consumer) {
        if (!HarmonyShaderMesh.accepts(consumer)) return false;
        if (!shaderAttempted) {
            shaderAttempted = true;
            if (supportsResources()) shaderMesh = HarmonyShaderMesh.create(model, type);
        }
        if (shaderMesh == null) return false;
        // Submit immediately, including during shadow rendering. Do not use the main camera's
        // culling or deferred draw queue: a shader can displace vertices or render another view.
        shaderMesh.write(pose, consumer);
        return true;
    }

    private static boolean original(ResourceManager resources, ResourceLocation location) {
        return resources.getResource(location)
                .map(resource -> resource.sourcePackId().equals("mod_resources") ||
                        resource.sourcePackId().equals("mod:" + location.getNamespace()))
                .orElse(false);
    }

    private boolean supportsResources() {
        // Packs may supply dynamic geometry, animated sprites or a genuinely translucent star.
        // Keep their original renderer, including quad sorting and sprite activation, in that case.
        if (model.getClass() != SimpleBakedModel.class) return false;
        var resources = Minecraft.getInstance().getResourceManager();
        String mesh = name.equals("space") ? "space" : "star";
        if (!original(resources, GTLCore.id("models/obj/" + name + ".json")) ||
                !original(resources, GTLCore.id("models/obj/" + mesh + ".obj")) ||
                !original(resources, GTLCore.id("models/obj/" + mesh + ".mtl")))
            return false;
        Set<TextureAtlasSprite> sprites = Collections.newSetFromMap(new IdentityHashMap<>());
        RandomSource random = RandomSource.create(42);
        for (Direction direction : Direction.values()) {
            random.setSeed(42);
            for (BakedQuad quad : model.getQuads(null, direction, random, ModelData.EMPTY, type)) {
                sprites.add(quad.getSprite());
            }
        }
        random.setSeed(42);
        for (BakedQuad quad : model.getQuads(null, null, random, ModelData.EMPTY, type)) {
            sprites.add(quad.getSprite());
        }
        if (sprites.isEmpty()) return false;
        for (var sprite : sprites) {
            var id = sprite.contents().name();
            if (!original(resources, new ResourceLocation(id.getNamespace(), "textures/" + id.getPath() + ".png")) ||
                    sprite.contents().getUniqueFrames().count() != 1)
                return false;
            // The bundled star is an opaque JPEG, although it uses the translucent render type.
            if (name.equals("star") && !id.equals(GTLCore.id("block/obj/star_layer"))) return false;
        }
        return true;
    }

    private void compile(ModelBlockRenderer renderer) {
        if (quadBuilder == null) quadBuilder = new BufferBuilder(1 << 20);
        if (triangleBuilder == null) triangleBuilder = new BufferBuilder(1 << 20);
        var builder = quadBuilder;
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        renderer.renderModel(new PoseStack().last(), builder, null, model, 1, 1, 1,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, type);
        var data = builder.end();
        try {
            int count = data.drawState().vertexCount();
            if (count == 0 || count % 4 != 0 || data.drawState().format() != DefaultVertexFormat.BLOCK) return;
            ByteBuffer source = data.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
            // Real quads keep both triangles. Only byte-identical padded OBJ vertices can be removed.
            ByteBuffer triangles = MemoryUtil.memAlloc(Math.multiplyExact(count / 4, 6 * STRIDE));
            try {
                for (int quad = 0; quad < count; quad += 4) {
                    int offset = quad * STRIDE;
                    for (int vertex = 0; vertex < 4; vertex++) {
                        int base = offset + vertex * STRIDE;
                        float x = source.getFloat(base), y = source.getFloat(base + 4), z = source.getFloat(base + 8);
                        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) ||
                                (source.get(base + 15) & 255) != 255)
                            return;
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        minZ = Math.min(minZ, z);
                        maxX = Math.max(maxX, x);
                        maxY = Math.max(maxY, y);
                        maxZ = Math.max(maxZ, z);
                    }
                    copyVertex(triangles, source, offset);
                    copyVertex(triangles, source, offset + STRIDE);
                    copyVertex(triangles, source, offset + 2 * STRIDE);
                    if (!sameVertex(source, offset + 2 * STRIDE, offset + 3 * STRIDE)) {
                        copyVertex(triangles, source, offset + 2 * STRIDE);
                        copyVertex(triangles, source, offset + 3 * STRIDE);
                        copyVertex(triangles, source, offset);
                    }
                }
                triangles.flip();
                var compact = triangleBuilder;
                compact.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.BLOCK);
                compact.putBulkData(triangles);
                var compiled = compact.end();
                vertices = new VertexBuffer(VertexBuffer.Usage.STATIC);
                vertices.bind();
                try {
                    vertices.upload(compiled);
                } finally {
                    VertexBuffer.unbind();
                }
            } finally {
                MemoryUtil.memFree(triangles);
            }
        } finally {
            data.release();
        }
    }

    private static boolean sameVertex(ByteBuffer bytes, int first, int second) {
        for (int i = 0; i < STRIDE; i++) {
            if (bytes.get(first + i) != bytes.get(second + i)) return false;
        }
        return true;
    }

    private static void copyVertex(ByteBuffer to, ByteBuffer from, int offset) {
        to.put(to.position(), from, offset, STRIDE);
        to.position(to.position() + STRIDE);
    }

    boolean enqueue(Matrix4f pose, Matrix4f modelView, Matrix4f projection) {
        clip.set(projection).mul(modelView).mul(pose);
        if (!clip.testAab(minX, minY, minZ, maxX, maxY, maxZ)) return true;
        // This is a cache capacity limit, not a visibility limit: overflow uses the original path.
        if (instances == MAX_INSTANCES) return false;
        if (instances == transforms.size()) {
            transforms.add(new Matrix4f());
            views.add(new Matrix4f());
        }
        // Vanilla transforms positions on the CPU before its shader computes cylindrical fog.
        // Keep these transforms separate: folding the model pose into ModelViewMat changes fog.
        transforms.get(instances).set(pose);
        views.get(instances++).set(modelView);
        return true;
    }

    void bind() {
        vertices.bind();
    }

    void draw() {
        vertices.draw();
    }

    @Override
    public void close() {
        instances = 0;
        transforms.clear();
        views.clear();
        if (vertices != null) {
            vertices.close();
            vertices = null;
        }
        if (shaderMesh != null) {
            shaderMesh.close();
            shaderMesh = null;
        }
    }
}
