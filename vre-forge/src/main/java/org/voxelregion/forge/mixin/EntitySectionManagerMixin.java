/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.voxelregion.forge.EntityStorageBridge;

/** Native entity-only leases never promote terrain or override a player's chunk visibility. */
@Mixin(PersistentEntitySectionManager.class)
abstract class EntitySectionManagerMixin<T extends EntityAccess> implements EntityStorageBridge.Manager {
    @Shadow @Final private Long2ObjectMap<Visibility> chunkVisibility;
    @Shadow @Final private LongSet chunksToUnload;
    @Shadow @Final private EntitySectionStorage<T> sectionStorage;
    @Shadow private void ensureChunkQueuedForLoad(long position) { throw new AssertionError(); }
    @Shadow private boolean storeChunkSections(long position, Consumer<T> consumer) { throw new AssertionError(); }
    @Shadow public abstract boolean areEntitiesLoaded(long position);
    @Unique private final Map<Long, Integer> vre$entityLeases = new HashMap<>();

    @Override public void vre$acquireEntityChunk(long position, boolean loadSaved) {
        vre$entityLeases.merge(position, 1, Integer::sum);
        try {
            if (loadSaved) ensureChunkQueuedForLoad(position);
        } catch (RuntimeException | Error failure) {
            vre$releaseEntityChunk(position);
            throw failure;
        }
    }
    @Override public boolean vre$entitiesLoaded(long position) { return areEntitiesLoaded(position); }
    @Override public List<Entity> vre$entitySnapshot(long position, int maximum) {
        if (!vre$entityLeases.containsKey(position)) throw new IllegalStateException("Entity chunk is not leased");
        var entities = sectionStorage.getExistingSectionsInChunk(position)
                .flatMap(section -> section.getEntities()).limit((long) maximum + 1).map(entity -> (Entity) entity).toList();
        if (entities.size() > maximum) throw new IllegalStateException("Entity chunk exceeds snapshot quota " + maximum);
        return entities;
    }
    @Override public void vre$releaseEntityChunk(long position) {
        Integer count = vre$entityLeases.get(position);
        if (count == null) return;
        if (count > 1) { vre$entityLeases.put(position, count - 1); return; }
        vre$entityLeases.remove(position);
        // A read initiated while hidden has no terrain unload transition to queue this for us.
        // Reuse native saving/unloading after the lease instead of orphaning loaded entities.
        if (chunkVisibility.get(position) == Visibility.HIDDEN) chunksToUnload.add(position);
        else if (areEntitiesLoaded(position)) storeChunkSections(position, entity -> { });
    }
    @Inject(method = "processChunkUnload", at = @At("HEAD"), cancellable = true)
    private void vre$retainEntityChunk(long position, CallbackInfoReturnable<Boolean> callback) {
        if (vre$entityLeases.containsKey(position)) callback.setReturnValue(false);
    }
}
