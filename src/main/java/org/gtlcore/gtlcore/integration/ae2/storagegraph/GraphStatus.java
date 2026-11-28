package org.gtlcore.gtlcore.integration.ae2.storagegraph;

/**
 * Topology completeness of a collected snapshot. Only {@link #COMPLETE} may replace
 * native AE2 results; route/policy validation is a separate PR-3 concern.
 */
public enum GraphStatus {
    COMPLETE,
    INCOMPLETE_NODE_LIMIT,
    INCOMPLETE_DEPTH_LIMIT,
    INCOMPLETE_ROUTE_LIMIT
}
