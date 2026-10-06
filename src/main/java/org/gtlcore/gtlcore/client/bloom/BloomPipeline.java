package org.gtlcore.gtlcore.client.bloom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33C.*;

public final class BloomPipeline implements AutoCloseable {

    private GlProgram emission, blur, composite;
    private Target mask;
    private final Target[] horizontal = new Target[4], vertical = new Target[4];
    private int fullscreenVao;
    private int sceneDepth;
    private boolean opaqueDepthReady;

    public void beginFrame() {
        opaqueDepthReady = false;
    }

    /** Preserve opaque occlusion before glass/water can write a nearer depth. */
    public void captureOpaqueDepth() throws IOException {
        if (opaqueDepthReady) return;
        var main = Minecraft.getInstance().getMainRenderTarget();
        // Shader renderers may use a different framebuffer during terrain rendering.
        // Take its depth before init() binds our own framebuffer attachments.
        int source = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        // Fabulous' depth-copy stage can leave framebuffer 0 bound until the terrain
        // RenderType is set up. The opaque main target is still valid at this point.
        if (source == 0 || glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) == GL_NONE) {
            if (main.getDepthTextureId() <= 0) return;
            source = main.frameBufferId;
        }
        if (mask == null || mask.width != main.width || mask.height != main.height) {
            try (var state = new GlState()) {
                state.prepare();
                init(main.width, main.height);
            }
        }
        // A texture copy changes no program, VAO, viewport, blend/depth state or sampler.
        // Save only the bindings it touches instead of querying/restoring the entire
        // render state every frame. Initialization above still uses the full guard.
        int readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE5);
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            copyDepth(source, main.width, main.height);
            opaqueDepthReady = true;
        } finally {
            glBindTexture(GL_TEXTURE_2D, previousTexture);
            glActiveTexture(activeTexture);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
        }
    }

    private void copyDepth(int framebuffer, int width, int height) {
        // Keep an immutable copy: composite writes to main and cannot sample its attachment.
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        texture(5, sceneDepth);
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
    }

    public void render(SectionMeshes meshes, DynamicRings rings, Matrix4f view, Matrix4f projection,
                       Frustum frustum, Vec3 camera, float fogStart, float fogEnd, int fogShape) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        var main = mc.getMainRenderTarget();
        DepthMapping mapping = DepthMapping.current();
        if (mapping == null) return;
        if (main.getDepthTextureId() <= 0) throw new IOException("Main framebuffer has no readable depth texture");
        try (var state = new GlState()) {
            state.prepare();
            init(main.width, main.height);
            // Normally captured before translucent terrain. Fall back only if that render
            // stage was skipped; never reuse another frame's camera/occlusion snapshot.
            if (!opaqueDepthReady) copyDepth(main.frameBufferId, main.width, main.height);
            mask.bind();
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
            emission.use();
            mapping.apply(emission);
            emission.integer("Atlas", 0);
            emission.integer("SceneDepth", 5);
            emission.matrix("Projection", projection);
            emission.scalar("FogStart", fogStart);
            emission.scalar("FogEnd", fogEnd);
            emission.integer("FogShape", fogShape);
            texture(5, sceneDepth);
            texture(0, mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId());
            emission.integer("Textured", 1);
            // Terrain discards back faces. Inside a wall those faces have no scene depth,
            // so depth sampling alone cannot stop their otherwise invisible bloom.
            glCullFace(GL_BACK);
            glFrontFace(GL_CCW);
            glEnable(GL_CULL_FACE);
            boolean terrain = meshes.draw(emission, view, frustum, camera);
            // Dormant casings retain their GPU geometry. An entirely dark mask still needs
            // no blur/composite passes; closing GlState restores the caller's framebuffer.
            if (!terrain && !rings.hasMesh()) return;
            // GTM's light-ring RenderType is explicitly double-sided, as are our screen passes.
            glDisable(GL_CULL_FACE);
            emission.integer("Textured", 0);
            emission.integer("SectionOffsets", 0);
            // Entity vertices already contain the block entity's camera-relative pose transform.
            emission.matrix("ModelView", RenderSystem.getModelViewMatrix());
            emission.vec3("ChunkOffset", 0, 0, 0);
            rings.draw();

            glBindVertexArray(fullscreenVao);
            blur.use();
            blur.integer("Source", 0);
            mapping.apply(blur);
            blur.integer("SceneDepth", 5);
            blur.matrix("Projection", projection);
            int source = mask.texture;
            for (int i = 0; i < 4; i++) {
                blur.integer("Radius", 3 + i * 2);
                horizontal[i].bind();
                texture(0, source);
                blur.vec2("OutSize", horizontal[i].width, horizontal[i].height);
                blur.vec2("BlurDir", 1, 0);
                fullscreen();
                vertical[i].bind();
                texture(0, horizontal[i].texture);
                blur.vec2("OutSize", vertical[i].width, vertical[i].height);
                blur.vec2("BlurDir", 0, 1);
                fullscreen();
                source = vertical[i].texture;
            }

            // Oculus draws its opaque hand before the translucent-terrain capture too,
            // so foreground hand/wall occlusion is retained without treating glass as solid.
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, main.frameBufferId);
            glViewport(0, 0, main.viewWidth, main.viewHeight);
            composite.use();
            mapping.apply(composite);
            composite.integer("SceneDepth", 5);
            composite.matrix("Projection", projection);
            for (int i = 0; i < 4; i++) {
                texture(i, vertical[i].texture);
                composite.integer("Blur" + i, i);
            }
            composite.scalar("Strength", (float) BloomConfig.strength());
            composite.scalar("Radius", (float) BloomConfig.radius());
            state.additive();
            fullscreen();
        }
    }

    private void init(int width, int height) throws IOException {
        if (emission == null) {
            try {
                emission = new GlProgram("emission.vert", "emission.frag");
                blur = new GlProgram("fullscreen.vert", "blur.frag");
                composite = new GlProgram("fullscreen.vert", "composite.frag");
                fullscreenVao = glGenVertexArrays();
            } catch (IOException | RuntimeException e) {
                close();
                throw e;
            }
        }
        if (mask != null && mask.width == width && mask.height == height) return;
        closeTargets();
        try {
            sceneDepth = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, sceneDepth);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, width, height, 0, GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            mask = new Target(width, height);
            for (int i = 0; i < 4; i++) {
                int w = Math.max(1, width >> (i + 1)), h = Math.max(1, height >> (i + 1));
                horizontal[i] = new Target(w, h);
                vertical[i] = new Target(w, h);
            }
        } catch (IOException | RuntimeException e) {
            closeTargets();
            throw e;
        }
    }

    private static void texture(int slot, int id) {
        glActiveTexture(GL_TEXTURE0 + slot);
        glBindTexture(GL_TEXTURE_2D, id);
    }

    private static void fullscreen() {
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private void closeTargets() {
        opaqueDepthReady = false;
        if (sceneDepth != 0) {
            glDeleteTextures(sceneDepth);
            sceneDepth = 0;
        }
        if (mask != null) {
            mask.close();
            mask = null;
        }
        for (int i = 0; i < 4; i++) {
            if (horizontal[i] != null) {
                horizontal[i].close();
                horizontal[i] = null;
            }
            if (vertical[i] != null) {
                vertical[i].close();
                vertical[i] = null;
            }
        }
    }

    @Override
    public void close() {
        DepthMapping.reset();
        closeTargets();
        if (emission != null) {
            emission.close();
            emission = null;
        }
        if (blur != null) {
            blur.close();
            blur = null;
        }
        if (composite != null) {
            composite.close();
            composite = null;
        }
        if (fullscreenVao != 0) {
            glDeleteVertexArrays(fullscreenVao);
            fullscreenVao = 0;
        }
    }

    private static final class Target implements AutoCloseable {

        final int width, height, texture, framebuffer;

        Target(int width, int height) throws IOException {
            this.width = width;
            this.height = height;
            texture = glGenTextures();
            framebuffer = glGenFramebuffers();
            glBindTexture(GL_TEXTURE_2D, texture);
            // Alpha carries surface distance, not opacity. Depth is tested per texel before
            // interpolation, independently of how many nearby emitters are currently on.
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_HALF_FLOAT, (ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                close();
                throw new IOException("Incomplete bloom framebuffer at " + width + "x" + height);
            }
        }

        void bind() {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
            glViewport(0, 0, width, height);
        }

        @Override
        public void close() {
            glDeleteFramebuffers(framebuffer);
            glDeleteTextures(texture);
        }
    }
}
