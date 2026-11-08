package org.gtlcore.gtlcore.integration.jei.tree;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Tiny bitmap amount renderer used by the recipe tree and the inventory craft overlay.
 */
final class CraftTextRenderer {

    private CraftTextRenderer() {}

    static int stateColor(int state) {
        return switch (state) {
            case 1 -> 0xFF790091;
            case 2 -> 0xFF00918E;
            case -1 -> 0xFF911300;
            default -> 0xFF915900;
        };
    }

    static void drawMicroAmount(GuiGraphics graphics, String text, int right, int bottom, int color) {
        int width = text.length() * 4 - 1;
        int x = right - width;
        int y = bottom - 5;
        for (int index = 0; index < text.length(); index++) {
            int[] glyph = glyph(text.charAt(index));
            int glyphX = x + index * 4;
            for (int row = 0; row < 5; row++) {
                for (int column = 0; column < 3; column++) {
                    if ((glyph[row] & (1 << (2 - column))) != 0) {
                        graphics.fill(glyphX + column, y + row, glyphX + column + 1,
                                y + row + 1, 0xA0000000);
                    }
                }
            }
            for (int row = 0; row < 5; row++) {
                for (int column = 0; column < 3; column++) {
                    if ((glyph[row] & (1 << (2 - column))) != 0) {
                        graphics.fill(glyphX + column, y + row - 1, glyphX + column + 1,
                                y + row, color);
                    }
                }
            }
        }
    }

    static String amountText(long amount) {
        amount = Math.max(0, amount);
        if (amount < 1000) {
            return Long.toString(amount);
        }
        String[] units = { "k", "M", "B", "T", "Q" };
        long divisor = 1000;
        int unit = 0;
        while (unit < units.length - 1 && amount >= divisor * 1000) {
            divisor *= 1000;
            unit++;
        }
        String text = String.format(java.util.Locale.ROOT, "%.2f", amount / (double) divisor);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text + units[unit];
    }

    private static int[] glyph(char character) {
        return switch (character) {
            case '0' -> new int[] { 0b111, 0b101, 0b101, 0b101, 0b111 };
            case '1' -> new int[] { 0b010, 0b110, 0b010, 0b010, 0b111 };
            case '2' -> new int[] { 0b111, 0b001, 0b111, 0b100, 0b111 };
            case '3' -> new int[] { 0b111, 0b001, 0b111, 0b001, 0b111 };
            case '4' -> new int[] { 0b101, 0b101, 0b111, 0b001, 0b001 };
            case '5' -> new int[] { 0b111, 0b100, 0b111, 0b001, 0b111 };
            case '6' -> new int[] { 0b111, 0b100, 0b111, 0b101, 0b111 };
            case '7' -> new int[] { 0b111, 0b001, 0b010, 0b010, 0b010 };
            case '8' -> new int[] { 0b111, 0b101, 0b111, 0b101, 0b111 };
            case '9' -> new int[] { 0b111, 0b101, 0b111, 0b001, 0b111 };
            case '.' -> new int[] { 0b000, 0b000, 0b000, 0b000, 0b010 };
            case 'k' -> new int[] { 0b100, 0b101, 0b110, 0b101, 0b101 };
            case 'M' -> new int[] { 0b101, 0b111, 0b111, 0b101, 0b101 };
            case 'B' -> new int[] { 0b110, 0b101, 0b110, 0b101, 0b110 };
            case 'T' -> new int[] { 0b111, 0b010, 0b010, 0b010, 0b010 };
            case 'Q' -> new int[] { 0b111, 0b101, 0b101, 0b111, 0b011 };
            case '+' -> new int[] { 0b000, 0b010, 0b111, 0b010, 0b000 };
            default -> new int[] { 0b000, 0b000, 0b000, 0b000, 0b000 };
        };
    }
}
