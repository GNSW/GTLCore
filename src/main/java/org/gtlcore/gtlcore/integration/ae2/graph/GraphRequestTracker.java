package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;

import java.util.List;

public interface GraphRequestTracker {

    void gtlcore$expectGraphOutput(AEKey key);

    default long gtlcore$graphProviderGeneration() {
        return 0;
    }

    default long gtlcore$graphPlanningGeneration() {
        return gtlcore$graphProviderGeneration();
    }

    default void gtlcore$invalidateGraphBinding(String binding) {}

    default ByproductPatternIndex gtlcore$patternSources() {
        return null;
    }

    default Iterable<IPatternDetails> gtlcore$registeredGraphPatterns() {
        return List.of();
    }
}
