// SPDX-License-Identifier: LGPL-3.0-only
package ca.spottedleaf.starlight.common.light;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

/** Retains already loaded bulk participants; never synchronously loads missing neighbours. */
public final class RegionLightLease implements AutoCloseable {
    private static final TicketType<UUID> TYPE = TicketType.create("vre_global_bulk_light", UUID::compareTo);
    private final ServerLevel level;
    private final UUID token = UUID.randomUUID();
    private final List<ChunkPos> held = new ArrayList<>();
    private boolean closed;

    private RegionLightLease(ServerLevel level) { this.level = level; }

    public static RegionLightLease acquire(ServerLevel level, StarLightInterface provider, ChunkPos center, int radius) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Light tickets require owner thread");
        RegionLightLease lease = new RegionLightLease(level);
        try {
            for (int z = -radius; z <= radius; z++) for (int x = -radius; x <= radius; x++) {
                ChunkAccess chunk = provider.getAnyChunkNow(center.x + x, center.z + z);
                if (chunk == null || chunk.getStatus() != ChunkStatus.FULL) continue;
                ChunkPos pos = chunk.getPos();
                level.getChunkSource().addRegionTicket(TYPE, pos, 0, lease.token);
                lease.held.add(pos);
            }
            return lease;
        } catch (Throwable error) {
            lease.close();
            throw error;
        }
    }

    @Override public void close() {
        if (!this.level.getServer().isSameThread()) throw new IllegalStateException("Light ticket release requires owner thread");
        if (closed) return;
        closed = true;
        for (ChunkPos pos : held) this.level.getChunkSource().removeRegionTicket(TYPE, pos, 0, token);
        held.clear();
    }
}
