/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.voxelregion.forge.EntityStorageBridge;

@Mixin(ServerLevel.class)
abstract class ServerEntityManagerMixin implements EntityStorageBridge {
    @Shadow @Final private PersistentEntitySectionManager<Entity> entityManager;
    @Override public Manager vre$entityManager() { return (Manager) entityManager; }
}
