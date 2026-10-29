/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.IdentityHashMap;

/** Memoize repeated immutable record hashes only while constructing one private noise evaluator. */
public final class NoiseHashMemo implements AutoCloseable {
    private static final ThreadLocal<IdentityHashMap<Object, Integer>> ACTIVE = new ThreadLocal<>();
    private final IdentityHashMap<Object, Integer> previous;

    private NoiseHashMemo() {
        previous = ACTIVE.get();
        ACTIVE.set(new IdentityHashMap<>());
    }

    public static NoiseHashMemo open() { return new NoiseHashMemo(); }

    public static Integer lookup(Object record) {
        var map = ACTIVE.get();
        return map == null ? null : map.get(record);
    }

    public static void remember(Object record, int hash) {
        var map = ACTIVE.get();
        if (map != null && map.size() < 8192) map.put(record, hash);
    }

    @Override public void close() {
        if (previous == null) ACTIVE.remove();
        else ACTIVE.set(previous);
    }
}
