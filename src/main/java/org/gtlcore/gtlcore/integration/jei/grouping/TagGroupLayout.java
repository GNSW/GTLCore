package org.gtlcore.gtlcore.integration.jei.grouping;

/** Screen-clamped, adjacent popup geometry, independent of Minecraft rendering. */
public record TagGroupLayout(int x, int y, int columns, int rows, int width, int height, int headerHeight) {

    public static final int SLOT = 18;
    public static final int PADDING = 4;
    public static final int HEADER = 11;
    public static final int FOOTER = 12;

    public static TagGroupLayout create(int screenWidth, int screenHeight, int anchorX, int anchorY, int count) {
        return create(screenWidth, screenHeight, anchorX, anchorY, count, 0, HEADER);
    }

    public static TagGroupLayout create(int screenWidth, int screenHeight, int anchorX, int anchorY, int count,
                                        int minimumWidth, int requestedHeaderHeight) {
        int headerHeight = Math.max(HEADER, Math.min(requestedHeaderHeight, screenHeight - 2 * PADDING - FOOTER - SLOT));
        int columns = Math.min(Math.min(8, Math.max(1, count)), Math.max(1, (screenWidth - 2 * PADDING) / SLOT));
        int rows = Math.min(Math.min(6, (count + columns - 1) / columns),
                Math.max(1, (screenHeight - 2 * PADDING - headerHeight - FOOTER) / SLOT));
        int width = Math.max(columns * SLOT + 2 * PADDING, Math.min(screenWidth, minimumWidth));
        int height = rows * SLOT + 2 * PADDING + headerHeight + FOOTER;
        int x = anchorX >= width ? anchorX - width : anchorX + SLOT;
        x = Math.max(0, Math.min(screenWidth - width, x));
        int y = Math.max(0, Math.min(screenHeight - height, anchorY - headerHeight - PADDING));
        return new TagGroupLayout(x, y, columns, rows, width, height, headerHeight);
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    public int maxFirstRow(int count) {
        return Math.max(0, (count + columns - 1) / columns - rows);
    }
}
