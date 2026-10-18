package org.gtlcore.gtlcore.mixin.gtm.cover;

import com.gregtechceu.gtceu.utils.GTUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GTUtil.class, remap = false)
public class GTUtilSolarMixin {

    @Inject(method = "canSeeSunClearly", at = @At("HEAD"), cancellable = true)
    private static void gtlcore$forceSunInSkyblockDimensions(Level level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        String dimension = level.dimension().location().toString();
        if ("kubejs:void".equals(dimension) || "kubejs:flat".equals(dimension)) {
            cir.setReturnValue(level.canSeeSky(pos.above()));
        }
    }
}
