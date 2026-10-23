package org.gtlcore.gtlcore.integration.ae2.graph;

/** A relevant live dependency changed while its request was being captured. */
final class GraphSnapshotChanged extends IllegalStateException {

    GraphSnapshotChanged(String detail) {
        super(detail);
    }
}
