package org.gtlcore.gtlcore.client.monitor;

import org.gtlcore.gtlcore.integration.ae2.MEFluidUnits;
import org.gtlcore.gtlcore.integration.monitor.MachineMonitorPackets;
import org.gtlcore.gtlcore.integration.monitor.MachineMonitorRuntime;
import org.gtlcore.gtlcore.utils.NumberUtils;
import org.gtlcore.gtlcore.utils.TextUtil;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.utils.GTUtil;

import com.lowdragmc.lowdraglib.side.fluid.FluidHelper;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.*;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;

import com.mojang.blaze3d.vertex.*;

import java.math.*;
import java.text.*;
import java.util.*;

/** The sole layout used by the physical screen and its inspection UI. Coordinates follow the reference image. */
final class MachineMonitorView {

    private static final float BODY_HEIGHT = 200;
    static final int ROW_HEIGHT = 10;
    // Two information lines and the existing gutter above the reference layout.
    static final int HEADER_HEIGHT = ROW_HEIGHT * 3;
    static final int ROWS_TOP = HEADER_HEIGHT + 98;
    static final float WIDTH = 300, HEIGHT = BODY_HEIGHT + HEADER_HEIGHT;
    static final int BORDER = 0x2D3035;
    private static final int BASE = 0x0B0C0E, PANEL = 0x17191C, GREEN = 0x5FD07E, TEXT = 0xE0E2E6, MUTED = 0x90969F;
    private static final DecimalFormat DECIMAL = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
    // One layout pixel at the required physical screen size.
    static final float LAYER_DEPTH = Math.min(MachineMonitorRuntime.SCREEN_WIDTH / WIDTH, MachineMonitorRuntime.SCREEN_HEIGHT / BODY_HEIGHT);

    enum Layer {

        MARGIN,
        FRAME,
        BACKGROUND,
        PANEL,
        PROGRESS_TRACK,
        PROGRESS_FILL,
        CONTENT;

        float depth() {
            return (ordinal() + 1) * LAYER_DEPTH;
        }
    }

    private MachineMonitorView() {}

    static void draw(Canvas canvas, MachineMonitorClient.Display display) {
        int start = display.state.getInt("Page") * MachineMonitorPackets.ROWS_PER_PAGE;
        draw(canvas, display, start, start);
    }

    static void draw(Canvas canvas, MachineMonitorClient.Display display, int inputStart, int outputStart) {
        canvas.rect(0, 0, WIDTH, HEIGHT, BORDER, Layer.FRAME);
        canvas.rect(2, 2, WIDTH - 4, HEIGHT - 4, BASE, Layer.BACKGROUND);
        canvas.rect(7, 7, WIDTH - 14, HEADER_HEIGHT - 6, PANEL, Layer.PANEL);
        for (float x : new float[] { 7, 154 }) {
            canvas.rect(x, HEADER_HEIGHT + 7, 139, 17, PANEL, Layer.PANEL);
            canvas.rect(x, HEADER_HEIGHT + 30, 139, 44, PANEL, Layer.PANEL);
            canvas.rect(x, HEADER_HEIGHT + 81, 139, 113, PANEL, Layer.PANEL);
        }
        var state = display.state;
        canvas.text(machineName(state), 11, 11, TEXT, 278);
        canvas.fitted(location(state), 11, 22, MUTED, 278, false);
        String status = state.getString("Status");
        label(canvas, "status", 11, HEADER_HEIGHT + 11);
        canvas.right(Component.translatable("monitor.gtlcore.machine.state." + status), 142, HEADER_HEIGHT + 11, status.equals("working") ? GREEN : MUTED, 50);
        label(canvas, "type", 158, HEADER_HEIGHT + 11);
        canvas.right(state.contains("RecipeType") ? Component.literal(state.getString("RecipeType")) : Component.literal("—"), 289, HEADER_HEIGHT + 11, TEXT, 89);
        label(canvas, "progress", 11, HEADER_HEIGHT + 35);
        boolean hasRecipe = state.getBoolean("HasRecipe");
        boolean computation = state.getBoolean("Computation");
        double progress = display.progress();
        double total = display.total();
        double ratio = hasRecipe && total > 0 ? Math.max(0, Math.min(1, progress / total)) : 0;
        double percent = hasRecipe && total > 0 ? Math.max(0, Math.min(100, progress * 100 / total)) : 0;
        canvas.right(Component.literal(hasRecipe ? (int) Math.floor(percent) + "%" : "—"), 142, HEADER_HEIGHT + 35, TEXT, 45);
        Component time = !hasRecipe ? Component.literal("—") : computation ?
                Component.translatable("monitor.gtlcore.machine.indefinite") : Component.translatable("monitor.gtlcore.machine.time",
                        decimal(progress / SharedConstants.TICKS_PER_SECOND), decimal(total / SharedConstants.TICKS_PER_SECOND));
        canvas.fitted(time, 11, HEADER_HEIGHT + 48, MUTED, 131, false);
        canvas.rect(11, HEADER_HEIGHT + 64, 131, 5, 0x272A2F, Layer.PROGRESS_TRACK);
        canvas.rect(11, HEADER_HEIGHT + 64, (float) (131 * ratio), 5, GREEN, Layer.PROGRESS_FILL);
        label(canvas, display.state.contains("SteamConversion") ? "steam" : "energy", 158, HEADER_HEIGHT + 35);
        Component energy = hasRecipe ? energy(state, false) : Component.literal("—");
        if (Minecraft.getInstance().font.width(energy) > 131) energy = energy(state, true);
        canvas.fitted(energy, 158, HEADER_HEIGHT + 48, TEXT, 131, false);
        label(canvas, "inputs", 11, HEADER_HEIGHT + 85);
        label(canvas, "outputs", 158, HEADER_HEIGHT + 85);
        if (hasRecipe) {
            drawRows(canvas, display.inputs, 11, display, inputStart);
            drawRows(canvas, display.outputs, 158, display, outputStart);
        }
    }

    static Component machineName(CompoundTag state) {
        Component name = state.contains("MachineName") ? Component.translatable(state.getString("MachineName")) : Component.literal("—");
        return Component.translatable("monitor.gtlcore.machine.name", name);
    }

    static Component location(CompoundTag state) {
        if (!state.contains("TargetPosition")) return Component.literal("—");
        var pos = BlockPos.of(state.getLong("TargetPosition"));
        return Component.translatable("monitor.gtlcore.machine.location", pos.getX(), pos.getY(), pos.getZ(), state.getString("TargetDimension"));
    }

    static Component energy(CompoundTag state, boolean compact) {
        var recipe = state.getCompound("Recipe");
        long eut = recipe.getLong("EUt");
        if (eut <= 0) return Component.literal("—");
        boolean input = recipe.getBoolean("isInput");
        if (state.contains("SteamConversion")) {
            double amount = Math.ceil(eut * state.getDouble("SteamConversion"));
            return Component.translatable("monitor.gtlcore.machine." + (input ? "consume" : "produce"))
                    .append(Component.literal((compact ? MEFluidUnits.formatDisplayAmount(amount) : decimal(amount) + " mB") + "/t").withStyle(ChatFormatting.GREEN));
        }
        int tier = GTUtil.getTierByVoltage(eut);
        var power = Component.literal((compact ? NumberUtils.formatLong(eut) : decimal(eut)) + " EU/t").withStyle(input ? ChatFormatting.RED : ChatFormatting.GREEN);
        var amps = Component.literal(decimal((double) eut / GTValues.V[tier]) + "A " + GTValues.VN[tier])
                .withStyle(style -> style.withColor(TextUtil.GTL_CORE$VC[tier]));
        return Component.translatable("monitor.gtlcore.machine." + (input ? "consume" : "produce"))
                .append(power).append(Component.literal(" (").withStyle(ChatFormatting.GREEN))
                .append(amps).append(Component.literal(")").withStyle(ChatFormatting.GREEN));
    }

    private static void drawRows(Canvas canvas, List<Row> rows, int left, MachineMonitorClient.Display display, int start) {
        for (int i = 0; i < MachineMonitorPackets.ROWS_PER_PAGE && start + i < rows.size(); i++) {
            Row row = rows.get(start + i);
            int y = ROWS_TOP + i * ROW_HEIGHT;
            canvas.icon(row, left + 1, y - 1);
            canvas.text(row.name(), left + 12, y, row.nonConsumed ? MUTED : TEXT, row.nonConsumed ? 65 : 52);
            if (row.nonConsumed) {
                canvas.right(Component.translatable("monitor.gtlcore.machine.non_consumed"), left + 131, y, MUTED, 44);
            } else {
                canvas.fitted(Component.literal(row.amount(true)), left + 96, y, TEXT, 30, true);
                canvas.fitted(Component.literal(row.rate(display.state, true)), left + 131, y, MUTED, 33, true);
            }
        }
        if (rows.size() > MachineMonitorPackets.ROWS_PER_PAGE) {
            int trackX = left + 137;
            int trackY = ROWS_TOP - 2;
            int trackH = MachineMonitorPackets.ROWS_PER_PAGE * ROW_HEIGHT;
            int maxStart = Math.max(1, rows.size() - MachineMonitorPackets.ROWS_PER_PAGE);
            int barH = Math.max(6, trackH * MachineMonitorPackets.ROWS_PER_PAGE / rows.size());
            int barY = trackY + (trackH - barH) * Math.min(Math.max(start, 0), maxStart) / maxStart;
            canvas.rect(trackX, trackY, 2, trackH, 0x40000000, Layer.PANEL);
            canvas.rect(trackX, barY, 2, barH, 0xFFC8D0DC, Layer.PANEL);
        }
    }

    static String decimal(double value) {
        return DECIMAL.format(value);
    }

    static String decimal(long value) {
        return DECIMAL.format(value);
    }

    private static void label(Canvas canvas, String key, int x, int y) {
        canvas.text(Component.translatable("monitor.gtlcore.machine.label." + key), x, y, GREEN, 80);
    }

    static List<Row> rows(ListTag data) {
        List<Row> result = new ArrayList<>();
        for (Tag tag : data) result.add(new Row((CompoundTag) tag));
        return result;
    }

    static final class Row {

        final List<ItemStack> items = new ArrayList<>();
        final List<FluidStack> fluids = new ArrayList<>();
        final long amount, maxAmount;
        final boolean nonConsumed, tick;
        final int chance, maxChance;

        private Row(CompoundTag tag) {
            for (Tag item : tag.getList("Items", Tag.TAG_COMPOUND)) items.add(ItemStack.of((CompoundTag) item));
            for (Tag fluid : tag.getList("Fluids", Tag.TAG_COMPOUND)) fluids.add(FluidStack.loadFromTag((CompoundTag) fluid));
            amount = tag.getLong("Amount");
            maxAmount = tag.getLong("MaxAmount");
            nonConsumed = tag.getBoolean("NonConsumed");
            tick = tag.getBoolean("Tick");
            chance = tag.getInt("Chance");
            maxChance = tag.getInt("MaxChance");
        }

        int alternative() {
            var level = Minecraft.getInstance().level;
            return level == null ? 0 : (int) (level.getGameTime() / SharedConstants.TICKS_PER_SECOND);
        }

        ItemStack item() {
            return items.isEmpty() ? ItemStack.EMPTY : items.get(Math.floorMod(alternative(), items.size()));
        }

        FluidStack fluid() {
            return fluids.get(Math.floorMod(alternative(), fluids.size()));
        }

        Component name() {
            Component name = items.isEmpty() ? fluid().getDisplayName() : item().getHoverName();
            int alternatives = items.size() + fluids.size();
            if (alternatives > 1) name = Component.translatable("monitor.gtlcore.machine.alternatives", name);
            return chance > 0 && chance < maxChance ? Component.translatable("monitor.gtlcore.machine.chanced", name) : name;
        }

        String amount(boolean compact) {
            String value = amount(amount, compact);
            if (maxAmount > amount) value += "–" + amount(maxAmount, compact);
            return value + (tick ? "/t" : "");
        }

        private String amount(long value, boolean compact) {
            if (compact) return fluids.isEmpty() ? NumberUtils.formatLong(value) : MEFluidUnits.formatDisplayAmount(value);
            return decimal(value) + (fluids.isEmpty() ? "" : " mB");
        }

        String rate(CompoundTag state, boolean compact) {
            int duration = state.getInt("MaxProgress");
            if (nonConsumed || (!tick && (state.getBoolean("Computation") || duration <= 0)) || (chance > 0 && chance < maxChance)) return "—";
            String value = rate(amount, duration, compact);
            return maxAmount > amount ? value + "–" + rate(maxAmount, duration, compact) : value;
        }

        private String rate(long amount, int duration, boolean compact) {
            BigDecimal numerator = BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(SharedConstants.TICKS_PER_SECOND));
            BigDecimal value = tick ? numerator : numerator.divide(BigDecimal.valueOf(duration), 2, RoundingMode.HALF_UP);
            if (compact) return (fluids.isEmpty() ? NumberUtils.formatDouble(value.doubleValue()) : MEFluidUnits.formatDisplayAmount(value.doubleValue())) + "/s";
            return DECIMAL.format(value) + (fluids.isEmpty() ? "/s" : " mB/s");
        }
    }

    interface Canvas {

        void rect(float x, float y, float width, float height, int color, Layer layer);

        void draw(Component text, float x, float y, int color, float scale);

        void icon(Row row, float x, float y);

        default void text(Component text, float x, float y, int color, int width) {
            draw(fit(text, width), x, y, color, 1);
        }

        default void right(Component text, float x, float y, int color, int width) {
            Component clipped = fit(text, width);
            draw(clipped, x - Minecraft.getInstance().font.width(clipped), y, color, 1);
        }

        default void fitted(Component text, float x, float y, int color, int width, boolean right) {
            var font = Minecraft.getInstance().font;
            int textWidth = font.width(text);
            float scale = Math.min(1, (float) width / Math.max(1, textWidth));
            draw(text, right ? x - textWidth * scale : x, y + font.lineHeight * (1 - scale) / 2, color, scale);
        }

        private static Component fit(Component text, int width) {
            var font = Minecraft.getInstance().font;
            if (font.width(text) <= width) return text;
            var result = Component.empty();
            font.substrByWidth(text, Math.max(0, width - font.width("…"))).visit((style, value) -> {
                result.append(Component.literal(value).setStyle(style));
                return Optional.empty();
            }, Style.EMPTY);
            return result.append("…");
        }
    }

    static final class GuiCanvas implements Canvas {

        private final GuiGraphics graphics;

        GuiCanvas(GuiGraphics graphics) {
            this.graphics = graphics;
        }

        public void rect(float x, float y, float width, float height, int color, Layer layer) {
            graphics.fill((int) x, (int) y, (int) (x + width), (int) (y + height), 0xFF000000 | color);
        }

        public void draw(Component text, float x, float y, int color, float scale) {
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(x, y, 0);
            pose.scale(scale, scale, 1);
            graphics.drawString(Minecraft.getInstance().font, text, 0, 0, color, false);
            pose.popPose();
        }

        public void icon(Row row, float x, float y) {
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(x, y, 0);
            pose.scale(.5f, .5f, 1);
            if (!row.items.isEmpty()) graphics.renderItem(row.item(), 0, 0);
            else {
                var fluid = row.fluid();
                var extension = IClientFluidTypeExtensions.of(fluid.getFluid());
                var sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(extension.getStillTexture());
                int tint = extension.getTintColor();
                graphics.blit(0, 0, 0, 16, 16, sprite, (tint >> 16 & 255) / 255f, (tint >> 8 & 255) / 255f, (tint & 255) / 255f, 1);
            }
            pose.popPose();
        }
    }

    static final class WorldCanvas implements Canvas {

        private final PoseStack pose;
        private final MultiBufferSource buffers;

        WorldCanvas(PoseStack pose, MultiBufferSource buffers) {
            this.pose = pose;
            this.buffers = buffers;
        }

        public void rect(float x, float y, float width, float height, int color, Layer layer) {
            if (width <= 0 || height <= 0) return;
            var buffer = buffers.getBuffer(RenderType.textBackground());
            var matrix = pose.last().pose();
            float depth = layer.depth();
            int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
            buffer.vertex(matrix, x, y, depth).color(r, g, b, 255).uv2(LightTexture.FULL_BRIGHT).endVertex();
            buffer.vertex(matrix, x, y + height, depth).color(r, g, b, 255).uv2(LightTexture.FULL_BRIGHT).endVertex();
            buffer.vertex(matrix, x + width, y + height, depth).color(r, g, b, 255).uv2(LightTexture.FULL_BRIGHT).endVertex();
            buffer.vertex(matrix, x + width, y, depth).color(r, g, b, 255).uv2(LightTexture.FULL_BRIGHT).endVertex();
        }

        public void draw(Component text, float x, float y, int color, float scale) {
            pose.pushPose();
            pose.translate(x, y, Layer.CONTENT.depth());
            pose.scale(scale, scale, 1);
            Minecraft.getInstance().font.drawInBatch(text, 0, 0, color, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            pose.popPose();
        }

        public void icon(Row row, float x, float y) {
            if (!row.items.isEmpty()) {
                pose.pushPose();
                pose.translate(x + 4, y + 4, Layer.CONTENT.depth());
                pose.scale(8, -8, LAYER_DEPTH);
                Minecraft.getInstance().getItemRenderer().renderStatic(row.item(), ItemDisplayContext.GUI, LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY, pose, buffers, Minecraft.getInstance().level, 0);
                pose.popPose();
            } else {
                var fluid = row.fluid();
                var sprite = FluidHelper.getStillTexture(fluid);
                int color = FluidHelper.getColor(fluid);
                var buffer = buffers.getBuffer(RenderType.text(InventoryMenu.BLOCK_ATLAS));
                vertex(buffer, x, y + 8, sprite.getU0(), sprite.getV1(), color);
                vertex(buffer, x + 8, y + 8, sprite.getU1(), sprite.getV1(), color);
                vertex(buffer, x + 8, y, sprite.getU1(), sprite.getV0(), color);
                vertex(buffer, x, y, sprite.getU0(), sprite.getV0(), color);
            }
        }

        private void vertex(VertexConsumer buffer, float x, float y, float u, float v, int color) {
            buffer.vertex(pose.last().pose(), x, y, Layer.CONTENT.depth()).color(color >> 16 & 255, color >> 8 & 255, color & 255, color >>> 24).uv(u, v)
                    .uv2(LightTexture.FULL_BRIGHT).endVertex();
        }
    }
}
