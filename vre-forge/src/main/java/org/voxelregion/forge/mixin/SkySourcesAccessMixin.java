/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import net.minecraft.world.level.lighting.SkyLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.voxelregion.forge.NativeAccess;

@Mixin(SkyLightEngine.class)
public interface SkySourcesAccessMixin extends NativeAccess.SkySources {
    @Override @Invoker("getLowestSourceY") int vre$lowestSourceY(int worldX, int worldZ, int missingValue);
}
