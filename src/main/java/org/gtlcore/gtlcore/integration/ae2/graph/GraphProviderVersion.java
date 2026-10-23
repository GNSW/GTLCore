package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.crafting.IPatternDetails;

/** Monotonic provider revision, including multiple edits inside the same server tick. */
public interface GraphProviderVersion {

    long gtlcore$providerGeneration();

    /** Effective planning changes; execution still observes every provider refresh. */
    default long gtlcore$planningGeneration() {
        return gtlcore$providerGeneration();
    }

    /** Live registered patterns; iterate on the server thread while the revision is unchanged. */
    Iterable<IPatternDetails> gtlcore$registeredPatterns();

    default void gtlcore$patternChanged(IPatternDetails pattern, boolean present) {}

    default ByproductPatternIndex gtlcore$patternSources() {
        return null;
    }
}
