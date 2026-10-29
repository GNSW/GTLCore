/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.planner;

import java.util.Objects;

/**
 * Host evidence must cover protected terrain, structures, cross-chunk generation,
 * existing data, and the supported publication lifecycle. The core cannot prove it.
 */
public record GenerationSafety(Decision decision, String evidence) {
    public enum Decision { SAFE, REQUIRES_GENERATION, UNKNOWN }

    public GenerationSafety {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.isBlank()) throw new IllegalArgumentException("Safety requires explicit evidence or a reason");
    }

    public static GenerationSafety unknown(String reason) {
        return new GenerationSafety(Decision.UNKNOWN, reason);
    }
}
