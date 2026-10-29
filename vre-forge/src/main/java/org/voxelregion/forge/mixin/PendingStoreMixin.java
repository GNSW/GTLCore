/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.voxelregion.forge.NativeAccess;

@Mixin(targets = "net.minecraft.world.level.chunk.storage.IOWorker$PendingStore")
abstract class PendingStoreMixin implements NativeAccess.PendingStore {
    @Override @Accessor("data") public abstract CompoundTag vre$data();
    @Override @Accessor("result") public abstract CompletableFuture<Void> vre$result();
}
