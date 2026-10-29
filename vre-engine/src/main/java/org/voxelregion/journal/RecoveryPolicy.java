// SPDX-License-Identifier: MPL-2.0
package org.voxelregion.journal;

/** Recovery never treats missing or uncertain evidence as permission to replay. */
public final class RecoveryPolicy {
    private RecoveryPolicy() {}
    public enum Decision { ALREADY_FINISHED, RESUME_UNTOUCHED, STOP_TERMINAL, PAUSE_UNCERTAIN, SKIP_PERMISSION }
    public static Decision decide(boolean operationTerminal, ChunkReceipts.Outcome chunkReceipt,
                                  boolean unchangedSinceIntentProven, boolean currentlyPermitted) {
        if (operationTerminal) return Decision.STOP_TERMINAL;
        if (chunkReceipt != null) return Decision.ALREADY_FINISHED;
        if (!unchangedSinceIntentProven) return Decision.PAUSE_UNCERTAIN;
        if (!currentlyPermitted) return Decision.SKIP_PERMISSION;
        return Decision.RESUME_UNTOUCHED;
    }
}
