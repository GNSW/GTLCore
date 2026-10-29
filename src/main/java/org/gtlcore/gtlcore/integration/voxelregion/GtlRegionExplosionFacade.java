package org.gtlcore.gtlcore.integration.voxelregion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

public final class GtlRegionExplosionFacade {

    private GtlRegionExplosionFacade() {}

    public static boolean submitSphere(ServerLevel level, UUID actor, BlockPos center, int radius) {
        return GtlRegionExplosions.submitSphereDefault(level, actor, center, radius);
    }
}
