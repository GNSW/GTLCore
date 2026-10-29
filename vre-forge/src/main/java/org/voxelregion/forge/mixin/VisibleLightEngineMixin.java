/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.voxelregion.forge.NativeAccess;

/** Read-only bridge; public getDataLayerData also returns queued, unpublished layers. */
@Mixin(LightEngine.class)
abstract class VisibleLightEngineMixin implements NativeAccess.VisibleLightData, NativeAccess.LightBoundary {
    @Shadow @Final protected LayerLightSectionStorage<?> storage;
    @Shadow @Final protected net.minecraft.world.level.chunk.LightChunkGetter chunkSource;

    @Override public net.minecraft.world.level.chunk.LightChunk vre$lightChunk(int x, int z) {
        return chunkSource.getChunkForLighting(x, z);
    }

    @Override public DataLayer vre$copyVisibleSectionData(long sectionPosition) {
        return ((NativeAccess.VisibleLightData) storage).vre$copyVisibleSectionData(sectionPosition);
    }
    @Override public boolean vre$storesUpdatingSection(long sectionPosition) {
        return ((NativeAccess.VisibleLightData) storage).vre$storesUpdatingSection(sectionPosition);
    }
}
