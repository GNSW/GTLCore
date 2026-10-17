package org.gtlcore.gtlcore.integration.ae2;

/** Advances after every completed inventory sample, including samples with no quantity changes. */
public interface InventorySnapshotVersion {

    long gtlcore$inventorySnapshotVersion();

    /** Capture on the server thread after refreshing AE's inventory cache. */
    InventorySnapshot gtlcore$inventorySnapshot();
}
