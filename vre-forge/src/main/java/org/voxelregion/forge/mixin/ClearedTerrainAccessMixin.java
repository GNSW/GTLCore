/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.voxelregion.forge.NativeAccess;

@Mixin(ChunkAccess.class)
abstract class ClearedTerrainAccessMixin implements NativeAccess.ClearedTerrain {
    @Unique private volatile CompoundTag vre$clearedTerrain;
    @Override public CompoundTag vre$clearedTerrain() { return vre$clearedTerrain == null ? null : vre$clearedTerrain.copy(); }
    @Override public void vre$clearedTerrain(CompoundTag value) {
        if (value != null && (value.getInt("schema") != 1 || !value.hasUUID("operation")
                || !value.getString("intent").matches("[0-9a-fA-F]{64}")))
            throw new IllegalArgumentException("Invalid cleared-terrain provenance");
        vre$clearedTerrain = value == null ? null : value.copy();
    }
    @Override public boolean vre$isClearedTerrain() { return vre$clearedTerrain != null; }
}
