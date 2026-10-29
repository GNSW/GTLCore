/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.voxelregion.forge.runtime.EntityOperationRunner;

/**
 * Privileged server-thread, one-pass entity operations, independent of terrain operations.
 * Hosts authenticate callers. Geometry selects the current entity position, not its bounding box.
 * Jobs are ephemeral and are cancelled on shutdown; APPLIED is not a durable storage acknowledgement.
 */
public final class ForgeEntityOperations {
    public enum Scope { LOADED_ONLY, LOAD_EXISTING_SAVED }
    public enum Mode { KILL, DISCARD }
    public enum State { QUEUED, RUNNING, APPLIED, CANCELLED, FAILED }

    /** DISCARD avoids normal non-player death drops. Players always use their normal kill path. */
    public record Options(boolean includePlayers, Scope scope, Mode mode) {
        public static final Options DEFAULT = new Options(true, Scope.LOAD_EXISTING_SAVED, Mode.DISCARD);
        public Options { Objects.requireNonNull(scope); Objects.requireNonNull(mode); }
    }
    public record Handle(ResourceLocation dimension, UUID id) {
        public Handle { Objects.requireNonNull(dimension); Objects.requireNonNull(id); }
    }
    /** Surviving counts native kill attempts that did not kill, including mod-vetoed damage. */
    public record Status(Handle handle, State state, long examined, long affected, long surviving,
            long skippedPlayers, long deniedChunks, long completedChunks, int pendingChunks, String message) {
        public boolean terminal() { return state == State.APPLIED || state == State.CANCELLED || state == State.FAILED; }
    }

    /**
     * The scan reads native entity storage without requesting terrain. Native kill/removal
     * callbacks may access terrain themselves; KILL retains those ordinary game semantics.
     */
    public static Handle submit(ServerLevel level, RegionGeometry geometry, Options options) {
        return EntityOperationRunner.submit(Objects.requireNonNull(level), Objects.requireNonNull(geometry),
                Objects.requireNonNull(options));
    }
    public static Optional<Status> status(ServerLevel level, UUID id) {
        return EntityOperationRunner.status(Objects.requireNonNull(level), Objects.requireNonNull(id));
    }
    /** Cancels remaining work; completed native effects are not undone. */
    public static boolean cancel(ServerLevel level, UUID id) {
        return EntityOperationRunner.cancel(Objects.requireNonNull(level), Objects.requireNonNull(id));
    }
    private ForgeEntityOperations() { }
}
