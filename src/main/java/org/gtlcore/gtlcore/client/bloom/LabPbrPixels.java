package org.gtlcore.gtlcore.client.bloom;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Objects;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** CPU-only LabPBR specular-map emission generation; never edits the albedo image. */
public final class LabPbrPixels {

    private static final byte[] PNG_SIGNATURE = { (byte) 137, 80, 78, 71, 13, 10, 26, 10 };

    private LabPbrPixels() {}

    /**
     * Generates a separate {@code _s} image at the complete source canvas size, including
     * all animation frames. Source alpha is coverage: {@code round(alpha * 254 / 255)}.
     * Source RGB is not used, and both inputs remain unchanged.
     *
     * <p>
     * A new map has neutral RGB channels (zero). A caller-verified LabPBR map retains
     * its RGB channels and the greater of its existing and generated emission. LabPBR
     * alpha 255 means no emission, so it is treated as zero before that comparison.
     * Format verification belongs to the caller; this class never guesses from pixels.
     * Animation metadata must likewise be preserved by the caller.
     *
     * @param base             the albedo image whose alpha supplies emission coverage
     * @param existingSpecular an existing specular map, or {@code null} for a new map
     * @param existingIsLabPbr whether the caller explicitly verified the existing format
     * @throws IllegalArgumentException if an existing map is unverified or has a different size
     */
    public static BufferedImage generate(BufferedImage base, BufferedImage existingSpecular,
                                         boolean existingIsLabPbr) {
        return generate(base, existingSpecular, existingIsLabPbr, 1.0);
    }

    /**
     * Same lossless material merge, with a bounded multiplier for newly generated emission only.
     * Existing authored emission and RGB are never dimmed or changed by this multiplier.
     */
    public static BufferedImage generate(BufferedImage base, BufferedImage existingSpecular,
                                         boolean existingIsLabPbr, double strength) {
        Objects.requireNonNull(base, "base");
        if (!Double.isFinite(strength) || strength < 0.0 || strength > 1.0)
            throw new IllegalArgumentException("Generated emission strength must be finite and in [0, 1]");
        if (existingSpecular != null) {
            if (!existingIsLabPbr)
                throw new IllegalArgumentException("Existing specular map is not verified LabPBR");
            if (base.getWidth() != existingSpecular.getWidth() || base.getHeight() != existingSpecular.getHeight())
                throw new IllegalArgumentException("Existing specular map dimensions differ from base canvas");
        }

        BufferedImage result = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < base.getHeight(); y++) {
            for (int x = 0; x < base.getWidth(); x++) {
                int coverage = base.getRGB(x, y) >>> 24;
                int emission = (int) Math.round(coverage * 254.0 * strength / 255.0);
                int rgb = 0;
                if (existingSpecular != null) {
                    int pixel = existingSpecular.getRGB(x, y);
                    rgb = pixel & 0x00FFFFFF;
                    int previousEmission = pixel >>> 24;
                    if (previousEmission != 255) emission = Math.max(emission, previousEmission);
                }
                result.setRGB(x, y, (emission << 24) | rgb);
            }
        }
        return result;
    }

    /** Match the albedo canvas, sampling existing RGB material channels without color conversion. */
    public static BufferedImage alignMaterial(BufferedImage base, BufferedImage material) {
        if (base.getWidth() == material.getWidth() && base.getHeight() == material.getHeight()) return material;
        BufferedImage aligned = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < base.getHeight(); y++) for (int x = 0; x < base.getWidth(); x++)
            aligned.setRGB(x, y, material.getRGB(x * material.getWidth() / base.getWidth(), y * material.getHeight() / base.getHeight()));
        return aligned;
    }

    /** Decodes PNG bytes without relying on files, a graphics device, or a GL context. */
    public static BufferedImage decodePng(byte[] png) throws IOException {
        Objects.requireNonNull(png, "png");
        if (png.length < PNG_SIGNATURE.length) throw new IOException("Missing PNG signature");
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (png[i] != PNG_SIGNATURE[i]) throw new IOException("Input is not a PNG image");
        }
        // Explicit memory streams avoid ImageIO's process-wide optional disk cache.
        try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("PNG decoder is unavailable");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("PNG decoder did not return an image");
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    /** Encodes an image as PNG without modifying it. */
    public static byte[] encodePng(BufferedImage image) throws IOException {
        Objects.requireNonNull(image, "image");
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                MemoryCacheImageOutputStream png = new MemoryCacheImageOutputStream(output)) {
            if (!ImageIO.write(image, "png", png)) throw new IOException("PNG encoder is unavailable");
            png.flush();
            return output.toByteArray();
        }
    }
}
