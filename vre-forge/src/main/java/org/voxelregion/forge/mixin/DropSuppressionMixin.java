/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.voxelregion.forge.DropSuppression;

/** Shared native admission path includes addFreshEntity and addWithUUID, before join-world hooks. */
@Mixin(ServerLevel.class)
abstract class DropSuppressionMixin {
    @Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
    private void vre$suppressSynchronousDeletionDrops(Entity entity, CallbackInfoReturnable<Boolean> result) {
        if (DropSuppression.suppresses((ServerLevel) (Object) this, entity)) result.setReturnValue(false);
    }
}
