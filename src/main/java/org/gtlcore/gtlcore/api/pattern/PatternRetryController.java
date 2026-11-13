package org.gtlcore.gtlcore.api.pattern;

/** Shared retry state for the regular and independently scheduled controller implementations. */
public interface PatternRetryController {

    PatternRetryState gtlcore$getPatternRetryState();

    void gtlcore$queueFormationCheck(long period, boolean eager, boolean recheckFormed);

    /** Recheck a formed structure, or invalidate it when its controller block was removed. */
    void gtlcore$onStructureBlockChanged(boolean controllerRemoved);
}
