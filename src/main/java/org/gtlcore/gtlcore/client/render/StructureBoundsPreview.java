package org.gtlcore.gtlcore.client.render;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.block.StructurePlacerBlockEntity;
import org.gtlcore.gtlcore.common.structure.transform.StructureTransform;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL30C.*;

/**
 * 放置边界高亮：潜行右键放置器后，在世界上画出即将放置结构的包围盒，持续 {@link #DURATION_TICKS} tick。
 * <p>
 * 纯客户端功能，边界由客户端方块实体里已同步的预设/偏移/旋转/镜像算出，不需要额外网络包。
 * </p>
 */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class StructureBoundsPreview {

    private static final int DURATION_TICKS = 200;
    private static final float RED = 0.25F;
    private static final float GREEN = 0.85F;
    private static final float BLUE = 1.0F;
    private static final BufferBuilder BUFFER = new BufferBuilder(RenderType.lines().bufferSize());

    private static ResourceKey<Level> dimension;
    private static AABB box;
    private static long expireAt;

    private StructureBoundsPreview() {}

    /** 潜行右键放置器时调用（客户端）。 */
    public static void show(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof StructurePlacerBlockEntity placer)) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (placer.currentStructure() == null) {
            display(minecraft, Component.translatable(
                    "ui.quantum_chromodynamic_charge.structure.preview.empty")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        StructureTransform.BlockPosBounds bounds = placer.placementBounds();
        BlockPos min = bounds.min();
        BlockPos max = bounds.max();
        dimension = level.dimension();
        box = new AABB(
                min.getX(), min.getY(), min.getZ(),
                max.getX() + 1.0D, max.getY() + 1.0D, max.getZ() + 1.0D);
        expireAt = level.getGameTime() + DURATION_TICKS;
        display(minecraft, Component.translatable(
                "ui.quantum_chromodynamic_charge.structure.bounds_shown",
                max.getX() - min.getX() + 1,
                max.getY() - min.getY() + 1,
                max.getZ() - min.getZ() + 1).withStyle(ChatFormatting.AQUA));
    }

    private static void display(Minecraft minecraft, Component message) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(message, true);
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (box == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().equals(dimension)) return;
        if (minecraft.level.getGameTime() > expireAt) {
            box = null;
            return;
        }

        PoseStack pose = new PoseStack();
        var camera = event.getCamera().getPosition();
        pose.translate(-camera.x, -camera.y, -camera.z);
        BUFFER.begin(RenderType.lines().mode(), RenderType.lines().format());
        VertexConsumer lines = BUFFER;
        try {
            LevelRenderer.renderLineBox(pose, lines, box, RED, GREEN, BLUE, 1.0F);
            draw(event);
        } finally {
            // 中断的帧不能把几何留给下一帧
            if (BUFFER.building()) BUFFER.end().release();
            BUFFER.discard();
        }
    }

    /** 与 {@code ManipulatorClient#drawPreview} 相同的绘制流程：自备相机矩阵、关闭深度测试以便穿透方块可见。 */
    private static void draw(RenderLevelStageEvent event) {
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
            // RenderType 会开启深度测试，边框要能透过地形看到
            RenderSystem.disableDepthTest();
            BufferUploader.drawWithShader(BUFFER.end());
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
}
