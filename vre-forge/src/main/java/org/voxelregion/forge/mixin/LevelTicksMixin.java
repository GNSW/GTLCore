/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.mixin;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.voxelregion.forge.NativeAccess;

/** Native clearArea bookkeeping with an exact changed-position predicate instead of a bounding box. */
@Mixin(LevelTicks.class)
abstract class LevelTicksMixin<T> implements NativeAccess.TickFilter {
    @Shadow @Final private Long2ObjectMap<LevelChunkTicks<T>> allContainers;
    @Shadow @Final private Long2LongMap nextTickForContainer;
    @Shadow @Final private Queue<ScheduledTick<T>> toRunThisTick;
    @Shadow @Final private List<ScheduledTick<T>> alreadyRunThisTick;
    @Shadow @Final private Set<ScheduledTick<?>> toRunThisTickSet;

    @Override public void vre$clearChanged(ChunkPos chunk, Predicate<BlockPos> changed) {
        long key = chunk.toLong();
        Predicate<ScheduledTick<T>> remove = tick -> ChunkPos.asLong(tick.pos()) == key && changed.test(tick.pos());
        LevelChunkTicks<T> container = allContainers.get(key);
        if (container != null) {
            ScheduledTick<T> before = container.peek();
            container.removeIf(remove);
            ScheduledTick<T> after = container.peek();
            if (before != after) {
                if (after == null) nextTickForContainer.remove(key);
                else nextTickForContainer.put(key, after.triggerTick());
            }
        }
        alreadyRunThisTick.removeIf(remove);
        toRunThisTick.removeIf(remove);
        toRunThisTickSet.removeIf(tick -> ChunkPos.asLong(tick.pos()) == key && changed.test(tick.pos()));
    }
}
