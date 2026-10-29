/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.List;
import net.minecraft.world.entity.Entity;

/** Internal native entity-manager access. Every call belongs to the owning server thread. */
public interface EntityStorageBridge {
    Manager vre$entityManager();

    interface Manager {
        void vre$acquireEntityChunk(long position, boolean loadSaved);
        boolean vre$entitiesLoaded(long position);
        /** Includes native hidden sections, which ordinary world queries intentionally omit. */
        List<Entity> vre$entitySnapshot(long position, int maximum);
        /** Queues ordinary native entity save/unload; does not block on disk or change chunk visibility. */
        void vre$releaseEntityChunk(long position);
    }
}
