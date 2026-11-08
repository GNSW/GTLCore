package org.gtlcore.gtlcore.mixin.mc;

import org.gtlcore.gtlcore.client.renderer.item.PlushieHeadRender;
import org.gtlcore.gtlcore.common.plushie.PlushieItems;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bounces a plushie equipped in the vanilla helmet slot, mirroring the Curios head renderer. */
@Mixin(CustomHeadLayer.class)
public abstract class CustomHeadLayerMixin {

    @Inject(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/entity/layers/CustomHeadLayer;translateToHead(Lcom/mojang/blaze3d/vertex/PoseStack;Z)V",
                     shift = At.Shift.AFTER))
    private void gtlcore$bounceWornPlushie(PoseStack pose, MultiBufferSource buffer, int light, LivingEntity entity,
                                           float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                                           float netHeadYaw, float headPitch, CallbackInfo callback) {
        ItemStack stack = entity.getItemBySlot(EquipmentSlot.HEAD);
        if (!PlushieItems.isPlushie(stack)) return;
        PlushieHeadRender.applyPlacement(pose, stack);
        PlushieHeadRender.applyBounce(pose, entity, partialTick);
    }
}
