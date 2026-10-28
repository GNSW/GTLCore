package org.gtlcore.gtlcore.client.manipulator;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.item.MatterManipulatorBehavior;
import org.gtlcore.gtlcore.common.item.manipulator.ManipulatorGeometry;
import org.gtlcore.gtlcore.common.item.manipulator.ManipulatorSettings;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.network.ManipulatorPackets;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import static org.lwjgl.opengl.GL30C.*;

@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, value = Dist.CLIENT)
public final class ManipulatorClient {

    private static KeyMapping[] keys = {};
    private static final BufferBuilder PREVIEW_BUFFER = new BufferBuilder(RenderType.lines().bufferSize());
    // A four-pixel ring with an inner radius of ten leaves the vanilla 15-pixel crosshair clear.
    private static final int HOLD_RING_RADIUS = 14;
    private static final int HOLD_RING_THICKNESS = 4;
    private static final int HOLD_RING_COLOR = 0xFF00A4D9;
    private static final int HOLD_RING_BACKGROUND_COLOR = 0x47DCE6F0;
    private static boolean startingGesture;
    private static boolean waitForUseRelease;
    private static final MatterManipulatorBehavior.Action[] ACTIONS = {
            MatterManipulatorBehavior.Action.COPY, MatterManipulatorBehavior.Action.CUT, MatterManipulatorBehavior.Action.PASTE, MatterManipulatorBehavior.Action.CLEAR
    };

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.gtlcore.manipulator." + name, KeyConflictContext.IN_GAME, KeyModifier.CONTROL, InputConstants.Type.KEYSYM, code, "key.categories.gtlcore.manipulator");
    }

    @Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {

        @SubscribeEvent
        public static void overlays(RegisterGuiOverlaysEvent event) {
            event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "manipulator_hold", ManipulatorClient::renderHoldProgress);
        }

        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) {
            ManipulatorClient.keys = new KeyMapping[] {
                    key("copy", GLFW.GLFW_KEY_C), key("cut", GLFW.GLFW_KEY_X), key("paste", GLFW.GLFW_KEY_V), key("clear", GLFW.GLFW_KEY_Z)
            };
            for (KeyMapping key : ManipulatorClient.keys) event.register(key);
        }
    }

    private static ItemStack tool() {
        var player = Minecraft.getInstance().player;
        if (player == null) return ItemStack.EMPTY;
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof MatterManipulatorBehavior)) stack = player.getOffhandItem();
        return stack.getItem() instanceof MatterManipulatorBehavior && !MatterManipulatorBehavior.isMarker(stack) ? stack : ItemStack.EMPTY;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        var mc = Minecraft.getInstance();
        updateUseGesture(mc);
        if (event.phase != TickEvent.Phase.END) return;
        boolean enabled = mc.screen == null && !tool().isEmpty() && !startingGesture && !waitForUseRelease;
        for (int i = 0; i < keys.length; i++) while (keys[i].consumeClick()) if (enabled) ManipulatorPackets.send(ACTIONS[i]);
    }

    private static boolean usingTool(Minecraft mc) {
        return mc.player != null && mc.player.isUsingItem() && mc.player.getUseItem().getItem() instanceof MatterManipulatorBehavior &&
                !MatterManipulatorBehavior.isMarker(mc.player.getUseItem());
    }

    private static void updateUseGesture(Minecraft mc) {
        if (mc.player == null || !mc.options.keyUse.isDown()) {
            startingGesture = false;
            waitForUseRelease = false;
            return;
        }
        boolean using = usingTool(mc);
        if (startingGesture && (!using || !mc.player.isShiftKeyDown() || mc.screen != null)) {
            waitForUseRelease = true;
            if (using) mc.gameMode.releaseUsingItem(mc.player);
        }
        if (using && !waitForUseRelease) startingGesture = mc.player.getTicksUsingItem() <= MatterManipulatorBehavior.HOLD_TICKS;
    }

    @SubscribeEvent
    public static void pick(InputEvent.InteractionKeyMappingTriggered event) {
        var mc = Minecraft.getInstance();
        updateUseGesture(mc);
        if (waitForUseRelease || startingGesture && !event.isUseItem()) {
            event.setCanceled(true);
            event.setSwingHand(false);
            return;
        }
        if (!event.isPickBlock() || tool().isEmpty()) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        ManipulatorPackets.send(mc.player.isShiftKeyDown() ? MatterManipulatorBehavior.Action.WHITELIST : MatterManipulatorBehavior.Action.PICK);
    }

    private static void renderHoldProgress(ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height) {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (mc.options.hideGui || mc.screen != null || waitForUseRelease || player == null || !player.isUsingItem()) return;
        ItemStack stack = player.getUseItem();
        if (!(stack.getItem() instanceof MatterManipulatorBehavior) || MatterManipulatorBehavior.isMarker(stack)) return;
        int ticks = player.getTicksUsingItem();
        // Vanilla decrements the remaining duration after onUseTick; keep the full ring for that tick.
        if (ticks > MatterManipulatorBehavior.HOLD_TICKS) return;
        float progress = Math.min((ticks + partialTick) / MatterManipulatorBehavior.HOLD_TICKS, 1);
        int x = width / 2;
        int y = height / 2;
        graphics.flush();
        gui.setupOverlayRenderState(true, false);
        var shader = RenderSystem.getShader();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        try {
            drawHoldArc(graphics, x, y, 1, HOLD_RING_BACKGROUND_COLOR);
            if (progress > 0) drawHoldArc(graphics, x, y, progress, HOLD_RING_COLOR);
        } finally {
            RenderSystem.setShader(() -> shader);
        }
        graphics.drawCenteredString(mc.font, Component.translatable("mm.hold_progress", (int) (progress * 100)),
                x, y + HOLD_RING_RADIUS + mc.font.lineHeight / 2, ChatFormatting.WHITE.getColor());
    }

    private static void drawHoldArc(GuiGraphics graphics, int x, int y, float progress, int color) {
        // Match the radial menu's triangle strip, with at most one GUI pixel per arc segment.
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
            buffer.vertex(matrix, x + dx * (HOLD_RING_RADIUS - HOLD_RING_THICKNESS), y + dy * (HOLD_RING_RADIUS - HOLD_RING_THICKNESS), 0).color(color).endVertex();
        }
        BufferUploader.drawWithShader(buffer.end());
    }

    @SubscribeEvent
    public static void preview(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || !ConfigHolder.INSTANCE.manipulator.preview) return;
        ItemStack stack = tool();
        if (stack.isEmpty()) return;
        var s = ManipulatorSettings.read(stack);
        var mc = Minecraft.getInstance();
        if (s.a == null || s.b == null || !s.dimension.equals(mc.level.dimension().location().toString())) return;
        if (s.template() && s.anchor != null) {
            try {
                long arrays = Math.multiplyExact(Math.multiplyExact((long) s.repeat[0], s.repeat[1]), s.repeat[2]);
                ManipulatorGeometry.target(s.size().offset(-1, -1, -1), Math.toIntExact(arrays - 1), s);
            } catch (ArithmeticException invalidCoordinates) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.translatable("mm.error.bounds"), true);
                return;
            }
        }
        // Keep vertices camera-relative; apply this frame's view matrix once, when drawing.
        PoseStack pose = new PoseStack();
        var camera = event.getCamera().getPosition();
        pose.translate(-camera.x, -camera.y, -camera.z);
        PREVIEW_BUFFER.begin(RenderType.lines().mode(), RenderType.lines().format());
        VertexConsumer lines = PREVIEW_BUFFER;
        try {
            box(pose, lines, s.min(), s.size(), 0.2f, 0.55f, 1);
            int limit = ConfigHolder.INSTANCE.manipulator.previewLimit;
            long volume = s.volume();
            if (s.template() && s.anchor != null) {
                long arrays = (long) s.repeat[0] * s.repeat[1] * s.repeat[2];
                for (int array = 0; array < Math.min(arrays, limit); array++) box(pose, lines, ManipulatorGeometry.target(BlockPos.ZERO, array, s).offset(ManipulatorGeometry.transformedMin(s)), ManipulatorGeometry.extent(s), 1, 0.55f, 0.1f);
                if (volume * arrays <= limit) {
                    for (int array = 0; array < arrays; array++) for (int i = 0; i < volume; i++) {
                        BlockPos local = ManipulatorGeometry.local(i, s.size());
                        BlockPos pos = ManipulatorGeometry.target(local, array, s);
                        var source = mc.level.getBlockState(s.min().offset(local));
                        var state = mc.level.getBlockState(pos);
                        boolean blocked = !state.isAir() && state != source && s.removal != ManipulatorSettings.Removal.ALL && !(s.removal == ManipulatorSettings.Removal.REPLACEABLE && state.canBeReplaced());
                        box(pose, lines, pos, new BlockPos(1, 1, 1), 1, blocked ? 0.1f : source.isAir() ? 0.25f : 0.55f, 0.1f);
                    }
                }
            } else if (!s.template() && volume <= limit) {
                for (int i = 0; i < volume; i++) {
                    BlockPos local = ManipulatorGeometry.local(i, s.size());
                    if (ManipulatorGeometry.material(local, s) < 0) continue;
                    BlockPos pos = s.min().offset(local);
                    var state = mc.level.getBlockState(pos);
                    boolean blocked = !state.isAir() && s.removal != ManipulatorSettings.Removal.ALL && !(s.removal == ManipulatorSettings.Removal.REPLACEABLE && state.canBeReplaced());
                    box(pose, lines, pos, new BlockPos(1, 1, 1), 1, blocked ? 0.1f : 0.55f, 0.1f);
                }
            }
            drawPreview(event);
        } finally {
            // An interrupted preview must not leave geometry for a later frame.
            if (PREVIEW_BUFFER.building()) PREVIEW_BUFFER.end().release();
            PREVIEW_BUFFER.discard();
        }
    }

    private static void drawPreview(RenderLevelStageEvent event) {
        var type = RenderType.lines();
        var modelView = RenderSystem.getModelViewStack();
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var shader = RenderSystem.getShader();
        float lineWidth = RenderSystem.getShaderLineWidth();
        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        int depthFunc = glGetInteger(GL_DEPTH_FUNC);
        int readFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int drawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        modelView.pushPose();
        modelView.setIdentity();
        modelView.mulPoseMatrix(event.getPoseStack().last().pose());
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f(event.getProjectionMatrix()), sorting);
        type.setupRenderState();
        try {
            // RenderType setup enables depth testing, so apply the preview option afterwards.
            if (ConfigHolder.INSTANCE.manipulator.previewThroughBlocks) RenderSystem.disableDepthTest();
            // Bypass the shared world source: Oculus may defer its endBatch(RenderType) call.
            BufferUploader.drawWithShader(PREVIEW_BUFFER.end());
        } finally {
            type.clearRenderState();
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setShader(() -> shader);
            RenderSystem.lineWidth(lineWidth);
            RenderSystem.depthMask(depthMask);
            RenderSystem.depthFunc(depthFunc);
            if (depthTest) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        }
    }

    private static void box(PoseStack pose, VertexConsumer lines, BlockPos pos, BlockPos size, float r, float g, float b) {
        LevelRenderer.renderLineBox(pose, lines, new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + size.getX(), pos.getY() + size.getY(), pos.getZ() + size.getZ()), r, g, b, 1);
    }
}
