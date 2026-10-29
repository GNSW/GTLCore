/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.voxelregion.forge.NativeAccess;

@Mixin(ChunkSkyLightSources.class)
public interface ColumnSkySourcesMixin extends NativeAccess.ColumnSkySources {
    @Override @Invoker("set") void vre$setSource(int index, int y);
}
