// SPDX-License-Identifier: MPL-2.0
package org.voxelregion.journal;

import java.util.Objects;
import java.util.UUID;

/** Immutable operation parameters. The payload must encode geometry, rules and their format version. */
public record OperationIntent(UUID id, String dimension, String actor, byte[] payload) {
    public static final int MAX_PAYLOAD = 1_048_576;
    public OperationIntent {
        Objects.requireNonNull(id); Objects.requireNonNull(dimension); Objects.requireNonNull(actor);
        Objects.requireNonNull(payload);
        if (dimension.isBlank() || dimension.length() > 4096 || actor.length() > 4096 || payload.length > MAX_PAYLOAD)
            throw new IllegalArgumentException("Invalid operation metadata");
        payload = payload.clone();
    }
    @Override public byte[] payload() { return payload.clone(); }
}
