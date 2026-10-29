// SPDX-License-Identifier: MPL-2.0
package org.voxelregion.api;

import java.util.UUID;
import org.voxelregion.journal.ChunkReceipts;
import org.voxelregion.planner.SectionPlan;

/** Pure final gate; evaluate on the world thread, immediately before mutation. */
public final class CommitGuard {
    private CommitGuard() {}
    public enum Decision { READY, ALREADY_APPLIED, SKIP_PERMISSION, REPLAN }
    public static Decision check(UUID operation, ChunkReceipts receipts, SectionPlan plan,
                                 long currentSectionRevision, long currentProtectionRevision,
                                 boolean currentlyPermitted) {
        java.util.Objects.requireNonNull(operation); java.util.Objects.requireNonNull(receipts);
        java.util.Objects.requireNonNull(plan);
        if (receipts.get(operation) != null) return Decision.ALREADY_APPLIED;
        if (!currentlyPermitted) return Decision.SKIP_PERMISSION;
        if (plan.sectionRevision() != currentSectionRevision || plan.protectionRevision() != currentProtectionRevision)
            return Decision.REPLAN;
        return Decision.READY;
    }
}
