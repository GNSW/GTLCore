/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.Objects;
import java.util.UUID;

/** Checks co-saved evidence only; a SavedData cursor never authorizes replay. */
final class RecoveryValidation {
    enum Decision { RESUME, BEGIN_NEXT_PHASE, SKIP_PHASE, STOP_TERMINAL, PAUSE_UNCERTAIN }
    record Result(Decision decision, ChunkReceipts.Cursor cursor, String reason) { }

    static Result check(ChunkReceipts receipts, UUID operation, String intentHash, long phase) {
        Objects.requireNonNull(receipts); Objects.requireNonNull(operation); Objects.requireNonNull(intentHash);
        if (phase < 0 || phase == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid phase");
        byte state = receipts.state(operation);
        if (ChunkReceipts.terminal(state)) return result(Decision.STOP_TERMINAL, null, "Terminal chunk receipt");
        ChunkReceipts.Checkpoint checkpoint = receipts.checkpoint(operation);
        if (checkpoint == null) return uncertain(state == 0 ? "Unvisited chunk has no restart evidence" : "Legacy STARTED receipt has no cursor");
        if (!intentHash.equals(checkpoint.intentHash())) return uncertain("Immutable operation parameters changed");
        if (phase <= checkpoint.completedThroughPhase()) return result(Decision.SKIP_PHASE, null, "Phase already terminal; never replay later construction");
        if (!receipts.trackingValid()) return uncertain("Mutation tracking is not trustworthy");
        if (checkpoint.pendingWrite()) return uncertain("Native write may have been captured before its result was known");
        if (checkpoint.expectedMutationRevision() != receipts.mutationRevision()) return uncertain("Chunk was edited after the last operation checkpoint");
        if (checkpoint.workingPhase() == phase) return result(Decision.RESUME, checkpoint.cursor(), "Co-saved cursor and mutation revision agree");
        if (checkpoint.workingPhase() == -1) return result(Decision.BEGIN_NEXT_PHASE,
                new ChunkReceipts.Cursor(checkpoint.minSectionY(), 0), "Prior phase complete; chunk remains unchanged");
        return uncertain("A different phase is still incomplete");
    }

    private static Result uncertain(String reason) { return result(Decision.PAUSE_UNCERTAIN, null, reason); }
    private static Result result(Decision decision, ChunkReceipts.Cursor cursor, String reason) { return new Result(decision, cursor, reason); }
    private RecoveryValidation() { }
}
