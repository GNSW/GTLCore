/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.voxelregion.forge.GenerationGate;

/** Changes only a proven native terrain-fill stage; all ChunkStatus ownership stays native. */
@Mixin(FlatLevelSource.class)
public abstract class FlatGenerationMixin {
    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void vre$retainedFlatLayers(Executor executor, Blender blender, RandomState random,
            StructureManager structures, ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
        FlatLevelSource generator = (FlatLevelSource) (Object) this;
        CompletableFuture<ChunkAccess> replacement = GenerationGate.tryFill(generator, chunk,
                () -> generator.fillFromNoise(executor, blender, random, structures, chunk));
        if (replacement != null) callback.setReturnValue(replacement);
    }
}
