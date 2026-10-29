/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import java.util.BitSet;
import java.util.List;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.voxelregion.forge.CompleteChunkLightPacket;
import org.voxelregion.forge.runtime.RuntimeBridge;

/** Bulk terrain writes do not send the individual block changes used by vanilla client light propagation. */
@Mixin(ChunkHolder.class)
abstract class RegionLightBroadcastMixin {
    @Shadow @Final private BitSet skyChangedLightSectionFilter;
    @Shadow @Final private BitSet blockChangedLightSectionFilter;
    @Shadow private void broadcast(List<ServerPlayer> players, Packet<?> packet) { throw new AssertionError(); }

    @Redirect(method = "broadcastChanges", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkHolder$PlayerProvider;getPlayers(Lnet/minecraft/world/level/ChunkPos;Z)Ljava/util/List;"))
    private List<ServerPlayer> vre$includeInteriorPlayers(ChunkHolder.PlayerProvider provider, ChunkPos position,
            boolean boundaryOnly, LevelChunk chunk) {
        if (!boundaryOnly) return provider.getPlayers(position, false);
        boolean authoritative = chunk.getLevel() instanceof ServerLevel level
                && RuntimeBridge.needsAuthoritativeLight(level, position);
        return provider.getPlayers(position, boundaryOnly && !authoritative);
    }

    @Redirect(method = "broadcastChanges", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkHolder;broadcast(Ljava/util/List;Lnet/minecraft/network/protocol/Packet;)V"))
    private void vre$completeChangedLight(ChunkHolder holder, List<ServerPlayer> players, Packet<?> packet, LevelChunk chunk) {
        if (packet instanceof ClientboundLightUpdatePacket light && chunk.getLevel() instanceof ServerLevel level
                && RuntimeBridge.needsAuthoritativeLight(level, chunk.getPos())) {
            CompleteChunkLightPacket.fillMissing(light.getLightData(), level.getChunkSource().getLightEngine(),
                    chunk.getPos(), level.dimensionType().hasSkyLight(), skyChangedLightSectionFilter, blockChangedLightSectionFilter);
        }
        broadcast(players, packet);
    }
}
