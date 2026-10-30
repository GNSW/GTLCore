// SPDX-License-Identifier: LGPL-3.0-only
package ca.spottedleaf.starlight.common.light;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.voxelregion.forge.NativeAccess;

/** VRE consumes the same visible snapshots used by native packets and renderers. */
public abstract class ProviderLightReader implements LayerLightEventListener,
        NativeAccess.VisibleLightData, NativeAccess.LightBoundary {
    private final StarLightInterface provider;
    protected ProviderLightReader(StarLightInterface provider) { this.provider = provider; }
    @Override public final DataLayer vre$copyVisibleSectionData(long sectionPosition) {
        // The Starlight reader already exports a caller-owned DataLayer copy.
        return this.getDataLayerData(SectionPos.of(sectionPosition));
    }
    @Override public final LightChunk vre$lightChunk(int x, int z) { return this.provider.getAnyChunkNow(x, z); }
}
