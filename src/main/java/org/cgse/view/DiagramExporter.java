// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.view;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

import javax.imageio.ImageIO;

/** Optional desktop PNG/SVG composition from portable geometry and host-provided images. */
public final class DiagramExporter {

    public record Node<K>(double x, double y, K icon, String name, String amount, String exact,
                          boolean missing, boolean seed, String initialInput, String reference) {

        private int border() {
            return seed ? 0x168F99 : initialInput.isEmpty() ? 0x777580 : 0xAD6518;
        }

        private String marker() {
            return seed ? "S" : initialInput.isEmpty() ? "" : "I";
        }
    }

    public record Line(double ax, double ay, double bx, double by) {}

    private DiagramExporter() {}

    /** Preserve two pixels per layout unit even for huge diagrams; only the working strip is bounded. */
    public static <K> void write(String title, List<Node<K>> nodes, List<Line> lines, Map<K, BufferedImage> icons,
                                 boolean amounts, Path png, Path svg) throws IOException {
        double left = nodes.stream().mapToDouble(Node::x).min().orElse(0) - 28;
        double top = nodes.stream().mapToDouble(Node::y).min().orElse(0) - 38;
        double width = nodes.stream().mapToDouble(Node::x).max().orElse(0) - left + 28;
        double height = nodes.stream().mapToDouble(Node::y).max().orElse(0) - top + 32;
        double scale = 2;
        int pixelWidth = pixelDimension(width * scale), pixelHeight = pixelDimension(height * scale);
        StripPngWriter.write(png, pixelWidth, pixelHeight, (graphics, stripTop, stripRows) -> {
            graphics.setColor(new Color(0xF2F1F5));
            graphics.fillRect(0, stripTop, pixelWidth, stripRows);
            graphics.scale(scale, scale);
            graphics.translate(-left, -top);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.setColor(new Color(0x505058));
            graphics.setFont(new Font(Font.DIALOG, Font.PLAIN, 9));
            graphics.drawString(title, (float) left + 8, (float) top + 14);
            graphics.setStroke(new BasicStroke(0.6f));
            double firstY = top + stripTop / scale - 1, lastY = firstY + stripRows / scale + 2;
            for (var line : lines) {
                if (Math.max(line.ay(), line.by()) < firstY || Math.min(line.ay(), line.by()) > lastY) continue;
                graphics.draw(new java.awt.geom.Line2D.Double(line.ax(), line.ay(), line.bx(), line.by()));
            }
            for (var node : nodes) {
                if (node.y() + 24 < firstY || node.y() - 24 > lastY) continue;
                graphics.setColor(new Color(node.missing() ? 0xE7C4C4 : 0xD5D4DE));
                graphics.fill(new java.awt.geom.Rectangle2D.Double(node.x() - 11, node.y() - 11, 22, 22));
                graphics.setColor(new Color(node.border()));
                graphics.draw(new java.awt.geom.Rectangle2D.Double(node.x() - 11, node.y() - 11, 22, 22));
                graphics.drawImage(icons.get(node.icon()), (int) node.x() - 8, (int) node.y() - 8, 16, 16, null);
                graphics.setFont(new Font(Font.DIALOG, Font.PLAIN, 5));
                if (amounts) graphics.drawString(node.amount(), (float) node.x() - graphics.getFontMetrics().stringWidth(node.amount()) / 2.0f, (float) node.y() + 17);
                if (!node.marker().isEmpty()) graphics.drawString(node.marker(), (float) node.x() - 11, (float) node.y() - 12);
                if (!node.reference().equals("NORMAL")) graphics.drawString("↗", (float) node.x() + 8, (float) node.y() - 10);
            }
        });
        try (Writer out = Files.newBufferedWriter(svg)) {
            out.write("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"" + width + "\" height=\"" + height + "\" viewBox=\"" + left + " " + top + " " + width + " " + height + "\">\n");
            out.write("<title>" + xml(title) + "</title><rect x=\"" + left + "\" y=\"" + top + "\" width=\"" + width + "\" height=\"" + height + "\" fill=\"#f2f1f5\"/><defs>\n");
            Map<K, Integer> ids = new HashMap<>();
            for (var entry : icons.entrySet()) {
                int id = ids.size();
                ids.put(entry.getKey(), id);
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(entry.getValue(), "png", bytes);
                out.write("<image id=\"i" + id + "\" width=\"16\" height=\"16\" xlink:href=\"data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray()) + "\"/>\n");
            }
            out.write("</defs><g stroke=\"#74747c\" stroke-width=\"0.6\" fill=\"none\">\n");
            for (var line : lines) out.write("<path d=\"M" + line.ax() + " " + line.ay() + "L" + line.bx() + " " + line.by() + "\"/>\n");
            out.write("</g><g font-family=\"sans-serif\" font-size=\"5\" text-anchor=\"middle\">\n");
            for (var node : nodes) {
                out.write("<g transform=\"translate(" + node.x() + " " + node.y() + ")\"><title>" + xml(node.name() + " · " + node.exact() +
                        (node.seed() ? " · Retained seed" : node.initialInput().isEmpty() ? "" : " · Initial input: " + node.initialInput())) + "</title>");
                out.write("<rect x=\"-11\" y=\"-11\" width=\"22\" height=\"22\" fill=\"" + (node.missing() ? "#e7c4c4" : "#d5d4de") + "\" stroke=\"" + String.format(Locale.ROOT, "#%06x", node.border()) + "\"/>");
                out.write("<use x=\"-8\" y=\"-8\" xlink:href=\"#i" + ids.get(node.icon()) + "\"/>");
                if (amounts) out.write("<text y=\"17\">" + xml(node.amount()) + "</text>");
                if (!node.marker().isEmpty()) out.write("<text x=\"-10\" y=\"-12\" fill=\"" + String.format(Locale.ROOT, "#%06x", node.border()) + "\">" + node.marker() + "</text>");
                if (!node.reference().equals("NORMAL")) out.write("<text x=\"10\" y=\"-12\">↗</text>");
                out.write("</g>\n");
            }
            out.write("</g></svg>\n");
        }
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static int pixelDimension(double size) throws IOException {
        if (!Double.isFinite(size) || size <= 0 || size > Integer.MAX_VALUE)
            throw new IOException("Invalid PNG dimension: " + size);
        return Math.max(1, (int) Math.ceil(size));
    }
}
