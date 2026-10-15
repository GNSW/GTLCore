package org.gtlcore.gtlcore.mixin.ldlib.gui.util;

import com.lowdragmc.lowdraglib.gui.util.TextFormattingUtil;
import com.lowdragmc.lowdraglib.side.fluid.FluidHelper;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.text.DecimalFormat;
import java.util.Map;
import java.util.NavigableMap;

@Mixin(TextFormattingUtil.class)
public abstract class TextFormattingUtilMixin {

    @Shadow(remap = false)
    private static NavigableMap<Long, String> suffixesBucket;

    @Inject(method = "formatLongToCompactStringBuckets", at = @At("HEAD"), cancellable = true, remap = false)
    private static void gtlcore$safeScaling(long value, int precision, CallbackInfoReturnable<String> cir) {
        if (value <= Long.MAX_VALUE / 1000) return;
        double scaledDouble = (double) value * 1000d / (double) FluidHelper.getBucket();
        long scaled = (long) scaledDouble;
        if (scaled == 0) {
            cir.setReturnValue(String.format("%sm", new DecimalFormat("0.####").format(scaledDouble)));
        } else if (scaled < Math.pow(10, precision)) {
            cir.setReturnValue(scaled + suffixesBucket.floorEntry(scaled).getValue());
        } else {
            Map.Entry<Long, String> e = suffixesBucket.floorEntry(scaled);
            long truncated = scaled / (e.getKey() / 10);
            boolean hasDecimal = truncated < 100 && (truncated / 10d) != (truncated / 10);
            cir.setReturnValue(hasDecimal ? (truncated / 10d) + e.getValue() : (truncated / 10) + e.getValue());
        }
    }
}
