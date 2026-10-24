// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.cgse.core;

import java.util.*;

/** Immutable recipe ownership and a bounded, inventory-independent startup index.
 * Preparation is charged and sliced by the guard that first needs it. */
final class GraphRecipeCatalog<K> extends AbstractList<GraphRecipe<K>> implements RandomAccess {
    private static final long MAX_STARTUP_BYTES = 16L << 20;
    private final List<GraphRecipe<K>> recipes;
    record Startup<K>(Map<K, List<GraphRecipe<K>>> growing, Map<K, List<GraphRecipe<K>>> returned) {}
    private volatile Startup<K> startup;
    private long startupBytes;

    GraphRecipeCatalog(List<GraphRecipe<K>> recipes) { this.recipes = List.copyOf(recipes); }
    @Override public GraphRecipe<K> get(int index) { return recipes.get(index); }
    @Override public int size() { return recipes.size(); }

    Startup<K> startup() { return startup; }

    /** The caller transfers a completed index and must never mutate it again. */
    synchronized Startup<K> rememberStartup(Map<K, List<GraphRecipe<K>>> index,
                                           Map<K, List<GraphRecipe<K>>> returned, long bytes) {
        if (startup == null && bytes <= MAX_STARTUP_BYTES) {
            startupBytes = bytes;
            startup = new Startup<>(Collections.unmodifiableMap(index), Collections.unmodifiableMap(returned));
        }
        return startup;
    }

    synchronized GraphCompiler.CacheEntry startupMetrics() {
        return new GraphCompiler.CacheEntry(startup == null ? 0 : 1,
                startup == null ? 0 : startup.growing.size() + startup.returned.size(), startupBytes, 0, 0, 0);
    }
}
