package org.gtlcore.gtlcore.api.pattern;

/** Per-controller allocation hints; never stores matched world data or collections. */
public interface PatternCapacityState {

    PatternCapacityHints gtlcore$getCapacityHints();
}
