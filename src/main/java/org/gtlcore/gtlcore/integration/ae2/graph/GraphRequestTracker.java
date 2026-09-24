package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.stacks.AEKey;

public interface GraphRequestTracker {

    void gtlcore$expectGraphOutput(AEKey key);

    default long gtlcore$graphProviderGeneration() {
        return 0;
    }

    default void gtlcore$invalidateGraphBinding(String binding) {}
}
