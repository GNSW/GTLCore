package org.gtlcore.gtlcore.client.bloom;

import org.lwjgl.opengl.ARBDrawBuffersBlend;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33C.*;

/** Raw GL calls leave Mojang/Iris state caches untouched; restore the actual state before returning. */
final class GlState implements AutoCloseable {

    private final int program = glGetInteger(GL_CURRENT_PROGRAM);
    private final int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
    private final int arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
    private final int readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
    private final int drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
    private final int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
    private final int cullFace = glGetInteger(GL_CULL_FACE_MODE), frontFace = glGetInteger(GL_FRONT_FACE);
    private final int[] viewport = new int[4];
    private final int[] textures = new int[6], samplers = new int[6];
    private final int[] caps = { GL_DEPTH_TEST, GL_CULL_FACE, GL_SCISSOR_TEST, GL_STENCIL_TEST, GL_FRAMEBUFFER_SRGB,
            GL_POLYGON_OFFSET_FILL, GL_RASTERIZER_DISCARD, GL_COLOR_LOGIC_OP };
    private final boolean blend = glIsEnabledi(GL_BLEND, 0);
    private final boolean[] enabled = new boolean[caps.length];
    private final int srcRgb = glGetInteger(GL_BLEND_SRC_RGB), dstRgb = glGetInteger(GL_BLEND_DST_RGB);
    private final int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
    private final int eqRgb = glGetInteger(GL_BLEND_EQUATION_RGB), eqAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA);
    private final boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
    private final boolean[] colorMask = new boolean[4];
    private final float[] clearColor = new float[4];

    GlState() {
        glGetIntegerv(GL_VIEWPORT, viewport);
        glGetFloatv(GL_COLOR_CLEAR_VALUE, clearColor);
        try (var stack = MemoryStack.stackPush()) {
            ByteBuffer mask = stack.malloc(4);
            glGetBooleanv(GL_COLOR_WRITEMASK, mask);
            for (int i = 0; i < 4; i++) colorMask[i] = mask.get(i) != 0;
        }
        for (int i = 0; i < caps.length; i++) enabled[i] = glIsEnabled(caps[i]);
        for (int i = 0; i < textures.length; i++) {
            glActiveTexture(GL_TEXTURE0 + i);
            textures[i] = glGetInteger(GL_TEXTURE_BINDING_2D);
            samplers[i] = glGetInteger(GL_SAMPLER_BINDING);
        }
        glActiveTexture(activeTexture);
    }

    void prepare() {
        for (int cap : caps) glDisable(cap);
        glDisablei(GL_BLEND, 0);
        glDepthMask(false);
        glColorMaski(0, true, true, true, true);
        for (int i = 0; i < samplers.length; i++) glBindSampler(i, 0);
        glActiveTexture(GL_TEXTURE0);
    }

    void additive() {
        glEnablei(GL_BLEND, 0);
        blendState(GL_ONE, GL_ONE, GL_ONE, GL_ONE, GL_FUNC_ADD, GL_FUNC_ADD);
        glColorMaski(0, true, true, true, false);
    }

    private static void blendState(int sr, int dr, int sa, int da, int er, int ea) {
        // Do not overwrite a shader pack's independent blend settings for other MRT attachments.
        if (GL.getCapabilities().OpenGL40) {
            GL40C.glBlendFuncSeparatei(0, sr, dr, sa, da);
            GL40C.glBlendEquationSeparatei(0, er, ea);
        } else if (GL.getCapabilities().GL_ARB_draw_buffers_blend) {
            ARBDrawBuffersBlend.glBlendFuncSeparateiARB(0, sr, dr, sa, da);
            ARBDrawBuffersBlend.glBlendEquationSeparateiARB(0, er, ea);
        } else {
            glBlendFuncSeparate(sr, dr, sa, da);
            glBlendEquationSeparate(er, ea);
        }
    }

    @Override
    public void close() {
        glUseProgram(program);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        for (int i = 0; i < textures.length; i++) {
            glActiveTexture(GL_TEXTURE0 + i);
            glBindTexture(GL_TEXTURE_2D, textures[i]);
            glBindSampler(i, samplers[i]);
        }
        glActiveTexture(activeTexture);
        blendState(srcRgb, dstRgb, srcAlpha, dstAlpha, eqRgb, eqAlpha);
        if (blend) glEnablei(GL_BLEND, 0);
        else glDisablei(GL_BLEND, 0);
        glCullFace(cullFace);
        glFrontFace(frontFace);
        for (int i = 0; i < caps.length; i++) {
            if (enabled[i]) glEnable(caps[i]);
            else glDisable(caps[i]);
        }
        glDepthMask(depthMask);
        glColorMaski(0, colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
    }
}
