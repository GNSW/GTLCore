/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.ModList;

/** Optional linkage uses only FTB's published API, verified against 2001.3.1. */
final class ClaimChecks {
    enum Decision { ALLOW, DENY, UNAVAILABLE }
    private static boolean initialized;
    private static boolean present;
    private static Throwable failure;
    private static Method api, loaded, manager, getChunk, getTeam, explosions;
    private static Constructor<?> position;

    static Decision check(ServerLevel level, int x, int z, boolean protectEveryClaim) {
        initialize();
        if (!present) return Decision.ALLOW;
        if (failure != null) return Decision.UNAVAILABLE;
        try {
            Object service = api.invoke(null);
            if (!Boolean.TRUE.equals(loaded.invoke(service))) return Decision.UNAVAILABLE;
            Object claim = getChunk.invoke(manager.invoke(service), position.newInstance(level.dimension(), x, z));
            if (claim == null) return Decision.ALLOW;
            if (protectEveryClaim) return Decision.DENY;
            return Boolean.TRUE.equals(explosions.invoke(getTeam.invoke(claim))) ? Decision.ALLOW : Decision.DENY;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            if (failure == null) RuntimeBridge.LOGGER.error("FTB protection API unavailable; region operations fail closed", ex);
            failure = ex;
            return Decision.UNAVAILABLE;
        }
    }

    private static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        present = ModList.get().isLoaded("ftbchunks");
        if (!present) return;
        try {
            api = Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI").getMethod("api");
            Class<?> service = Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI$API");
            loaded = service.getMethod("isManagerLoaded");
            manager = service.getMethod("getManager");
            Class<?> pos = Class.forName("dev.ftb.mods.ftblibrary.math.ChunkDimPos");
            position = pos.getConstructor(ResourceKey.class, int.class, int.class);
            getChunk = Class.forName("dev.ftb.mods.ftbchunks.api.ClaimedChunkManager").getMethod("getChunk", pos);
            getTeam = Class.forName("dev.ftb.mods.ftbchunks.api.ClaimedChunk").getMethod("getTeamData");
            explosions = Class.forName("dev.ftb.mods.ftbchunks.api.ChunkTeamData").getMethod("canExplosionsDamageTerrain");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            failure = ex;
            RuntimeBridge.LOGGER.error("Unsupported FTB public API; region operations fail closed", ex);
        }
    }
    private ClaimChecks() {}
}
