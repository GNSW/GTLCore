package org.gtlcore.gtlcore.client.monitor;

import org.gtlcore.gtlcore.integration.gtmt.monitor.*;

import com.gregtechceu.gtceu.utils.FormattingUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.IEventBus;

import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;

import java.math.*;

public final class EnergyMonitorRenderer implements BlockEntityRenderer<EnergyMonitorBlockEntity> {

    public static final int[] COLORS = { 0x55CCFF, 0xFFCC55, 0xCC88FF, 0xFF88BB };
    public static final String[] PERIODS = { "1m", "5m", "1h", "24h" };

    public EnergyMonitorRenderer(BlockEntityRendererProvider.Context c) {}

    public static void register(IEventBus bus) {
        bus.addListener(EnergyMonitorRenderer::renderers);
    }

    private static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerBlockEntityRenderer(EnergyMonitorContent.ENTITY.get(), EnergyMonitorRenderer::new);
    }

    public static int color(String value) {
        try {
            int sign = new BigDecimal(value).signum();
            return sign > 0 ? 0x55DD77 : sign < 0 ? 0xFF5555 : 0xAAAAAA;
        } catch (NumberFormatException e) {
            return 0xAAAAAA;
        }
    }

    public static String compact(String value) {
        try {
            BigDecimal v = new BigDecimal(value);
            java.text.DecimalFormat format = new java.text.DecimalFormat("#,##0.###", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US));
            format.setMaximumFractionDigits(Math.max(0, Math.min(3, v.scale())));
            return format.format(v);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    public static String gtPower(String value) {
        BigDecimal power = new BigDecimal(value);
        BigDecimal magnitude = power.abs();
        int tier = 0;
        for (int i = 1; i <= com.gregtechceu.gtceu.api.GTValues.MAX; i++) {
            if (magnitude.compareTo(BigDecimal.valueOf(com.gregtechceu.gtceu.api.GTValues.V[i])) < 0) break;
            tier = i;
        }
        BigDecimal voltage = BigDecimal.valueOf(com.gregtechceu.gtceu.api.GTValues.V[tier]);
        int extension = 0;
        if (tier == com.gregtechceu.gtceu.api.GTValues.MAX) {
            while (extension < 16 && magnitude.compareTo(voltage.multiply(BigDecimal.valueOf(4))) >= 0) {
                voltage = voltage.multiply(BigDecimal.valueOf(4));
                extension++;
            }
        }
        BigDecimal amps = power.divide(voltage, 3, RoundingMode.HALF_UP);
        String name = extension == 0 ? com.gregtechceu.gtceu.api.GTValues.VN[tier] : "MAX+" + extension;
        return (power.signum() > 0 ? "+" : "") + compact(power.toPlainString()) + " EU/t (" + compact(amps.toPlainString()) + "A " + name + ")";
    }

    public void render(EnergyMonitorBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (!be.formed()) return;
        CompoundTag data = be.display();
        pose.pushPose();
        pose.translate(.5, .5, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-be.getBlockState().getValue(EnergyMonitorBlock.FACING).toYRot()));
        pose.translate(-2.42, 2.42, .506);
        pose.scale(.01f, -.01f, .01f);
        String title = Component.translatable("monitor.gtlcore.formed_title").getString();
        text(pose, buffers, title, 12, 12, 0xFFFFFF);
        if (data.contains("ownerName")) {
            Component owner = null;
            try {
                owner = Component.Serializer.fromJson(data.getString("ownerName"));
            } catch (RuntimeException ignored) {}
            if (owner != null) {
                float scale = Math.min(1.7f, (484f - 12f) / Math.max(1, Minecraft.getInstance().font.width(title)));
                text(pose, buffers, Component.translatable("monitor.gtlcore.owner", owner).getString(),
                        12f + Minecraft.getInstance().font.width(title) * scale + 8f, 12, 0x55CCFF);
            }
        }
        for (int i = 0; i < 4; i++) {
            int y = 68 + i * 32;
            largeText(pose, buffers, PERIODS[i], 12, y, COLORS[i]);
            String v = data.contains("power" + i) ? data.getString("power" + i) : "";
            largeText(pose, buffers, v.isEmpty() ? Component.translatable("monitor.gtlcore.collecting").getString() : gtPower(v), 110, y, color(v));
        }
        chart(pose, buffers, data.getList("changes5m", Tag.TAG_COMPOUND), 12, 228, true, data.getLong("now"), Component.translatable("monitor.gtlcore.chart_changes").getString());
        chart(pose, buffers, data.getList("points", Tag.TAG_COMPOUND), 250, 228, false, data.getLong("now"), Component.translatable("monitor.gtlcore.chart_history").getString());
        String estimate;
        if (!data.contains("power0")) {
            estimate = Component.translatable("monitor.gtlcore.estimate_collecting").getString();
        } else {
            int sign = new BigDecimal(data.getString("power0")).signum();
            if (sign > 0) estimate = Component.translatable("monitor.gtlcore.fill_forever").getString();
            else if (sign < 0) {
                String drain = data.contains("drainSeconds") ? drainEta(data.getString("drainSeconds")) : Component.translatable("monitor.gtlcore.collecting").getString();
                estimate = Component.translatable("monitor.gtlcore.drain_eta", drain).getString();
            } else estimate = Component.translatable("monitor.gtlcore.estimate_stable").getString();
        }
        text(pose, buffers, estimate, 12, 34, 0xAAAAAA);
        pose.popPose();
    }

    private static String drainEta(String secondsText) {
        long total;
        try {
            total = new BigInteger(secondsText).min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
        } catch (NumberFormatException e) {
            return Component.translatable("monitor.gtlcore.collecting").getString();
        }
        if (total <= 0L) return Component.translatable("monitor.gtlcore.collecting").getString();
        long days = total / 86400L;
        long hours = total % 86400L / 3600L;
        long minutes = total % 3600L / 60L;
        long seconds = total % 60L;
        StringBuilder text = new StringBuilder();
        if (days > 0L) text.append(Component.translatable("monitor.gtlcore.time_days", FormattingUtil.formatNumbers(days)).getString());
        if (days > 0L || hours > 0L) text.append(Component.translatable("monitor.gtlcore.time_hours", FormattingUtil.formatNumbers(hours)).getString());
        if (days > 0L || hours > 0L || minutes > 0L) text.append(Component.translatable("monitor.gtlcore.time_minutes", FormattingUtil.formatNumbers(minutes)).getString());
        text.append(Component.translatable("monitor.gtlcore.time_seconds", FormattingUtil.formatNumbers(seconds)).getString());
        return text.toString();
    }

    private static void chart(PoseStack pose, MultiBufferSource buffers, ListTag points, int left, int width, boolean change, long now, String title) {
        int curveColor = COLORS[change ? 0 : 3];
        if (points.isEmpty()) {
            chartText(pose, buffers, Component.translatable("monitor.gtlcore.collecting").getString(), left, 280, width, 0xAAAAAA);
            return;
        }
        BigInteger min = BigInteger.ZERO, max = BigInteger.ZERO;
        for (Tag element : points) {
            BigInteger value = new BigInteger(((CompoundTag) element).getString("energy"));
            min = min.min(value);
            max = max.max(value);
        }
        if (min.equals(max)) {
            max = max.add(BigInteger.ONE);
            if (change) min = min.subtract(BigInteger.ONE);
        }
        BigInteger span = max.subtract(min);
        chartText(pose, buffers, compact(max.toString()) + " EU", left, 237, width, 0xAAAAAA);
        chartText(pose, buffers, compact(min.toString()) + " EU", left, 412, width, 0xAAAAAA);
        chartText(pose, buffers, title, left, 218, width, 0xAAAAAA);
        int x0 = left + 6, x1 = left + width - 6;
        line(pose, buffers, x0, 260, x0, 405, 0x777777);
        line(pose, buffers, x0, 405, x1, 405, 0x777777);
        if (change) {
            float zero = 405 - 145 * new BigDecimal(min.negate()).divide(new BigDecimal(span), 12, RoundingMode.HALF_UP).floatValue();
            line(pose, buffers, x0, zero, x1, zero, 0x444444);
        }
        long start = change ? now - EnergyHistory.WINDOWS[1] : points.getCompound(0).getLong("tick");
        long duration = Math.max(1, now - start);
        float previousX = 0, previousY = 0;
        boolean first = true;
        for (Tag element : points) {
            CompoundTag point = (CompoundTag) element;
            float x = x0 + (x1 - x0) * (float) (point.getLong("tick") - start) / duration;
            float y = 405 - 145 * new BigDecimal(new BigInteger(point.getString("energy")).subtract(min)).divide(new BigDecimal(span), 12, RoundingMode.HALF_UP).floatValue();
            if (!first) line(pose, buffers, previousX, previousY, x, y, curveColor);
            else line(pose, buffers, x - 1, y, x + 1, y, curveColor);
            previousX = x;
            previousY = y;
            first = false;
        }
        chartText(pose, buffers, change ? "-5m" : Component.translatable("monitor.gtlcore.history").getString(), left, 435, width / 2, 0xAAAAAA);
        chartText(pose, buffers, "0", x1 - 10, 435, 10, 0xAAAAAA);
    }

    private static void chartText(PoseStack pose, MultiBufferSource buffers, String value, int x, int y, int width, int color) {
        pose.pushPose();
        pose.translate(x, y, 0);
        float scale = Math.min(1.7f, (float) width / Math.max(1, Minecraft.getInstance().font.width(value)));
        pose.scale(scale, scale, scale);
        drawText(pose, buffers, value, 0, 0, color);
        pose.popPose();
    }

    private static void largeText(PoseStack p, MultiBufferSource b, String s, float x, float y, int c) {
        p.pushPose();
        p.translate(x, y, 0);
        float scale = Math.min(2.2f, 350f / Math.max(1, Minecraft.getInstance().font.width(s)));
        p.scale(scale, scale, scale);
        drawText(p, b, s, 0, 0, c);
        p.popPose();
    }

    private static void text(PoseStack p, MultiBufferSource b, String s, float x, float y, int c) {
        p.pushPose();
        p.translate(x, y, 0);
        float scale = Math.min(1.7f, (484f - x) / Math.max(1, Minecraft.getInstance().font.width(s)));
        p.scale(scale, scale, scale);
        drawText(p, b, s, 0, 0, c);
        p.popPose();
    }

    private static void drawText(PoseStack p, MultiBufferSource b, String s, float x, float y, int c) {
        Minecraft.getInstance().font.drawInBatch(s, x, y, c, false, p.last().pose(), b, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
    }

    private static void line(PoseStack p, MultiBufferSource b, float x, float y, float xx, float yy, int color) {
        float dx = xx - x, dy = yy - y, length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length == 0) return;
        float nx = -dy / length * .75f, ny = dx / length * .75f;
        VertexConsumer v = b.getBuffer(RenderType.textBackground());
        int r = (color >> 16) & 255, g = (color >> 8) & 255, bl = color & 255;
        v.vertex(p.last().pose(), x + nx, y + ny, 0).color(r, g, bl, 255).uv2(LightTexture.FULL_BRIGHT).endVertex();
        v.vertex(p.last().pose(), xx + nx, yy + ny, 0).color(r, g, bl, 255).uv2(LightTexture.FULL_BRIGHT).endVertex();
        v.vertex(p.last().pose(), xx - nx, yy - ny, 0).color(r, g, bl, 255).uv2(LightTexture.FULL_BRIGHT).endVertex();
        v.vertex(p.last().pose(), x - nx, y - ny, 0).color(r, g, bl, 255).uv2(LightTexture.FULL_BRIGHT).endVertex();
    }

    public int getViewDistance() {
        return 96;
    }
}
