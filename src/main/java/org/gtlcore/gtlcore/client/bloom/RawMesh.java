package org.gtlcore.gtlcore.client.bloom;

import com.mojang.blaze3d.vertex.BufferBuilder;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33C.*;

/** Own VAO/VBO, with the ordinary 32-byte BLOCK vertex layout. No backend vertex-format patch. */
final class RawMesh implements AutoCloseable {

    private final int vao, vbo, ebo;
    private int count;
    private long bytes;
    private IntBuffer drawCounts;
    private PointerBuffer drawOffsets;

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
        return create(data, data.remaining(), quads, false);
    }

    static RawMesh allocateRegion(int bytes) {
        return create(null, bytes, true, true);
    }

    private static RawMesh create(ByteBuffer data, int size, boolean quads, boolean sectionOffsets) {
        int oldVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int oldBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        int vao = 0, vbo = 0, ebo = 0;
        try {
            vao = glGenVertexArrays();
            vbo = glGenBuffers();
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            if (data == null) glBufferData(GL_ARRAY_BUFFER, (long) size, GL_STATIC_DRAW);
            else glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
            int stride = 32;
            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
            glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, stride, 12L);
            glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 16L);
            glEnableVertexAttribArray(0);
            glEnableVertexAttribArray(1);
            glEnableVertexAttribArray(2);
            if (sectionOffsets) {
                // Local bloom does not use BLOCK normals. Keep section translations in
                // those three bytes, without expanding vertices or rounding their positions.
                glVertexAttribPointer(3, 3, GL_UNSIGNED_BYTE, false, stride, 28L);
                glEnableVertexAttribArray(3);
            }
            int vertices = size / stride, count = vertices;
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

    void copyVerticesTo(RawMesh target, int bytes) {
        int read = glGetInteger(GL_COPY_READ_BUFFER), write = glGetInteger(GL_COPY_WRITE_BUFFER);
        try {
            glBindBuffer(GL_COPY_READ_BUFFER, vbo);
            glBindBuffer(GL_COPY_WRITE_BUFFER, target.vbo);
            glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0, 0, bytes);
        } finally {
            glBindBuffer(GL_COPY_READ_BUFFER, read);
            glBindBuffer(GL_COPY_WRITE_BUFFER, write);
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

    /** Animated rings replace their stream, retaining the VAO/VBO and avoiding GPU read hazards. */
    void streamTriangles(ByteBuffer data) {
        if (ebo != 0 || data.remaining() % (3 * 32) != 0)
            throw new IllegalArgumentException("Expected complete, non-indexed BLOCK triangles");
        int previous = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        try {
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            // Orphan the old storage: previous draws may still be reading it asynchronously.
            glBufferData(GL_ARRAY_BUFFER, data, GL_STREAM_DRAW);
            bytes = data.remaining();
            count = data.remaining() / 32;
        } finally {
            glBindBuffer(GL_ARRAY_BUFFER, previous);
        }
    }

    void draw() {
        glBindVertexArray(vao);
        if (ebo != 0) glDrawElements(GL_TRIANGLES, count, GL_UNSIGNED_INT, 0L);
        else glDrawArrays(GL_TRIANGLES, 0, count);
    }

    void drawRanges(int[] counts, long[] offsets, int ranges, boolean changed) {
        glBindVertexArray(vao);
        if (ranges == 1) {
            glDrawElements(GL_TRIANGLES, counts[0], GL_UNSIGNED_INT, offsets[0]);
            return;
        }
        // Selection changes much less often than drawing. Retain native draw commands
        // instead of copying every range from Java arrays on every unchanged frame.
        if (changed || drawCounts == null || drawCounts.limit() != ranges) {
            if (drawCounts == null || drawCounts.capacity() < ranges) {
                int capacity = Math.max(ranges, drawCounts == null ? 16 : drawCounts.capacity() * 2);
                IntBuffer nextCounts = MemoryUtil.memAllocInt(capacity);
                PointerBuffer nextOffsets;
                try {
                    nextOffsets = MemoryUtil.memAllocPointer(capacity);
                } catch (Throwable failure) {
                    MemoryUtil.memFree(nextCounts);
                    throw failure;
                }
                if (drawCounts != null) MemoryUtil.memFree(drawCounts);
                if (drawOffsets != null) MemoryUtil.memFree(drawOffsets);
                drawCounts = nextCounts;
                drawOffsets = nextOffsets;
            }
            drawCounts.clear();
            drawOffsets.clear();
            for (int i = 0; i < ranges; i++) {
                drawCounts.put(counts[i]);
                drawOffsets.put(offsets[i]);
            }
            drawCounts.flip();
            drawOffsets.flip();
        }
        glMultiDrawElements(GL_TRIANGLES, drawCounts, GL_UNSIGNED_INT, drawOffsets);
    }

    long bytes() {
        return bytes;
    }

    @Override
    public void close() {
        if (drawCounts != null) MemoryUtil.memFree(drawCounts);
        if (drawOffsets != null) MemoryUtil.memFree(drawOffsets);
        drawCounts = null;
        drawOffsets = null;
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        if (ebo != 0) glDeleteBuffers(ebo);
    }
}
