package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;

import java.util.Map;

/** Read-only check for GTL providers with non-production success paths. */
public interface GraphDispatchPreflight {

    boolean gtlcore$canProduceGraphPattern(IPatternDetails pattern);

    /**
     * Confirmation checks only shared equipment, not current dispatch readiness.
     * Providers without shared-tool requirements must not refresh recipes or
     * scan their processing buffers merely to show a crafting preview.
     */
    default @org.jetbrains.annotations.Nullable net.minecraft.network.chat.Component gtlcore$graphSharedToolProblem(IPatternDetails pattern) {
        return null;
    }

    /** A failed machine preflight is not necessarily a missing shared tool. */
    default String gtlcore$graphBlockReason(IPatternDetails pattern) {
        return "PROVIDER_REJECTED";
    }

    default String gtlcore$graphBlockDetail(IPatternDetails pattern) {
        return gtlcore$graphBlockReason(pattern);
    }

    default net.minecraft.network.chat.Component gtlcore$graphBlockMessage(IPatternDetails pattern) {
        return net.minecraft.network.chat.Component.literal(gtlcore$graphBlockDetail(pattern));
    }

    default long gtlcore$graphCapacity(IPatternDetails pattern, Map<AEKey, Long> inputPerRun, long requested) {
        return requested;
    }
}
