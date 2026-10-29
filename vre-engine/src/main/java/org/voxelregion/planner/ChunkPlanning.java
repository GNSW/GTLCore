/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.planner;

import java.util.Objects;
import org.voxelregion.geometry.Coverage;

/** Pure lifecycle routing; it never loads a chunk or manufactures a safety proof. */
public final class ChunkPlanning {
    public enum Presence { LOADED, GENERATED_UNLOADED, NEVER_GENERATED, GENERATING, UNKNOWN }
    public enum Route { SKIP, PROCESS_EXISTING, MATERIALIZE_SAFE_EMPTY, NORMAL_GENERATION, DEFER }

    private ChunkPlanning() { }

    public static Route route(Presence presence, Coverage coverage, boolean fullyExposed,
                              ChunkPermission permission, GenerationSafety safety) {
        Objects.requireNonNull(presence, "presence");
        Objects.requireNonNull(coverage, "coverage");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(safety, "safety");
        if (coverage == Coverage.OUTSIDE || !permission.mayMutate()) return Route.SKIP;
        return switch (presence) {
            case LOADED, GENERATED_UNLOADED -> Route.PROCESS_EXISTING;
            case GENERATING, UNKNOWN -> Route.DEFER;
            case NEVER_GENERATED -> coverage == Coverage.INSIDE && fullyExposed
                    && safety.decision() == GenerationSafety.Decision.SAFE
                    ? Route.MATERIALIZE_SAFE_EMPTY : Route.NORMAL_GENERATION;
        };
    }
}
