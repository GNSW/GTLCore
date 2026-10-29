package org.gtlcore.gtlcore.common.worldgen;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Publication gate for the legacy GT per-world, first-nonempty-biome ore cache. */
public final class WorldgenSharedState {

    private static final Map<Object, Boolean> initializedWorlds = new WeakHashMap<>();

    private WorldgenSharedState() {}

    public static boolean isInitialized(Object world) {
        synchronized (initializedWorlds) {
            return initializedWorlds.containsKey(world);
        }
    }

    public static <T> List<T> cachedBiomeVeins(Object world, Supplier<List<T>> original) {
        synchronized (initializedWorlds) {
            // Keep the original cache and its first nonempty biome, ordering and list identity.
            // In particular, do not initialize it with a scheduler-selected biome.
            List<T> result = original.get();
            if (!result.isEmpty()) initializedWorlds.put(world, Boolean.TRUE);
            return result;
        }
    }
}
