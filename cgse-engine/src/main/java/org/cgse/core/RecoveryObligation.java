// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.Map;

/** An order owns this restoration contract; none of its future seeds are inventory. */
public record RecoveryObligation<K>(String owner, String scope, Map<K, Long> seeds, long stage, Status status) {

    public enum Status {
        READY,
        IN_FLIGHT,
        INTERMEDIATE,
        RESTORED,
        ABANDONED
    }

    public RecoveryObligation {
        if (owner.isEmpty() || scope.isEmpty() || stage < 0) throw new IllegalArgumentException("Invalid recovery owner/stage");
        seeds = GraphRecipe.amounts(seeds);
    }
}
