package org.gtlcore.gtlcore.client.renderer.item;

import org.gtlcore.gtlcore.client.plushie.PlushieHeadClient;
import org.gtlcore.gtlcore.common.block.PlushieAnimation;
import org.gtlcore.gtlcore.common.plushie.PlushieItems;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import com.mojang.blaze3d.vertex.PoseStack;

/** Applies the shared squash-and-rebound curve to a plushie worn in a head slot. */
public final class PlushieHeadRender {

    /** Matches the "head" display translation of the plushie item models. */
    private static final float HEAD_ITEM_Y = 0.75F;
    private static final float EXTENDED_FISHBIG_SCALE = 0.7F;

    private PlushieHeadRender() {}

    /**
     * ExtendedAE's fish model has no head display transform, so give it the same placement that the
     * GTLCore plushie models declare in their JSON.
     */
    public static void applyPlacement(PoseStack pose, ItemStack stack) {
        if (!PlushieItems.isExtendedFishbig(stack)) return;
        pose.translate(0.0F, HEAD_ITEM_Y, -1.0F / 16.0F);
        pose.scale(EXTENDED_FISHBIG_SCALE, EXTENDED_FISHBIG_SCALE, EXTENDED_FISHBIG_SCALE);
    }

    public static void applyBounce(PoseStack pose, LivingEntity wearer, float partialTick) {
        float squash = PlushieHeadClient.squash(wearer.getId(), partialTick);
        if (squash <= 0) return;
        pose.translate(0.0F, HEAD_ITEM_Y, 0.0F);
        pose.scale(PlushieAnimation.widthScale(squash), PlushieAnimation.heightScale(squash), 1.0F);
        pose.translate(0.0F, -HEAD_ITEM_Y, 0.0F);
    }
}
