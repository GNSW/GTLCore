package org.gtlcore.gtlcore.client.bloom;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.glBindFragDataLocation;

/** Private GL programs: never rewrite vanilla, Embeddium or a shader pack's shader source. */
final class GlProgram implements AutoCloseable {

    private final int id;
    private final Map<String, Integer> uniforms = new HashMap<>();

    GlProgram(String vertex, String fragment) throws IOException {
        int vs = compile(GL_VERTEX_SHADER, vertex), fs = 0, program = 0;
        try {
            fs = compile(GL_FRAGMENT_SHADER, fragment);
            program = glCreateProgram();
            glAttachShader(program, vs);
            glAttachShader(program, fs);
            glBindAttribLocation(program, 0, "Position");
            glBindAttribLocation(program, 1, "Color");
            glBindAttribLocation(program, 2, "UV0");
            glBindFragDataLocation(program, 0, "fragColor");
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
                throw new IOException("Bloom program link failed: " + glGetProgramInfoLog(program));
            }
            id = program;
        } catch (IOException | RuntimeException e) {
            if (program != 0) glDeleteProgram(program);
            throw e;
        } finally {
            glDeleteShader(vs);
            if (fs != 0) glDeleteShader(fs);
        }
    }

    private static int compile(int type, String file) throws IOException {
        var location = new ResourceLocation(GTLCore.MOD_ID, "shaders/bloom/" + file);
        String text;
        try (var input = Minecraft.getInstance().getResourceManager().open(location)) {
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        int shader = glCreateShader(type);
        glShaderSource(shader, text);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
            String error = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IOException(location + ": " + error);
        }
        return shader;
    }

    void use() {
        glUseProgram(id);
    }

    private int location(String name) {
        return uniforms.computeIfAbsent(name, n -> glGetUniformLocation(id, n));
    }

    void integer(String name, int x) {
        glUniform1i(location(name), x);
    }

    void scalar(String name, float x) {
        glUniform1f(location(name), x);
    }

    void vec2(String name, float x, float y) {
        glUniform2f(location(name), x, y);
    }

    void vec3(String name, float x, float y, float z) {
        glUniform3f(location(name), x, y, z);
    }

    void matrix(String name, Matrix4f matrix) {
        try (var stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(location(name), false, matrix.get(stack.mallocFloat(16)));
        }
    }

    @Override
    public void close() {
        glDeleteProgram(id);
    }
}
