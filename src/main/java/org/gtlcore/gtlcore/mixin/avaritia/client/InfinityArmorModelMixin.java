package org.gtlcore.gtlcore.mixin.avaritia.client;

import committee.nova.mods.avaritia.client.model.InfinityArmorModel;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(InfinityArmorModel.class)
public abstract class InfinityArmorModelMixin {

    @Group(name = "suppressWingMaterialLog", min = 1, max = 1)
    @Redirect(
              method = "renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
              at = @At(
                       value = "INVOKE",
                       target = "Lorg/apache/logging/log4j/Logger;info(Ljava/lang/Object;)V",
                       remap = false),
              require = 0)
    private void gtlcore$suppressWingMaterialLog(Logger logger, Object message) {}

    @Group(name = "suppressWingMaterialLog", min = 1, max = 1)
    @Redirect(
              method = "m_7695_(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
              at = @At(
                       value = "INVOKE",
                       target = "Lorg/apache/logging/log4j/Logger;info(Ljava/lang/Object;)V",
                       remap = false),
              require = 0,
              remap = false)
    @SuppressWarnings("target") // This grouped fallback targets the production SRG name only.
    private void gtlcore$suppressObfuscatedWingMaterialLog(Logger logger, Object message) {}
}
