package org.gtlcore.gtlcore.mixin.ae2.gui;

import org.gtlcore.gtlcore.client.gui.AmountExpressionNormalizer;

import appeng.client.gui.MathExpressionParser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(MathExpressionParser.class)
public abstract class MathExpressionParserMixin {

    @Unique
    @ModifyVariable(method = "parse", at = @At("HEAD"), argsOnly = true, index = 0, remap = false)
    private static String gtlcore$normalizeAmountExpression(String expression) {
        return AmountExpressionNormalizer.normalize(expression);
    }
}
