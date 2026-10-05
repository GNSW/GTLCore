package org.gtlcore.gtlcore.client.bloom;

import com.mojang.blaze3d.vertex.BufferBuilder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33C.*;

/** Own VAO/VBO, with the ordinary 32-byte BLOCK vertex layout. No backend vertex-format patch. */
final class RawMesh implements AutoCloseable {

    private final int vao, vbo, ebo, count;
    private final long bytes;

    private RawMesh(int vao, int vbo, int ebo, int count, long bytes) {
        this.vao = vao;
        this.vbo = vbo;
        this.ebo = ebo;
        this.count = count;
        this.bytes = bytes;
    }

    static RawMesh upload(BufferBuilder.RenderedBuffer data, boolean quads) {
        if (data.drawState().format().getVertexSize() != 32) throw new IllegalArgumentException("Expected BLOCK format");
        return uploadVertices(data.vertexBuffer(), quads);
    }

    static RawMesh uploadVertices(ByteBuffer data, boolean quads) {
        int oldVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int oldBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        int vao = 0, vbo = 0, ebo = 0;
        try {
            vao = glGenVertexArrays();
            vbo = glGenBuffers();
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
            int stride = 32;
            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
            glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, stride, 12L);
            glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 16L);
            glEnableVertexAttribArray(0);
            glEnableVertexAttribArray(1);
            glEnableVertexAttribArray(2);
            int vertices = data.remaining() / stride, count = vertices;
            long bytes = (long) vertices * stride;
            if (quads) {
                if ((vertices & 3) != 0) throw new IllegalArgumentException("Incomplete quad");
                count = vertices / 4 * 6;
                IntBuffer indices = MemoryUtil.memAllocInt(count);
                try {
                    for (int i = 0; i < vertices; i += 4) indices.put(i).put(i + 1).put(i + 2).put(i + 2).put(i + 3).put(i);
                    indices.flip();
                    ebo = glGenBuffers();
                    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
                    glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
                } finally {
                    MemoryUtil.memFree(indices);
                }
                bytes += (long) count * 4;
            }
            return new RawMesh(vao, vbo, ebo, count, bytes);
        } catch (RuntimeException e) {
            if (vao != 0) glDeleteVertexArrays(vao);
            if (vbo != 0) glDeleteBuffers(vbo);
            if (ebo != 0) glDeleteBuffers(ebo);
            throw e;
        } finally {
            glBindVertexArray(oldVao);
            glBindBuffer(GL_ARRAY_BUFFER, oldBuffer);
        }
    }

    /** Patch a stable quad slot without reallocating the VAO, VBO or index buffer. */
    void updateVertices(int byteOffset, ByteBuffer data) {
        if (byteOffset < 0 || (long) byteOffset + data.remaining() > (long) count / 6 * 4 * 32)
            throw new IllegalArgumentException("Vertex update outside allocation");
        int previous = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        try {
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferSubData(GL_ARRAY_BUFFER, byteOffset, data);
        } finally {
            glBindBuffer(GL_ARRAY_BUFFER, previous);
        }
    }

    void draw() {
        glBindVertexArray(vao);
        if (ebo != 0) glDrawElements(GL_TRIANGLES, count, GL_UNSIGNED_INT, 0L);
        else glDrawArrays(GL_TRIANGLES, 0, count);
    }

    void drawRanges(int[] counts, long[] offsets, int ranges) {
        glBindVertexArray(vao);
        try (var stack = MemoryStack.stackPush()) {
            var sizes = stack.mallocInt(ranges);
            var starts = stack.mallocPointer(ranges);
            for (int i = 0; i < ranges; i++) {
                sizes.put(counts[i]);
                starts.put(offsets[i]);
            }
            sizes.flip();
            starts.flip();
            glMultiDrawElements(GL_TRIANGLES, sizes, GL_UNSIGNED_INT, starts);
        }
    }

    long bytes() {
        return bytes;
    }

    @Override
    public void close() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        if (ebo != 0) glDeleteBuffers(ebo);
    }
}
