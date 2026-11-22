package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import appeng.api.storage.MEStorage;

public record Source(SourceId id, SourceKind kind, MEStorage physical, MEStorage accessor, long identityHash,
                     long sourceEpoch, int priority) {

    public MEStorage routeTarget() {
        return accessor != null ? accessor : physical;
    }
}
