package org.gtlcore.gtlcore.client.renderer.item;

import org.gtlcore.gtlcore.common.data.GTLItems;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import com.mojang.blaze3d.vertex.PoseStack;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;
import top.theillusivec4.curios.api.client.ICurioRenderer;

/** Optional Curios renderer using the same item model and head transform as the helmet slot. */
public final class PlushieCurioRenderer implements ICurioRenderer {

    public static void register() {
        CuriosRendererRegistry.register(GTLItems.FISHBIG_TM.get(), PlushieCurioRenderer::new);
        CuriosRendererRegistry.register(GTLItems.PIG_PLUSHIE.get(), PlushieCurioRenderer::new);
        CuriosRendererRegistry.register(GTLItems.SLIM_PIG_PLUSHIE.get(), PlushieCurioRenderer::new);
    }

    @Override
    public <T extends LivingEntity, M extends EntityModel<T>> void render(ItemStack stack, SlotContext context,
                                                                          PoseStack poseStack, RenderLayerParent<T, M> parent,
                                                                          MultiBufferSource buffers, int light,
                                                                          float limbSwing, float limbSwingAmount,
                                                                          float partialTicks, float ageInTicks,
                                                                          float netHeadYaw, float headPitch) {
        if (!(parent.getModel() instanceof HeadedModel model)) return;
        LivingEntity wearer = context.entity();
        poseStack.pushPose();
        if (wearer.isBaby() && !(wearer instanceof Villager)) {
            poseStack.translate(0, 0.03125F, 0);
            poseStack.scale(0.7F, 0.7F, 0.7F);
            poseStack.translate(0, 1, 0);
        }
        model.getHead().translateAndRotate(poseStack);
        CustomHeadLayer.translateToHead(poseStack, wearer instanceof Villager || wearer instanceof ZombieVillager);
        Minecraft.getInstance().getEntityRenderDispatcher().getItemInHandRenderer()
                .renderItem(wearer, stack, ItemDisplayContext.HEAD, false, poseStack, buffers, light);
        poseStack.popPose();
    }
}
