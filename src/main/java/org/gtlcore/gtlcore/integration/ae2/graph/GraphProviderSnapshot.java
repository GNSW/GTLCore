package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.crafting.pattern.AEProcessingPattern;

import java.util.List;
import java.util.Set;

/** The registration values copied by AE for one provider, not its live inventory. */
public interface GraphProviderSnapshot {

    List<IPatternDetails> gtlcore$planningPatterns();

    Set<AEKey> gtlcore$planningEmitables();

    int gtlcore$planningPriority();

    default boolean gtlcore$samePlanningState(GraphProviderSnapshot previous) {
        // Only AE's concrete processing patterns have immutable encoded input
        // and output semantics. Equality alone cannot certify addon callbacks.
        if (previous == null || gtlcore$planningPriority() != previous.gtlcore$planningPriority() ||
                !gtlcore$planningEmitables().equals(previous.gtlcore$planningEmitables())) return false;
        var current = gtlcore$planningPatterns();
        var old = previous.gtlcore$planningPatterns();
        if (current.size() != old.size()) return false;
        for (int i = 0; i < current.size(); i++) {
            IPatternDetails a = current.get(i), b = old.get(i);
            if (a.getClass() != AEProcessingPattern.class || b.getClass() != AEProcessingPattern.class ||
                    !a.equals(b)) return false;
        }
        return true;
    }
}
