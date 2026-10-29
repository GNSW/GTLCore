/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.voxelregion.forge.DropSuppression;

/** Avoids generating discarded container loot while preserving the native removal lifecycle. */
@Mixin(RandomizableContainerBlockEntity.class)
abstract class RandomizableLootSuppressionMixin {
    @Inject(method = "unpackLootTable", at = @At("HEAD"), cancellable = true)
    private void vre$skipImplicitDiscardedLoot(Player player, CallbackInfo callback) {
        var container = (RandomizableContainerBlockEntity) (Object) this;
        if (player == null && container.getLevel() instanceof ServerLevel level
                && DropSuppression.suppressesLootUnpacking(level, container.getBlockPos())) callback.cancel();
    }
}
