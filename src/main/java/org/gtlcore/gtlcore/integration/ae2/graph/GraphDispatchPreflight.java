package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;

import java.util.Map;

/** Read-only check for GTL providers with non-production success paths. */
public interface GraphDispatchPreflight {

    boolean gtlcore$canProduceGraphPattern(IPatternDetails pattern);

    default long gtlcore$graphCapacity(IPatternDetails pattern, Map<AEKey, Long> inputPerRun, long requested) {
        return requested;
    }
}
