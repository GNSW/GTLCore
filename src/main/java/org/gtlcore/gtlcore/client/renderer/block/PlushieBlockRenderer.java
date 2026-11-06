package org.gtlcore.gtlcore.client.renderer.block;

import org.gtlcore.gtlcore.common.block.PlushieAnimation;
import org.gtlcore.gtlcore.common.block.PlushieBlockEntity;
import org.gtlcore.gtlcore.common.data.GTLPlushies;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.eventbus.api.IEventBus;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

/** Renders the existing block model through a per-frame squash transform. */
public final class PlushieBlockRenderer implements BlockEntityRenderer<PlushieBlockEntity> {

    private static final RenderType PLUSHIE_LAYER = RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS);
    private final BlockRenderDispatcher blockRenderer;
    private final RandomSource random = RandomSource.create();

    private PlushieBlockRenderer(BlockEntityRendererProvider.Context context) {
        blockRenderer = context.getBlockRenderDispatcher();
    }

    public static void register(IEventBus bus) {
        bus.addListener(PlushieBlockRenderer::registerRenderers);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(GTLPlushies.BLOCK_ENTITY.get(), PlushieBlockRenderer::new);
    }

    @Override
    public void render(PlushieBlockEntity plushie, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        BlockState state = plushie.getBlockState();
        pose.pushPose();
        if (plushie.isPlaying() && plushie.getLevel() != null) {
            float squash = PlushieAnimation.musicSquash(
                    (plushie.getElapsedTicks(plushie.getLevel().getGameTime()) + partialTick) / 20.0);
            Direction facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ?
                    state.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
            pose.translate(0.5, 0, 0.5);
            pose.mulPose(Axis.YP.rotationDegrees(facing.toYRot()));
            pose.scale(PlushieAnimation.widthScale(squash), PlushieAnimation.heightScale(squash), 1);
            pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
            pose.translate(-0.5, 0, -0.5);
        }
        blockRenderer.getModelRenderer().tesselateBlock(plushie.getLevel(), blockRenderer.getBlockModel(state), state,
                plushie.getBlockPos(), pose, buffers.getBuffer(PLUSHIE_LAYER), false, random,
                state.getSeed(plushie.getBlockPos()), overlay, ModelData.EMPTY, PLUSHIE_LAYER);
        pose.popPose();
    }
}
