/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.voxelregion.forge.NativeAccess;

/** Native visibleSectionData is published with copy-on-write layers; copy before retaining it. */
@Mixin(LayerLightSectionStorage.class)
abstract class VisibleLightStorageMixin implements NativeAccess.VisibleLightData {
    @Shadow protected abstract DataLayer getDataLayer(long sectionPosition, boolean updating);

    @Override public boolean vre$storesUpdatingSection(long sectionPosition) {
        return getDataLayer(sectionPosition, true) != null;
    }

    @Override public DataLayer vre$copyVisibleSectionData(long sectionPosition) {
        DataLayer visible = getDataLayer(sectionPosition, false);
        return visible == null ? null : visible.copy();
    }
}
