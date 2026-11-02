package org.gtlcore.gtlcore.client.spray;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.item.InfiniteSprayCanBehavior;
import org.gtlcore.gtlcore.common.item.InfiniteSprayCanItem;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, value = Dist.CLIENT)
public final class InfiniteSprayCanClient {

    // A four-pixel ring with an inner radius of ten leaves the vanilla 15-pixel crosshair clear.
    private static final int HOLD_RING_RADIUS = 14;
    private static final int HOLD_RING_THICKNESS = 4;
    private static final int HOLD_RING_COLOR = 0xFF35C8E8;
    private static final int HOLD_RING_BACKGROUND_COLOR = 0x5535C8E8;

    @Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {

        @SubscribeEvent
        public static void overlays(RegisterGuiOverlaysEvent event) {
            event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "infinite_spray_can_hold",
                    InfiniteSprayCanClient::renderHoldProgress);
        }
    }

    private static void renderHoldProgress(ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height) {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (mc.options.hideGui || mc.screen != null || player == null || !player.isUsingItem()) return;
        ItemStack stack = player.getUseItem();
        if (!(stack.getItem() instanceof InfiniteSprayCanItem) || !player.isShiftKeyDown()) return;

        int ticks = player.getTicksUsingItem();
        if (ticks > InfiniteSprayCanBehavior.CONFIG_HOLD_TICKS) return;
        float progress = Math.min((ticks + partialTick) / InfiniteSprayCanBehavior.CONFIG_HOLD_TICKS, 1);
        int x = width / 2;
        int y = height / 2;

        graphics.flush();
        gui.setupOverlayRenderState(true, false);
        ShaderInstance previousShader = RenderSystem.getShader();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        try {
            drawHoldArc(graphics, x, y, 1, HOLD_RING_BACKGROUND_COLOR);
            if (progress > 0) drawHoldArc(graphics, x, y, progress, HOLD_RING_COLOR);
        } finally {
            restoreShader(previousShader);
        }
        graphics.drawCenteredString(mc.font,
                Component.translatable("item.gtlcore.infinite_spray_can.hold_progress", (int) (progress * 100)),
                x, y + HOLD_RING_RADIUS + mc.font.lineHeight / 2, ChatFormatting.WHITE.getColor());
    }

    private static void drawHoldArc(GuiGraphics graphics, int x, int y, float progress, int color) {
        double sweep = Math.PI * 2 * progress;
        int segments = (int) Math.ceil(sweep * HOLD_RING_RADIUS);
        var matrix = graphics.pose().last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i <= segments; i++) {
            double angle = -Math.PI / 2 + sweep * i / segments;
            float dx = (float) Math.cos(angle);
            float dy = (float) Math.sin(angle);
            buffer.vertex(matrix, x + dx * HOLD_RING_RADIUS, y + dy * HOLD_RING_RADIUS, 0).color(color).endVertex();
            buffer.vertex(matrix, x + dx * (HOLD_RING_RADIUS - HOLD_RING_THICKNESS),
                    y + dy * (HOLD_RING_RADIUS - HOLD_RING_THICKNESS), 0).color(color).endVertex();
        }
        BufferUploader.drawWithShader(buffer.end());
    }

    private static void restoreShader(ShaderInstance shader) {
        RenderSystem.setShader(() -> shader);
    }
}
