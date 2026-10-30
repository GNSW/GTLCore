package org.gtlcore.gtlcore.mixin.gtm.worldgen;

import org.gtlcore.gtlcore.common.worldgen.ReusableVeinedContext;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps native final captures and the native Blender intact for every non-reused context. */
@Mixin(targets = "com.gregtechceu.gtceu.api.data.worldgen.generator.veins.VeinedVeinGenerator$1", remap = false)
public abstract class VeinedFunctionContextMixin implements ReusableVeinedContext {

    @Unique
    private boolean gtlcore$coordinatesReused;
    @Unique
    private int gtlcore$sampleX;
    @Unique
    private int gtlcore$sampleY;
    @Unique
    private int gtlcore$sampleZ;

    @Override
    public void gtlcore$reuseCoordinates(int x, int y, int z) {
        gtlcore$sampleX = x;
        gtlcore$sampleY = y;
        gtlcore$sampleZ = z;
        gtlcore$coordinatesReused = true;
    }

    // These three methods implement Minecraft's FunctionContext interface, unlike the
    // enclosing GT class. Resolve their production names through that interface mapping.
    @ModifyReturnValue(method = "blockX()I", at = @At("RETURN"), remap = true, require = 1)
    private int gtlcore$currentX(int original) {
        return gtlcore$coordinatesReused ? gtlcore$sampleX : original;
    }

    @ModifyReturnValue(method = "blockY()I", at = @At("RETURN"), remap = true, require = 1)
    private int gtlcore$currentY(int original) {
        return gtlcore$coordinatesReused ? gtlcore$sampleY : original;
    }

    @ModifyReturnValue(method = "blockZ()I", at = @At("RETURN"), remap = true, require = 1)
    private int gtlcore$currentZ(int original) {
        return gtlcore$coordinatesReused ? gtlcore$sampleZ : original;
    }
}
