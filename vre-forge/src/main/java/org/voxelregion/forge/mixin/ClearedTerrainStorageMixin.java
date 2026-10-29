/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.voxelregion.forge.ClearedGeneration;
import org.voxelregion.forge.NativeAccess;

@Mixin(ChunkSerializer.class)
abstract class ClearedTerrainStorageMixin {
    @Inject(method = "write", at = @At("RETURN"))
    private static void vre$write(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<CompoundTag> callback) {
        var marker = ((NativeAccess.ClearedTerrain) ClearedGeneration.unwrap(chunk)).vre$clearedTerrain();
        if (marker != null) callback.getReturnValue().put(ClearedGeneration.MARKER, marker);
    }
    @Inject(method = "read", at = @At("RETURN"))
    private static void vre$read(ServerLevel level, PoiManager poi, ChunkPos position, CompoundTag image,
            CallbackInfoReturnable<ProtoChunk> callback) {
        if (image.contains(ClearedGeneration.MARKER, 10))
            ((NativeAccess.ClearedTerrain) ClearedGeneration.unwrap(callback.getReturnValue()))
                    .vre$clearedTerrain(image.getCompound(ClearedGeneration.MARKER));
    }
}
