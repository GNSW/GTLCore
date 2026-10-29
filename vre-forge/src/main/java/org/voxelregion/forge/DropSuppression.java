/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * DROP_NONE for synchronous native block replacement, including container onRemove inventories.
 * ItemEntity admission and implicit unpacking of an unopened native container's loot are suppressed
 * only inside the actual replacement cell. Loot metadata is never cleared by this policy. Existing
 * entities, explicit player opens, unrelated locations, other levels/threads and callbacks before
 * or after this write are untouched.
 * Host protection/claim queries must run before invoking this method, outside the scope.
 */
public final class DropSuppression {
    private static final ThreadLocal<Frames> ACTIVE = new ThreadLocal<>();

    public static boolean setBlock(ServerLevel level, BlockPos position, BlockState replacement, int flags) {
        Objects.requireNonNull(level); Objects.requireNonNull(position); Objects.requireNonNull(replacement);
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Native deletion requires the server thread");
        Frames frames = ACTIVE.get();
        if (frames == null) { frames = new Frames(); ACTIVE.set(frames); }
        // Copy coordinates: callers commonly reuse a MutableBlockPos while draining ranges.
        // The common depth-one path reuses its frame without a per-block object allocation.
        int index = frames.enter(level, position);
        try {
            return level.setBlock(position, replacement, flags);
        } finally {
            frames.exit(index);
        }
    }

    /** Internal native admission hook. This does not remove entities already in the world. */
    public static boolean suppresses(ServerLevel level, Entity entity) {
        if (!(entity instanceof ItemEntity) || !level.getServer().isSameThread()) return false;
        Frames frames = ACTIVE.get();
        if (frames == null || frames.depth == 0) return false;
        double x = entity.getX(), y = entity.getY(), z = entity.getZ();
        for (int i = frames.depth - 1; i >= 0; i--) {
            Frame scope = frames.entries[i];
            if (scope.level == level && x >= scope.x && x < (double) scope.x + 1
                    && y >= scope.y && y < (double) scope.y + 1 && z >= scope.z && z < (double) scope.z + 1)
                return true;
        }
        return false;
    }

    /**
     * Internal native loot hook. The caller additionally requires a null player;
     * returning true skips generation without changing the table, seed or items.
     */
    public static boolean suppressesLootUnpacking(ServerLevel level, BlockPos position) {
        if (!level.getServer().isSameThread()) return false;
        Frames frames = ACTIVE.get();
        if (frames == null || frames.depth == 0) return false;
        for (int i = frames.depth - 1; i >= 0; i--) {
            Frame scope = frames.entries[i];
            if (scope.level == level && scope.x == position.getX()
                    && scope.y == position.getY() && scope.z == position.getZ()) return true;
        }
        return false;
    }

    private static final class Frames {
        private Frame[] entries = { new Frame() };
        private int depth;
        private int enter(ServerLevel level, BlockPos position) {
            int index = depth;
            if (index == entries.length) entries = java.util.Arrays.copyOf(entries, entries.length * 2);
            Frame frame = entries[index];
            if (frame == null) entries[index] = frame = new Frame();
            frame.level = level;
            frame.x = position.getX(); frame.y = position.getY(); frame.z = position.getZ();
            depth = index + 1;
            return index;
        }
        private void exit(int index) {
            // Do not retain an unloaded level/server through this reusable thread-local stack.
            entries[index].level = null;
            depth = index;
        }
    }
    private static final class Frame {
        private ServerLevel level;
        private int x, y, z;
    }
    private DropSuppression() {}
}
