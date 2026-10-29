/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import java.util.function.IntSupplier;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.voxelregion.forge.NativeAccess;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessMixin extends NativeAccess.ChunkSave {
    @Override @Invoker("save") boolean vre$save(ChunkAccess chunk);
    @Override @Invoker("getChunkQueueLevel") IntSupplier vre$queueLevel(long position);
}
