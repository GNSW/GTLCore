package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import appeng.api.storage.MEStorage;

import java.util.List;

/**
 * One legal access path to a physical source. {@code path} contains every StorageBus wrapper from
 * the outermost one to the innermost one; callers must invoke the outermost wrapper so all filters
 * on the way are applied. An empty path means the physical leaf is accessed directly.
 */
public record SourceRoute(List<MEStorage> path, int priority, int nativeOrder) {

    public SourceRoute {
        path = List.copyOf(path);
    }

    public MEStorage target() {
        return path.isEmpty() ? null : path.get(0);
    }
}
