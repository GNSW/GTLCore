// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.view;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Renders bounded strips into one ordinary PNG, without allocating the entire image. */
public final class StripPngWriter {

    // At most 32 MiB for pixels, plus one RGB scanline and the compression buffers.
    private static final int MAX_STRIP_PIXELS = 8 * 1024 * 1024;

    @FunctionalInterface
    public interface Renderer {

        /** Graphics uses full-image pixel coordinates; only these rows are writable. */
        void render(Graphics2D graphics, int top, int rows);
    }

    private StripPngWriter() {}

    public static void write(Path file, int width, int height, Renderer renderer) throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_STRIP_PIXELS)
            throw new IOException("PNG dimensions exceed the scanline memory limit: " + width + " x " + height);
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), ".crafting-graph-", ".png.tmp");
        try {
            encode(temporary, width, height, renderer);
            // A failed export must not leave a truncated image at the advertised path.
            Files.move(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void encode(Path file, int width, int height, Renderer renderer) throws IOException {
        int stripRows = Math.min(height, Math.min(256, MAX_STRIP_PIXELS / width));
        var strip = new BufferedImage(width, stripRows, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) strip.getRaster().getDataBuffer()).getData();
        byte[] row = new byte[width * 3 + 1];
        row[0] = 1; // PNG Sub filter; each RGB byte is relative to the previous pixel.
        var deflater = new Deflater(Deflater.BEST_SPEED);
        try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 64 * 1024))) {
            output.writeLong(0x89504E470D0A1A0AL);
            byte[] header = ByteBuffer.allocate(13).putInt(width).putInt(height)
                    .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0).array();
            chunk(output, "IHDR", header, header.length);
            var chunks = new ImageChunks(output);
            var compressed = new DeflaterOutputStream(chunks, deflater, 64 * 1024);
            for (int top = 0; top < height;) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("PNG export interrupted");
                int rows = Math.min(stripRows, height - top);
                var graphics = strip.createGraphics();
                try {
                    graphics.translate(0, -top);
                    renderer.render(graphics, top, rows);
                } finally {
                    graphics.dispose();
                }
                for (int y = 0; y < rows; y++) {
                    int previous = 0, offset = y * width;
                    for (int x = 0, destination = 1; x < width; x++) {
                        int pixel = pixels[offset + x];
                        row[destination++] = (byte) ((pixel >>> 16) - (previous >>> 16));
                        row[destination++] = (byte) ((pixel >>> 8) - (previous >>> 8));
                        row[destination++] = (byte) (pixel - previous);
                        previous = pixel;
                    }
                    compressed.write(row);
                }
                top += rows;
            }
            // All strips share a single zlib stream; IDAT boundaries do not split the image.
            compressed.finish();
            chunks.flush();
            chunk(output, "IEND", new byte[0], 0);
        } finally {
            deflater.end();
            strip.flush();
        }
    }

    private static void chunk(DataOutputStream output, String name, byte[] bytes, int length) throws IOException {
        byte[] type = name.getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32();
        crc.update(type);
        crc.update(bytes, 0, length);
        output.writeInt(length);
        output.write(type);
        output.write(bytes, 0, length);
        output.writeInt((int) crc.getValue());
    }

    private static final class ImageChunks extends OutputStream {

        private final DataOutputStream output;
        private final byte[] buffer = new byte[64 * 1024];
        private int size;

        private ImageChunks(DataOutputStream output) {
            this.output = output;
        }

        @Override
        public void write(int value) throws IOException {
            buffer[size++] = (byte) value;
            if (size == buffer.length) flush();
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            while (length > 0) {
                int count = Math.min(length, buffer.length - size);
                System.arraycopy(bytes, offset, buffer, size, count);
                size += count;
                offset += count;
                length -= count;
                if (size == buffer.length) flush();
            }
        }

        @Override
        public void flush() throws IOException {
            if (size == 0) return;
            chunk(output, "IDAT", buffer, size);
            size = 0;
        }
    }
}
