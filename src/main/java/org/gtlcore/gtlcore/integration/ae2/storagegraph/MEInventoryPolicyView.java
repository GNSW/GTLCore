package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import appeng.api.stacks.AEKey;

/**
 * Policy view of an AE2 inventory wrapper. Mirrors {@code MEInventoryHandler}'s own rules instead of
 * collapsing them into one {@code allows(key)}: insert always checks the allow flag and the
 * black/white list, extract only checks the list when extraction filtering is enabled, and
 * availability only filters when {@code filterAvailableContents} is enabled.
 */
public interface MEInventoryPolicyView {

    boolean gtlcore$allowsExtract(AEKey key);

    boolean gtlcore$allowsInsert(AEKey key);

    boolean gtlcore$isAvailable(AEKey key);
}
