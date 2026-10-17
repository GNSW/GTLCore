// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

/**
 * Captured, immutable algebra for one deterministic consumable-state rule.
 * Implementations must not query a world, inventory or mutable host object.
 * Immutable key codecs may reconstruct captured identities through frozen registries.
 * The identity includes the per-use rule, cost, and all invariant state semantics.
 */
public interface StateFamily<K> {

    String id();

    boolean accepts(K state);

    /** Guaranteed complete uses available from this one physical entity. */
    long capacity(K state);

    /** Exact nominal return after one use; null means that the entity is consumed. */
    K nominalAfterOne(K state);
}
