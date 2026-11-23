package org.gtlcore.gtlcore.client.render;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.item.CoordinatePositioningCardItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL30C.*;

/**
 * 手持坐标定位卡时的框选区域高亮：起点与终点都设置后，在世界上画出该区域的边框。
 * <p>
 * 颜色用琥珀色，和结构放置器的青色放置边界区分开。纯客户端功能，坐标直接读卡上的 NBT。
 * </p>
 */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public final class CardRegionPreview {

    private static final float RED = 1.0F;
    private static final float GREEN = 0.62F;
    private static final float BLUE = 0.12F;
    private static final BufferBuilder BUFFER = new BufferBuilder(RenderType.lines().bufferSize());

    @Nullable
    private static AABB box;
    @Nullable
    private static ResourceKey<Level> dimension;

    private CardRegionPreview() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            box = null;
            return;
        }
        ItemStack stack = heldCard(player);
        if (stack == null || !CoordinatePositioningCardItem.hasRegion(stack)) {
            box = null;
            return;
        }
        BlockPos a = CoordinatePositioningCardItem.getStart(stack);
        BlockPos b = CoordinatePositioningCardItem.getEnd(stack);
        if (a == null || b == null) {
            box = null;
            return;
        }
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX());
        int maxY = Math.max(a.getY(), b.getY());
        int maxZ = Math.max(a.getZ(), b.getZ());
        box = new AABB(minX, minY, minZ, maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D);
        dimension = minecraft.level.dimension();
    }

    @Nullable
    private static ItemStack heldCard(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof CoordinatePositioningCardItem) return main;
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof CoordinatePositioningCardItem) return off;
        return null;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (box == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().equals(dimension)) return;

        PoseStack pose = new PoseStack();
        var camera = event.getCamera().getPosition();
        pose.translate(-camera.x, -camera.y, -camera.z);
        BUFFER.begin(RenderType.lines().mode(), RenderType.lines().format());
        VertexConsumer lines = BUFFER;
        try {
            LevelRenderer.renderLineBox(pose, lines, box, RED, GREEN, BLUE, 1.0F);
            draw(event);
        } finally {
            if (BUFFER.building()) BUFFER.end().release();
            BUFFER.discard();
        }
    }

    /** 与放置边界相同的绘制流程：自备相机矩阵、关闭深度测试，边框可穿透地形。 */
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
