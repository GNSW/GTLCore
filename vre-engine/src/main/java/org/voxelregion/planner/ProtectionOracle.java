/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.planner;

/**
 * Protection facts captured or safely queried by the host. Destruction and
 * propagation are deliberately independent. World-dependent implementations
 * must obey the host's thread and snapshot rules.
 */
public interface ProtectionOracle {
    enum StaticSummary { ALL_DESTROYABLE, NONE_DESTROYABLE, REQUIRES_VOXEL_CHECK, UNKNOWN }

    boolean mayDestroy(int state, int x, int y, int z);
    boolean blocksPropagation(int state, int x, int y, int z);

    /** Hosts must change this token whenever any protection rule changes. */
    default long revision() { return 0; }

    /** Must remain true when position, block entity, or external state matters. */
    default boolean requiresContextualChecks() { return true; }

    /** A conservative proof, not a heuristic or a cached resistance guess. */
    default StaticSummary staticSummary(SectionSnapshot section) { return StaticSummary.UNKNOWN; }
}
