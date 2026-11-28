package org.gtlcore.gtlcore.integration.ae2.storagegraph;

public record PolicySnapshot(
                             DirectionMode direction,
                             int priority,
                             String filterDescriptor,
                             boolean extractableOnly,
                             boolean voidOverflow,
                             long policyEpoch) {

    public static PolicySnapshot unknown() {
        return new PolicySnapshot(DirectionMode.BOTH, 0, "", false, false, 0L);
    }
}
