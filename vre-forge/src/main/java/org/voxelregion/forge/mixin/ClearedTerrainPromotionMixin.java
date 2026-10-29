/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.voxelregion.forge.NativeAccess;

@Mixin(LevelChunk.class)
abstract class ClearedTerrainPromotionMixin {
    @Inject(method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V", at = @At("RETURN"))
    private void vre$copy(ServerLevel level, ProtoChunk proto, LevelChunk.PostLoadProcessor processor, CallbackInfo callback) {
        ((NativeAccess.ClearedTerrain) this).vre$clearedTerrain(((NativeAccess.ClearedTerrain) proto).vre$clearedTerrain());
    }
}
