/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Uniform native sections need one palette entry, not a 4096-element unpack/remap/repack. */
@Mixin(PalettedContainer.class)
abstract class UniformPalettePackMixin<T> {
    @Shadow public abstract void acquire();
    @Shadow public abstract void release();
    @Shadow public abstract T get(int x, int y, int z);
    @Shadow public abstract boolean maybeHas(Predicate<T> predicate);

    @Inject(method = "pack", at = @At("HEAD"), cancellable = true)
    private void vre$packUniform(IdMap<T> registry, PalettedContainer.Strategy strategy,
            CallbackInfoReturnable<PalettedContainerRO.PackedData<T>> callback) {
        if (strategy != PalettedContainer.Strategy.SECTION_STATES && strategy != PalettedContainer.Strategy.SECTION_BIOMES) return;
        acquire();
        try {
            T first = get(0, 0, 0);
            if (!maybeHas(value -> value != first))
                callback.setReturnValue(new PalettedContainerRO.PackedData<>(List.of(first), Optional.empty()));
        } finally { release(); }
    }
}
