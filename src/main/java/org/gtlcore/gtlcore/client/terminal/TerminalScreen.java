package org.gtlcore.gtlcore.client.terminal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A self-contained, resolution-adaptive terminal UI. All controls are drawn
 * with GuiGraphics and hit-tested explicitly so the presentation remains
 * consistent across GUI scales and unusual resolutions.
 */
@OnlyIn(Dist.CLIENT)
public final class TerminalScreen extends Screen {

    private static final ResourceLocation WATERMARK = Objects.requireNonNull(ResourceLocation.tryParse(
            "gtlcore:textures/gui/terminal/gt_leisure_watermark.png"));
    private static final int BG = 0xFF020711;
    private static final int PANEL = 0xE8081424;
    private static final int PANEL_DEEP = 0xF0040B14;
    private static final int CYAN = 0xFF54E8F4;
    private static final int CYAN_BRIGHT = 0xFFC3FBFF;
    private static final int CYAN_DIM = 0x9954E8F4;
    private static final int BLUE = 0xFF62B9FF;
    private static final int MAGENTA = 0xFFFF5FBE;
    private static final int WHITE = 0xFFEAF9FB;
    private static final int MUTED = 0xFF82A5AF;
    private static final int RED = 0xFFFF6478;

    private static final long BOOT_ADVANCE_MS = 4300L;
    private static final long COMPLETE_LINE_MS = 700L;
    private static final long COMPLETE_STOP_MS = 6200L;

    private static final RouteInfo[] ROUTES = new RouteInfo[] {
            new RouteInfo(
                    "gtlcore.terminal.route.skyblock.title",
                    "gtlcore.terminal.route.skyblock.subtitle",
                    "gtlcore.terminal.route.skyblock.description",
                    true,
                    "gtlcore.terminal.route.skyblock.tag"),
            new RouteInfo(
                    "gtlcore.terminal.route.continental.title",
                    "gtlcore.terminal.route.continental.subtitle",
                    "gtlcore.terminal.route.continental.description",
                    false,
                    "gtlcore.terminal.route.continental.tag")
    };

    private final boolean mandatory;
    private final Screen returnScreen;
    private final List<Hit> hits = new ArrayList<>();
    private final long openedAt = System.currentTimeMillis();

    private Stage stage;
    private int routeIndex;
    private boolean detailOpen;
    private long detailOpenedAt;
    private long completionStartedAt;
    private boolean stopScheduled;
    private String errorMessage = "";

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentLeft;
    private int contentRight;
    private int contentTop;
    private int contentBottom;
    private boolean compact;
    private double mouseX;
    private double mouseY;

    public TerminalScreen(boolean mandatory, Screen returnScreen) {
        super(Component.translatable("gtlcore.terminal.title"));
        this.mandatory = mandatory;
        this.returnScreen = returnScreen;
        this.routeIndex = TerminalConfigWriter.isSkyblockMode() ? 0 : 1;
        this.stage = Stage.BOOT;
    }

    @Override
    protected void init() {
        setFocused(null);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        long now = System.currentTimeMillis();
        updateStage(now);
        layout();
        hits.clear();

        drawBackdrop(graphics, now);
        drawWatermark(graphics);
        drawFrame(graphics, now);

        switch (stage) {
            case BOOT -> renderBoot(graphics, now);
            case ROUTE -> {
                if (detailOpen) {
                    renderRouteDetail(graphics, now);
                } else {
                    renderRouteChooser(graphics, now);
                }
            }
            case CONFIG -> renderConfig(graphics, now);
            case COMPLETE -> renderCompletion(graphics, now);
        }

        drawFooter(graphics);
    }

    private void updateStage(long now) {
        if (stage == Stage.BOOT && now - openedAt >= BOOT_ADVANCE_MS) {
            stage = Stage.ROUTE;
            routeIndex = 0;
            detailOpen = false;
        }
        if (stage == Stage.COMPLETE && !stopScheduled && now - completionStartedAt >= COMPLETE_STOP_MS) {
            stopScheduled = true;
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(minecraft::stop);
        }
    }

    private void layout() {
        int margin = Math.max(8, Math.min(30, Math.min(width, height) / 22));
        panelWidth = Math.min(900, Math.max(220, width - margin * 2));
        panelHeight = Math.min(540, Math.max(180, height - margin * 2));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        compact = panelWidth < 520 || panelHeight < 330;

        int horizontal = Math.max(12, Math.min(38, panelWidth / 22));
        int vertical = Math.max(10, Math.min(28, panelHeight / 18));
        contentLeft = panelX + horizontal;
        contentRight = panelX + panelWidth - horizontal;
        contentTop = panelY + vertical + (compact ? 20 : 28);
        contentBottom = panelY + panelHeight - (compact ? 22 : 30);
    }

    private void drawBackdrop(GuiGraphics graphics, long now) {
        graphics.fill(0, 0, width, height, BG);
        graphics.fillGradient(0, 0, width, Math.max(1, height / 2), 0xC0061730, 0x30020A16);
        graphics.fillGradient(0, height / 2, width, height, 0x00020914, 0xFF01040B);

        int grid = compact ? 34 : 44;
        int driftX = (int) ((now / 150L) % grid);
        int driftY = (int) ((now / 210L) % grid);
        for (int x = -grid + driftX; x < width; x += grid) {
            graphics.fill(x, 0, x + 1, height, 0x142B91A8);
        }
        for (int y = -grid + driftY; y < height; y += grid) {
            graphics.fill(0, y, width, y + 1, 0x122B91A8);
        }

        graphics.fillGradient(0, 0, Math.max(1, width / 5), height, 0xA000050B, 0x0000050B);
        graphics.fillGradient(width - Math.max(1, width / 5), 0, width, height, 0x0000050B, 0xA000050B);

        for (int i = 0; i < 72; i++) {
            int sx = Math.floorMod(i * 193 + 47, Math.max(1, width));
            int sy = Math.floorMod(i * 109 + 23, Math.max(1, height));
            boolean bright = (i + now / 900L) % 7 == 0;
            int color = bright ? 0x9954E8F4 : 0x454FB9CC;
            int size = bright ? 2 : 1;
            graphics.fill(sx, sy, sx + size, sy + size, color);
        }

        graphics.fill(0, height / 2, width, height / 2 + 1, 0x1623C6E5);
        for (int y = 0; y < height; y += 4) {
            if ((y / 4) % 3 == 0) {
                graphics.fill(0, y, width, y + 1, 0x0809A5BB);
            }
        }
    }

    /**
     * Large, faint GT Leisure emblem behind the terminal content. It is drawn
     * after the procedural star/grid backdrop but before the panel and text,
     * so it reads as a watermark without obscuring any controls.
     */
    private void drawWatermark(GuiGraphics graphics) {
        int size = (int) Math.min(width * 0.56, height * 0.78);
        size = Math.max(72, size);
        int x = width - size - (compact ? 10 : 24);
        int y = (height - size) / 2 + (compact ? 4 : 10);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 0.22F);
        graphics.blit(WATERMARK, x, y, size, size, 0.0F, 0.0F, 400, 400, 400, 400);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void drawFrame(GuiGraphics graphics, long now) {
        int pulse = 150 + (int) (45 * (Math.sin(now / 650.0) * 0.5 + 0.5));
        graphics.fill(panelX - 2, panelY - 2, panelX + panelWidth + 2, panelY + panelHeight + 2, 0x3000C9E8);
        graphics.fillGradient(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL, PANEL_DEEP);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, CYAN);
        graphics.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight, 0x6642C9E0);
        graphics.fill(panelX, panelY, panelX + 1, panelY + panelHeight, 0x8842C9E0);
        graphics.fill(panelX + panelWidth - 1, panelY, panelX + panelWidth, panelY + panelHeight, 0x8842C9E0);

        int corner = compact ? 12 : 20;
        graphics.fill(panelX, panelY, panelX + corner, panelY + 2, CYAN_BRIGHT);
        graphics.fill(panelX, panelY, panelX + 2, panelY + corner, CYAN_BRIGHT);
        graphics.fill(panelX + panelWidth - corner, panelY, panelX + panelWidth, panelY + 2, MAGENTA);
        graphics.fill(panelX + panelWidth - 2, panelY, panelX + panelWidth, panelY + corner, MAGENTA);
        graphics.fill(panelX, panelY + panelHeight - 2, panelX + corner, panelY + panelHeight, 0xAA54E8F4);
        graphics.fill(panelX + panelWidth - corner, panelY + panelHeight - 2, panelX + panelWidth, panelY + panelHeight, 0xAAFF5FBE);

        int headerY = panelY + 8;
        graphics.drawString(font, tr("gtlcore.terminal.header.company"), contentLeft, headerY, CYAN_BRIGHT, false);
        String right = compact ? "GALACTIC // 07" : "GALACTIC GREG INDUSTRIES  //  TERMINAL 07";
        graphics.drawString(font, right, contentRight - font.width(right), headerY, MUTED, false);
        graphics.fill(contentLeft, headerY + 12, contentRight, headerY + 13, 0x552EA6C1);
        graphics.fill(contentLeft, headerY + 12, contentLeft + Math.max(30, panelWidth / 5), headerY + 13, CYAN);

        int stepX = contentLeft;
        int stepY = panelY + panelHeight - 13;
        Stage[] stages = Stage.values();
        for (int i = 0; i < stages.length; i++) {
            boolean active = stages[i].ordinal() <= stage.ordinal();
            int color = active ? (i == stage.ordinal() ? CYAN : 0xAA4FA9B8) : 0x44324F59;
            graphics.fill(stepX, stepY, stepX + 18, stepY + 2, color);
            stepX += 22;
        }
        String security = tr(mandatory ? "gtlcore.terminal.footer.mandatory" : "gtlcore.terminal.footer.maintenance");
        graphics.drawString(font, security, contentRight - font.width(security), stepY - 4, compact ? 0x665E8994 : 0x995E8994, false);
    }

    private void drawFooter(GuiGraphics graphics) {
        if (stage == Stage.BOOT || stage == Stage.COMPLETE) {
            return;
        }
        String hint;
        if (stage == Stage.CONFIG) {
            hint = tr("gtlcore.terminal.footer.config");
        } else if (detailOpen) {
            hint = tr("gtlcore.terminal.footer.detail");
        } else {
            hint = tr("gtlcore.terminal.footer.routes");
        }
        int color = compact ? 0x887FAAB5 : 0xAA7FAAB5;
        graphics.drawString(font, hint, contentLeft, contentBottom + 5, color, false);
    }

    private void drawWrapped(GuiGraphics graphics, String text, int x, int y, int maxWidth, int lineHeight, int color) {
        int cursorY = y;
        StringBuilder line = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            if (text.charAt(offset) == '\n') {
                graphics.drawString(font, line.toString(), x, cursorY, color, false);
                line.setLength(0);
                cursorY += lineHeight;
                offset += Character.charCount(codePoint);
                continue;
            }
            if (line.length() > 0 && font.width(line + character) > maxWidth) {
                graphics.drawString(font, line.toString(), x, cursorY, color, false);
                line.setLength(0);
                cursorY += lineHeight;
            }
            line.append(character);
            offset += Character.charCount(codePoint);
        }
        if (line.length() > 0) {
            graphics.drawString(font, line.toString(), x, cursorY, color, false);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private static double easeOut(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return 1.0 - Math.pow(1.0 - clamped, 3.0);
    }

    private void renderBoot(GuiGraphics graphics, long now) {
        long elapsed = now - openedAt;
        int centerY = panelY + panelHeight / 2;
        int lineHeight = compact ? 18 : 26;
        int startY = centerY - lineHeight * 2;

        String title = tr("gtlcore.terminal.boot.title");
        String player = tr("gtlcore.terminal.boot.profile") + Minecraft.getInstance().getUser().getName();
        String waiting = tr("gtlcore.terminal.boot.waiting");

        int titleWidth = font.width(title);
        int titleX = (width - titleWidth) / 2;
        int flicker = 220 + (int) (35 * Math.sin(now / 110.0));
        graphics.drawString(font, title, titleX, startY, withAlpha(CYAN_BRIGHT, flicker), false);
        if (elapsed > 180) {
            int progressWidth = Math.max(1, (int) (Math.min(1.0, elapsed / 2800.0) * Math.min(420, panelWidth - 80)));
            int progressX = (width - Math.min(420, panelWidth - 80)) / 2;
            graphics.fill(progressX, startY + 16, progressX + progressWidth, startY + 18, CYAN);
            graphics.fill(progressX + progressWidth, startY + 16, progressX + Math.min(420, panelWidth - 80), startY + 18, 0x55316B77);
        }
        if (elapsed > 1150) {
            double reveal = easeOut((elapsed - 1150.0) / 500.0);
            int offset = (int) ((1.0 - reveal) * 8.0);
            int profileWidth = font.width(player);
            graphics.drawString(font, player, (width - profileWidth) / 2, startY + lineHeight + offset, withAlpha(WHITE, (int) (255 * reveal)), false);
        }
        if (elapsed > 2350) {
            double reveal = easeOut((elapsed - 2350.0) / 520.0);
            int offset = (int) ((1.0 - reveal) * 8.0);
            int waitingWidth = font.width(waiting);
            graphics.drawString(font, waiting, (width - waitingWidth) / 2, startY + lineHeight * 2 + offset, withAlpha(CYAN, (int) (255 * reveal)), false);
        }

        String bootCode = String.format(Locale.ROOT, "BOOT %02d%%  //  SECURE CHANNEL ESTABLISHED", Math.min(100, (int) (elapsed / 43L)));
        int codeWidth = font.width(bootCode);
        graphics.drawString(font, bootCode, (width - codeWidth) / 2, contentBottom - 6, 0xAA527D86, false);
    }

    private void renderRouteChooser(GuiGraphics graphics, long now) {
        int headingY = contentTop;
        int availableHeight = contentBottom - headingY;
        boolean stacked = panelWidth < 560 || availableHeight < 245;

        String eyebrow = tr("gtlcore.terminal.routes.eyebrow");
        graphics.drawString(font, eyebrow, contentLeft, headingY, CYAN_DIM, false);
        int titleY = headingY + (compact ? 12 : 17);
        drawScaledText(graphics, tr("gtlcore.terminal.routes.title"), contentLeft, titleY, compact ? 1.25f : 1.55f, WHITE);
        String subtitle = stacked ? tr("gtlcore.terminal.routes.subtitle_compact") :
                tr("gtlcore.terminal.routes.subtitle");
        int subtitleY = titleY + (compact ? 17 : 24);
        if (subtitleY < contentBottom) {
            graphics.drawString(font, subtitle, contentLeft, subtitleY, MUTED, false);
        }

        int cardsTop = subtitleY + (compact ? 13 : 17);
        int cardsBottom = contentBottom - (compact ? 6 : 10);
        int gap = compact ? 7 : 14;
        int count = ROUTES.length;

        if (stacked) {
            int cardHeight = Math.max(30, (cardsBottom - cardsTop - gap * (count - 1)) / count);
            for (int i = 0; i < count; i++) {
                int y = cardsTop + i * (cardHeight + gap);
                drawRouteCard(graphics, i, contentLeft, y, contentRight - contentLeft, cardHeight, now, true);
            }
        } else {
            int cardWidth = (contentRight - contentLeft - gap * (count - 1)) / count;
            int cardHeight = Math.max(72, Math.min(174, cardsBottom - cardsTop));
            for (int i = 0; i < count; i++) {
                int x = contentLeft + i * (cardWidth + gap);
                drawRouteCard(graphics, i, x, cardsTop, cardWidth, cardHeight, now, false);
            }
        }
    }

    private void drawRouteCard(GuiGraphics graphics, int index, int x, int y, int cardWidth, int cardHeight, long now, boolean stacked) {
        boolean hovered = inside(mouseX, mouseY, x, y, cardWidth, cardHeight);
        RouteInfo route = ROUTES[index];
        int accent = index == 0 ? MAGENTA : CYAN;
        int fillTop = hovered ? 0xEE12314A : 0xE30B1B2D;
        int fillBottom = hovered ? 0xEE071522 : 0xE305101C;

        if (hovered) {
            graphics.fill(x - 1, y - 1, x + cardWidth + 1, y + cardHeight + 1, withAlpha(accent, 90));
        }
        graphics.fillGradient(x, y, x + cardWidth, y + cardHeight, fillTop, fillBottom);
        graphics.fill(x, y, x + cardWidth, y + 2, accent);
        graphics.fill(x, y, x + 2, y + cardHeight, withAlpha(accent, 150));
        graphics.fill(x + cardWidth - 2, y, x + cardWidth, y + cardHeight, 0x55365D68);
        graphics.fill(x, y + cardHeight - 2, x + cardWidth, y + cardHeight, 0x66365D68);

        int pad = stacked ? 9 : 14;
        String indexLabel = String.format(Locale.ROOT, "0%d", index + 1);
        graphics.drawString(font, indexLabel, x + pad, y + pad, accent, false);

        if (stacked) {
            float scale = cardHeight < 48 ? 1.02f : 1.18f;
            String shownTitle = truncateScaled(tr(route.title()), cardWidth - pad * 2 - 24, scale);
            drawScaledText(graphics, shownTitle, x + pad + 24, y + pad - 2, scale, WHITE);
        } else {
            int markerY = y + (index == 0 ? 32 : 32);
            graphics.fill(x + pad, markerY + 5, x + pad + 5, markerY + 10, accent);
            graphics.fill(x + pad + 2, markerY + 3, x + pad + 3, markerY + 12, accent);
            graphics.fill(x + pad + 3, markerY + 8, x + pad + 12, markerY + 9, accent);

            int titleY = y + 52;
            String shownTitle = truncateScaled(tr(route.title()), cardWidth - pad * 2, 1.58f);
            drawScaledText(graphics, shownTitle, x + pad, titleY, 1.58f, WHITE);

            int dividerY = titleY + 43;
            graphics.fill(x + pad, dividerY, x + cardWidth - pad, dividerY, 0x553A6A77);
            String tag = tr(route.highlightTag());
            graphics.drawString(font, "[" + tag + "]", x + pad, dividerY + 12, BLUE, false);
            String action = tr("gtlcore.terminal.routes.open_dossier");
            graphics.drawString(font, action, x + pad, y + cardHeight - 22, hovered ? CYAN_BRIGHT : CYAN, false);

            int scanY = y + 20 + (int) ((now / 20L + index * 37) % Math.max(1, cardHeight - 38));
            graphics.fill(x + 2, scanY, x + cardWidth - 2, scanY + 1, withAlpha(accent, hovered ? 45 : 22));
        }

        addHit(x, y, cardWidth, cardHeight, () -> openDetail(index));
    }

    private void openDetail(int index) {
        routeIndex = Math.max(0, Math.min(ROUTES.length - 1, index));
        detailOpen = true;
        detailOpenedAt = System.currentTimeMillis();
        stage = Stage.ROUTE;
    }

    private void renderRouteDetail(GuiGraphics graphics, long now) {
        RouteInfo route = ROUTES[routeIndex];
        double reveal = easeOut((now - detailOpenedAt) / 430.0);

        int arrowSize = compact ? 20 : 24;
        int navY = contentTop;
        int navLeftX = contentLeft;
        int navRightX = contentRight - arrowSize;

        String eyebrow = tr("gtlcore.terminal.dossier.eyebrow") + "  //  DOSSIER  0" + (routeIndex + 1) + "/02";
        int eyebrowLeft = navLeftX + arrowSize + (compact ? 8 : 12);
        int eyebrowRight = navRightX - (compact ? 8 : 12);
        String shownEyebrow = truncate(eyebrow, Math.max(1, eyebrowRight - eyebrowLeft));
        int eyebrowY = navY + Math.max(0, (arrowSize - font.lineHeight) / 2);
        graphics.drawString(font, shownEyebrow, eyebrowLeft, eyebrowY, CYAN_DIM, false);

        // Drawn after the label so the arrows always stay readable.
        drawArrow(graphics, navLeftX, navY, arrowSize, false, 0xAA54E8F4);
        drawArrow(graphics, navRightX, navY, arrowSize, true, 0xAA54E8F4);
        addHit(navLeftX, navY, arrowSize, arrowSize, () -> cycleRoute(-1));
        addHit(navRightX, navY, arrowSize, arrowSize, () -> cycleRoute(1));

        int titleBaseY = navY + arrowSize + (compact ? 8 : 12);
        int titleOffset = (int) ((1.0 - reveal) * 18.0);
        int titleY = titleBaseY + titleOffset;
        float titleScale = compact ? 1.2f : 1.55f;
        String shownTitle = truncateScaled(tr(route.title()), contentRight - contentLeft, titleScale);
        drawScaledText(graphics, shownTitle, contentLeft, titleY, titleScale, WHITE);
        int titleLineY = titleBaseY + (compact ? 17 : 22);
        graphics.fill(contentLeft, titleLineY, contentRight, titleLineY + 1, 0x662CB5CB);

        int bodyTop = titleLineY + (compact ? 9 : 14);
        int controlsHeight = compact ? 30 : 38;
        int bodyBottom = contentBottom - controlsHeight;
        int bodyLeft = contentLeft;
        int bodyWidth = contentRight - contentLeft;
        graphics.fillGradient(bodyLeft, bodyTop, bodyLeft + bodyWidth, bodyBottom, 0x8C071725, 0x76040D17);
        graphics.fill(bodyLeft, bodyTop, bodyLeft + 2, bodyBottom, withAlpha(routeIndex == 0 ? MAGENTA : CYAN, 170));
        graphics.fill(bodyLeft, bodyTop, bodyLeft + bodyWidth, bodyTop + 1, 0x5539A9BF);

        int textPad = compact ? 10 : 18;
        int lineHeight = compact ? 11 : 15;
        int textTop = bodyTop + (compact ? 8 : 14);
        graphics.enableScissor(bodyLeft + 2, bodyTop + 1, bodyLeft + bodyWidth - 2, Math.max(bodyTop + 2, bodyBottom - 1));
        drawRichWrapped(graphics, tr(route.description()), bodyLeft + textPad, textTop, bodyWidth - textPad * 2, lineHeight, WHITE);
        graphics.disableScissor();

        int backWidth = compact ? 62 : 92;
        int confirmWidth = Math.min(compact ? 132 : 194, Math.max(88, bodyWidth / 2));
        int controlY = contentBottom - (compact ? 23 : 30);
        drawControl(graphics, contentLeft, controlY, backWidth, compact ? 22 : 28,
                tr("gtlcore.terminal.button.back"), false, () -> {
                    detailOpen = false;
                    stage = Stage.ROUTE;
                });
        int confirmX = contentRight - confirmWidth;
        drawControl(graphics, confirmX, controlY, confirmWidth, compact ? 22 : 28,
                tr("gtlcore.terminal.button.confirm_route"), true, this::confirmRoute);
    }

    private void renderConfig(GuiGraphics graphics, long now) {
        int headingY = contentTop;
        boolean tight = contentBottom - contentTop < 190;
        graphics.drawString(font, tr("gtlcore.terminal.config.eyebrow"), contentLeft, headingY, CYAN_DIM, false);
        drawScaledText(graphics, tr("gtlcore.terminal.config.title"), contentLeft,
                headingY + (compact ? 13 : 18), compact ? 1.25f : 1.5f, WHITE);

        int backWidth = compact ? 62 : 92;
        int backHeight = compact ? 22 : 28;
        int backX = contentRight - backWidth;
        int questionY = headingY + (tight ? 27 : (compact ? 35 : 48));
        String question = tr("gtlcore.terminal.config.question");
        drawRichWrapped(graphics, question, contentLeft, questionY, contentRight - contentLeft, tight ? 10 : (compact ? 12 : 16), WHITE);

        int warningTop = questionY + (tight ? 13 : (compact ? 17 : 23));
        int warningHeight = tight ? 36 : (compact ? 44 : 60);
        int warningRight = contentRight;
        graphics.fillGradient(contentLeft, warningTop, warningRight, warningTop + warningHeight, 0xCC3B0B19, 0xCC220711);
        graphics.fill(contentLeft, warningTop, warningRight, warningTop + 1, RED);
        graphics.fill(contentLeft, warningTop, contentLeft + 2, warningTop + warningHeight, RED);
        graphics.fill(contentLeft, warningTop + warningHeight - 1, warningRight, warningTop + warningHeight, 0x88FF6478);

        int warningPad = compact ? 8 : 13;
        graphics.drawString(font, tr("gtlcore.terminal.config.warning_title"), contentLeft + warningPad,
                warningTop + (tight ? 4 : (compact ? 5 : 8)), RED, false);
        String warning = tr("gtlcore.terminal.config.warning");
        int warningTextY = warningTop + (tight ? 15 : (compact ? 17 : 24));
        drawRichWrapped(graphics, warning, contentLeft + warningPad, warningTextY, warningRight - contentLeft - warningPad * 2, tight ? 9 : (compact ? 10 : 13), 0xFFFFD4D9);

        int buttonsTop = warningTop + warningHeight + (tight ? 5 : (compact ? 10 : 16));
        int buttonHeight = tight ? 18 : (compact ? 25 : 34);
        int gap = tight ? 5 : (compact ? 7 : 12);
        boolean stacked = panelWidth < 510;
        if (stacked) {
            int usable = contentBottom - buttonsTop;
            int each = Math.max(1, (usable - gap) / 2);
            drawControl(graphics, contentLeft, buttonsTop, contentRight - contentLeft, each,
                    tr("gtlcore.terminal.button.void_miner_yes"), true, () -> chooseMiner(true));
            drawControl(graphics, contentLeft, buttonsTop + each + gap, contentRight - contentLeft,
                    Math.max(1, usable - each - gap), tr("gtlcore.terminal.button.void_miner_no"), false,
                    () -> chooseMiner(false));
        } else {
            int buttonWidth = (contentRight - contentLeft - gap) / 2;
            drawControl(graphics, contentLeft, buttonsTop, buttonWidth, buttonHeight,
                    tr("gtlcore.terminal.button.void_miner_yes"), true, () -> chooseMiner(true));
            drawControl(graphics, contentLeft + buttonWidth + gap, buttonsTop, buttonWidth, buttonHeight,
                    tr("gtlcore.terminal.button.void_miner_no"), false, () -> chooseMiner(false));
        }

        int backY = tight ? contentTop : contentBottom - backHeight;
        drawControl(graphics, backX, backY, backWidth, backHeight,
                tr("gtlcore.terminal.button.back"), false, () -> {
                    stage = Stage.ROUTE;
                    detailOpen = true;
                    detailOpenedAt = System.currentTimeMillis();
                });

        if (!errorMessage.isBlank()) {
            int errorY = tight ? panelY + panelHeight - 13 : Math.min(contentBottom - 8, buttonsTop + buttonHeight + (compact ? 14 : 22));
            String shownError = truncate(errorMessage, contentRight - contentLeft);
            graphics.drawString(font, shownError, contentLeft, errorY, RED, false);
        }
    }

    private void confirmRoute() {
        stage = Stage.CONFIG;
        errorMessage = "";
    }

    private void chooseMiner(boolean enabled) {
        boolean skyblock = ROUTES[routeIndex].skyblock;
        TerminalConfigWriter.Result result = TerminalConfigWriter.apply(Minecraft.getInstance(), skyblock, enabled);
        if (!result.success()) {
            errorMessage = result.message().getString();
            return;
        }

        if (!TerminalState.markCompleted(Minecraft.getInstance())) {
            errorMessage = tr("gtlcore.terminal.error.state_write_failed");
            return;
        }
        errorMessage = "";
        stage = Stage.COMPLETE;
        completionStartedAt = System.currentTimeMillis();
        stopScheduled = false;
    }

    private void renderCompletion(GuiGraphics graphics, long now) {
        String[] lines = new String[] {
                tr("gtlcore.terminal.complete.configured"),
                tr("gtlcore.terminal.complete.restarting"),
                tr("gtlcore.terminal.complete.restart_instruction"),
                tr("gtlcore.terminal.complete.farewell"),
                Minecraft.getInstance().getUser().getName()
        };
        long elapsed = now - completionStartedAt;
        int lineGap = compact ? 16 : 23;
        int totalHeight = lineGap * (lines.length - 1);
        int startY = panelY + (panelHeight - totalHeight) / 2 - (compact ? 2 : 8);

        for (int i = 0; i < lines.length; i++) {
            long revealAt = i == lines.length - 1 ? 2600L : i * COMPLETE_LINE_MS;
            if (elapsed < revealAt) {
                continue;
            }
            double reveal = easeOut((elapsed - revealAt) / 430.0);
            int offset = (int) ((1.0 - reveal) * 7.0);
            int color = i == 0 ? CYAN_BRIGHT : (i == lines.length - 1 ? MAGENTA : WHITE);
            float scale = Math.min(1.0f, Math.max(0.45f, (width - 32.0f) / Math.max(1, font.width(lines[i]))));
            int x = (int) ((width - font.width(lines[i]) * scale) / 2.0f);
            drawScaledText(graphics, lines[i], x, startY + i * lineGap + offset, scale, withAlpha(color, (int) (255 * reveal)));
        }

        int terminalLineY = startY + lines.length * lineGap + (compact ? 5 : 10);
        String terminal = tr("gtlcore.terminal.complete.session_closed");
        float terminalScale = Math.min(1.0f, Math.max(0.5f, (width - 32.0f) / Math.max(1, font.width(terminal))));
        drawScaledText(graphics, terminal, (int) ((width - font.width(terminal) * terminalScale) / 2.0f), terminalLineY, terminalScale, 0x995A8B96);

        if (!stopScheduled && elapsed >= COMPLETE_STOP_MS) {
            stopScheduled = true;
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(minecraft::stop);
        }
    }

    private void drawControl(GuiGraphics graphics, int x, int y, int controlWidth, int controlHeight, String label, boolean primary, Runnable action) {
        boolean hovered = inside(mouseX, mouseY, x, y, controlWidth, controlHeight);
        int accent = primary ? CYAN : 0xFF547A86;
        int top = hovered ? (primary ? 0xEE17455D : 0xEE26353A) : (primary ? 0xDD0D2838 : 0xDD111E24);
        int bottom = hovered ? 0xEE081A28 : 0xDD071018;
        graphics.fillGradient(x, y, x + controlWidth, y + controlHeight, top, bottom);
        graphics.fill(x, y, x + controlWidth, y + 1, hovered ? CYAN_BRIGHT : accent);
        graphics.fill(x, y + controlHeight - 1, x + controlWidth, y + controlHeight, withAlpha(accent, 170));
        graphics.fill(x, y, x + 1, y + controlHeight, withAlpha(accent, 120));
        graphics.fill(x + controlWidth - 1, y, x + controlWidth, y + controlHeight, withAlpha(accent, 120));

        int textWidth = font.width(label);
        int textColor = primary ? (hovered ? 0xFFF0FEFF : 0xFFC8F7FC) : (hovered ? WHITE : 0xFFB7CCD1);
        graphics.drawString(font, label, x + (controlWidth - textWidth) / 2, y + (controlHeight - 8) / 2, textColor, false);
        addHit(x, y, controlWidth, controlHeight, action);
    }

    private void drawArrow(GuiGraphics graphics, int x, int y, int size, boolean right, int color) {
        boolean hovered = inside(mouseX, mouseY, x, y, size, size);
        if (hovered) {
            graphics.fill(x, y, x + size, y + size, 0x552B8FA8);
        }
        graphics.fill(x, y, x + size, y + 1, withAlpha(color, hovered ? 255 : 150));
        graphics.fill(x, y + size - 1, x + size, y + size, withAlpha(color, hovered ? 255 : 150));
        int mid = y + size / 2;
        int centerX = x + size / 2;
        if (right) {
            for (int i = 0; i < size / 3; i++) {
                graphics.fill(centerX + size / 6 - i - 1, mid - i - 1, centerX + size / 6 - i, mid + i + 1, hovered ? CYAN_BRIGHT : CYAN);
            }
        } else {
            for (int i = 0; i < size / 3; i++) {
                graphics.fill(centerX - size / 6 + i, mid - i - 1, centerX - size / 6 + i + 1, mid + i + 1, hovered ? CYAN_BRIGHT : CYAN);
            }
        }
    }

    private void drawScaledText(GuiGraphics graphics, String text, int x, int y, float scale, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1.0f);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.pose().popPose();
    }

    private void drawRichWrapped(GuiGraphics graphics, String text, int x, int y, int maxWidth, int lineHeight, int color) {
        int cursorX = x;
        int cursorY = y;
        for (int offset = 0; offset < text.length();) {
            if (text.charAt(offset) == '[') {
                int end = text.indexOf(']', offset + 1);
                if (end > offset) {
                    String bracket = text.substring(offset, end + 1);
                    int bracketWidth = font.width(bracket);
                    if (cursorX > x && cursorX + bracketWidth > x + maxWidth) {
                        cursorX = x;
                        cursorY += lineHeight;
                    }
                    graphics.drawString(font, bracket, cursorX, cursorY, BLUE, false);
                    cursorX += bracketWidth;
                    offset = end + 1;
                    continue;
                }
            }

            int codePoint = text.codePointAt(offset);
            if (codePoint == '\n') {
                cursorX = x;
                cursorY += lineHeight;
                offset += Character.charCount(codePoint);
                continue;
            }
            String character = new String(Character.toChars(codePoint));
            int charWidth = font.width(character);
            if (cursorX > x && cursorX + charWidth > x + maxWidth) {
                cursorX = x;
                cursorY += lineHeight;
            }
            graphics.drawString(font, character, cursorX, cursorY, color, false);
            cursorX += charWidth;
            offset += Character.charCount(codePoint);
        }
    }

    private String truncateScaled(String text, int maxWidth, float scale) {
        int limit = Math.max(1, (int) Math.floor(maxWidth / Math.max(0.01f, scale)));
        return truncate(text, limit);
    }

    private String truncate(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end) + ellipsis) > maxWidth) {
            end--;
        }
        return text.substring(0, Math.max(0, end)) + ellipsis;
    }

    private void cycleRoute(int delta) {
        routeIndex = Math.floorMod(routeIndex + delta, ROUTES.length);
        detailOpenedAt = System.currentTimeMillis();
    }

    private void addHit(int x, int y, int hitWidth, int hitHeight, Runnable action) {
        hits.add(new Hit(x, y, hitWidth, hitHeight, action));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                if (hit.contains(mouseX, mouseY)) {
                    hit.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (stage == Stage.ROUTE && detailOpen) {
                detailOpen = false;
                return true;
            }
            if (stage == Stage.CONFIG) {
                stage = Stage.ROUTE;
                detailOpen = true;
                detailOpenedAt = System.currentTimeMillis();
                return true;
            }
            if (!mandatory) {
                onClose();
            }
            return true;
        }

        if (stage == Stage.ROUTE && detailOpen) {
            if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_A) {
                cycleRoute(-1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_D) {
                cycleRoute(1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirmRoute();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        if (stage == Stage.ROUTE) {
            if (keyCode == GLFW.GLFW_KEY_1 || keyCode == GLFW.GLFW_KEY_KP_1) {
                openDetail(0);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_2 || keyCode == GLFW.GLFW_KEY_KP_2) {
                openDetail(1);
                return true;
            }
        }

        if (stage == Stage.CONFIG) {
            if (keyCode == GLFW.GLFW_KEY_1 || keyCode == GLFW.GLFW_KEY_KP_1) {
                chooseMiner(true);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_2 || keyCode == GLFW.GLFW_KEY_KP_2) {
                chooseMiner(false);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        if (mandatory) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(returnScreen == null ? new TitleScreen() : returnScreen);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int hitWidth, int hitHeight) {
        return mouseX >= x && mouseX < x + hitWidth && mouseY >= y && mouseY < y + hitHeight;
    }

    private enum Stage {
        BOOT,
        ROUTE,
        CONFIG,
        COMPLETE
    }

    private record Hit(int x, int y, int width, int height, Runnable action) {

        private boolean contains(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, x, y, width, height);
        }
    }

    private record RouteInfo(String title, String destination, String description, boolean skyblock, String highlightTag) {}
}
