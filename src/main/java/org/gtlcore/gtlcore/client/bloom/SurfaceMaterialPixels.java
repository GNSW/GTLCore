package org.gtlcore.gtlcore.client.bloom;

import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * Conservative load-time material synthesis. Albedo and authored materials are never modified.
 * Material identity comes from a hash-bound semantic profile, never from RGB or luminance.
 * RGB differences control a small, optional roughness variation within that known material only.
 * Geometric relief requires a separately reviewed height mask; no color-to-height inference exists.
 */
public final class SurfaceMaterialPixels {

    public record Result(BufferedImage specular, BufferedImage normal) {}

    private SurfaceMaterialPixels() {}

    public static Result generate(BufferedImage base, String category, int frameWidth, int frameHeight,
                                  BufferedImage heightMask, double strength, double normalStrength) {
        Objects.requireNonNull(base, "base");
        bounded(strength);
        bounded(normalStrength);
        int baseline, reflectance;
        switch (category) {
            case "metal" -> {
                baseline = 120;
                reflectance = 255;
            }
            case "paint" -> {
                baseline = 96;
                reflectance = 10;
            }
            case "glass" -> {
                baseline = 190;
                reflectance = 10;
            }
            default -> throw new IllegalArgumentException("Unknown material category");
        }
        int w = base.getWidth(), h = base.getHeight();
        if (frameWidth < 1 || frameHeight < 1 || w % frameWidth != 0 || h % frameHeight != 0)
            throw new IllegalArgumentException("Invalid frame layout");
        if (heightMask != null && (heightMask.getWidth() != w || heightMask.getHeight() != h || w != frameWidth || h != frameHeight))
            throw new IllegalArgumentException("Height masks require one exact static frame");
        BufferedImage specular = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        BufferedImage normal = heightMask == null ? null : new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        // Animated surfaces use ONE spatial roughness field across all canvas frames. Taking the
        // mean of contrast at each local texel avoids roughness pulsing with animated color/highlights.
        double[] edges = new double[frameWidth * frameHeight];
        int[] samples = new int[edges.length];
        if (!category.equals("glass") && strength > 0) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                if ((base.getRGB(x, y) >>> 24) == 0) continue;
                int i = (y % frameHeight) * frameWidth + x % frameWidth;
                edges[i] += edge(base, x, y, frameWidth, frameHeight);
                samples[i]++;
            }
        }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int coverage = base.getRGB(x, y) >>> 24;
            int i = (y % frameHeight) * frameWidth + x % frameWidth;
            double contrast = samples[i] == 0 ? 0 : edges[i] / samples[i];
            // Deliberately small authored interpretation: panel interiors slightly smoother,
            // color-region boundaries slightly rougher. No stochastic noise or metal inference.
            double offset = category.equals("glass") ? 0 : 8.0 - 24.0 * Math.min(1.0, contrast / 0.25);
            int smooth = (int) Math.round(baseline + strength * offset);
            specular.setRGB(x, y, coverage == 0 ? 0xff000000 : 0xff000000 | (smooth << 16) | (reflectance << 8));
            if (normal != null) {
                // B=255 means no AO; A=255 means no POM depth. Cutout perimeter and outer
                // sprite border stay flat; sampled holes never create a raised silhouette.
                double nx = 0, ny = 0;
                if (coverage == 255 && x > 0 && y > 0 && x < w - 1 && y < h - 1 && opaque(base, x - 1, y) && opaque(base, x + 1, y) && opaque(base, x, y - 1) && opaque(base, x, y + 1)) {
                    double dx = (heightMask.getRGB(x + 1, y) & 255) - (heightMask.getRGB(x - 1, y) & 255);
                    double dy = (heightMask.getRGB(x, y + 1) & 255) - (heightMask.getRGB(x, y - 1) & 255);
                    double sx = Math.max(-0.12, Math.min(0.12, -dx / 128.0)) * normalStrength;
                    // DirectX Y- normal convention, image coordinates increase downward.
                    double sy = Math.max(-0.12, Math.min(0.12, -dy / 128.0)) * normalStrength;
                    double inv = 1.0 / Math.sqrt(1 + sx * sx + sy * sy);
                    nx = sx * inv;
                    ny = sy * inv;
                }
                int r = (int) Math.round((nx * .5 + .5) * 255), g = (int) Math.round((ny * .5 + .5) * 255);
                normal.setRGB(x, y, 0xff0000ff | (r << 16) | (g << 8));
            }
        }
        return new Result(specular, normal);
    }

    private static boolean opaque(BufferedImage image, int x, int y) {
        return (image.getRGB(x, y) >>> 24) == 255;
    }

    private static void bounded(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("Strength outside [0,1]");
    }

    private static double edge(BufferedImage image, int x, int y, int fw, int fh) {
        int minX = x / fw * fw, minY = y / fh * fh, maxX = minX + fw - 1, maxY = minY + fh - 1;
        int center = image.getRGB(x, y);
        double total = 0;
        int n = 0;
        int[] xx = { Math.max(minX, x - 1), Math.min(maxX, x + 1), x, x };
        int[] yy = { y, y, Math.max(minY, y - 1), Math.min(maxY, y + 1) };
        for (int k = 0; k < 4; k++) {
            int p = image.getRGB(xx[k], yy[k]);
            if ((p >>> 24) == 0) continue;
            // Relative color difference, not absolute brightness. All three channels are used.
            total += (Math.abs(((p >> 16) & 255) - ((center >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((center >> 8) & 255)) + Math.abs((p & 255) - (center & 255))) / 765.0;
            n++;
        }
        return n == 0 ? 0 : total / n;
    }
}
