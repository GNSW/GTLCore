package org.gtlcore.gtlcore.client.spray;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.item.InfiniteSprayCanBehavior;
import org.gtlcore.gtlcore.common.item.InfiniteSprayCanItem;
import org.gtlcore.gtlcore.network.InfiniteSprayCanPackets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class InfiniteSprayCanClient {

    private static BlockPos rangeStart;
    private static String rangeDimension = "";
    private static boolean rangeMode;

    private InfiniteSprayCanClient() {}

    private static ItemStack sprayCan() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return ItemStack.EMPTY;
        ItemStack mainHand = minecraft.player.getMainHandItem();
        if (mainHand.getItem() instanceof InfiniteSprayCanItem) return mainHand;
        ItemStack offHand = minecraft.player.getOffhandItem();
        return offHand.getItem() instanceof InfiniteSprayCanItem ? offHand : ItemStack.EMPTY;
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getAction() != GLFW.GLFW_PRESS || event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT ||
                minecraft.screen != null || minecraft.player == null || !minecraft.player.isShiftKeyDown() ||
                sprayCan().isEmpty()) {
            return;
        }
        event.setCanceled(true);
        InfiniteSprayCanPackets.toggleMode();
    }

    public static void receiveSelection(InfiniteSprayCanPackets.Selection selection) {
        rangeMode = selection.rangeMode();
        rangeStart = selection.start();
        rangeDimension = selection.dimension();
    }

    @SubscribeEvent
    public static void renderSelection(RenderLevelStageEvent event) {
        ItemStack stack = sprayCan();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || stack.isEmpty()) {
            return;
        }
        BlockPos selectedStart = InfiniteSprayCanBehavior.getRangeStart(stack);
        String selectedDimension = InfiniteSprayCanBehavior.getRangeDimension(stack);
        if (selectedStart == null && rangeMode) {
            selectedStart = rangeStart;
            selectedDimension = rangeDimension;
        }
        if (!InfiniteSprayCanBehavior.isRangeMode(stack) || selectedStart == null) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null ||
                !selectedDimension.equals(minecraft.level.dimension().location().toString())) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        var buffers = minecraft.renderBuffers().bufferSource();
        RenderType type = RenderType.lines();
        VertexConsumer lines = buffers.getBuffer(type);
        Vec3 camera = event.getCamera().getPosition();
        poseStack.pushPose();
        boolean lineWidthSet = false;
        try {
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            try {
                RenderSystem.lineWidth(2.0F);
                lineWidthSet = true;
            } catch (Throwable ignored) {
                // Some drivers only support one-pixel lines.
            }
            LevelRenderer.renderLineBox(poseStack, lines,
                    new AABB(selectedStart).inflate(0.005), 0.15F, 0.85F, 1.0F, 1.0F);
        } finally {
            poseStack.popPose();
            buffers.endBatch(type);
            if (lineWidthSet) {
                try {
                    RenderSystem.lineWidth(1.0F);
                } catch (Throwable ignored) {
                    // Ignore driver limitations.
                }
            }
        }
    }
}
