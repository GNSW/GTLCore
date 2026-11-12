package org.gtlcore.gtlcore.client.renderer.machine;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.api.machine.IGTLRenderDisabled;
import org.gtlcore.gtlcore.client.ClientUtil;
import org.gtlcore.gtlcore.client.renderer.RenderBufferHelper;
import org.gtlcore.gtlcore.common.machine.multiblock.electric.SpaceElevatorMachine;
import org.gtlcore.gtlcore.utils.RenderUtil;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.client.renderer.GTRenderTypes;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.model.data.ModelData;

import com.mojang.blaze3d.vertex.PoseStack;

import java.util.function.Consumer;

public class SpaceElevatorRenderer extends WorkableCasingMachineRenderer {

    private static final ResourceLocation CLIMBER_MODEL = GTLCore.id("obj/climber");

    public SpaceElevatorRenderer() {
        super(GTLCore.id("block/space_elevator_mechanical_casing"), GTCEu.id("block/multiblock/data_bank"));
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void render(BlockEntity blockEntity, float partialTicks, PoseStack poseStack, MultiBufferSource buffer,
                       int combinedLight, int combinedOverlay) {
        if (blockEntity instanceof IMachineBlockEntity machineBlockEntity &&
                machineBlockEntity.getMetaMachine() instanceof SpaceElevatorMachine machine && machine.isFormed() &&
                !IGTLRenderDisabled.isRenderDisabled(machine)) {
            float tick = RenderUtil.getSmoothTick(machine, partialTicks);
            double x = 0.5, y = 1, z = 0.5;
            switch (machine.getFrontFacing()) {
                case NORTH -> z = 3.5;
                case SOUTH -> z = -2.5;
                case WEST -> x = 3.5;
                case EAST -> x = -2.5;
            }
            poseStack.pushPose();
            RenderBufferHelper.renderCylinder(poseStack, buffer.getBuffer(GTRenderTypes.getLightRing()), (float) x, (float) (y - 2), (float) z, 0.3F, 360, 10, 0, 0, 0, 255);
            poseStack.translate(x, y + 180 + (140 * Math.sin(tick / 160)), z);
            renderClimber(poseStack, buffer);
            poseStack.popPose();
        }
    }

    private void renderClimber(PoseStack poseStack, MultiBufferSource buffer) {
        float scale = 4F;
        poseStack.pushPose();
        poseStack.scale(scale, scale, scale);
        ClientUtil.modelRenderer().renderModel(poseStack.last(), buffer.getBuffer(RenderType.solid()), null, ClientUtil.getBakedModel(CLIMBER_MODEL), 1.0F, 1.0F, 1.0F, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.solid());
        poseStack.popPose();
    }

    @Override
    public void onAdditionalModel(Consumer<ResourceLocation> registry) {
        super.onAdditionalModel(registry);
        registry.accept(CLIMBER_MODEL);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public boolean hasTESR(BlockEntity blockEntity) {
        return true;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public boolean isGlobalRenderer(BlockEntity blockEntity) {
        return true;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public int getViewDistance() {
        return 512;
    }
}
