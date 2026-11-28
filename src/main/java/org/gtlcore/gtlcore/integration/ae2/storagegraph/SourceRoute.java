package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import appeng.api.storage.MEStorage;

import java.util.List;

/**
 * One legal access path to a physical source. {@code path} contains every StorageBus wrapper from
 * the outermost one to the innermost one; callers must invoke the outermost wrapper so all filters
 * on the way are applied. An empty path means the physical leaf is accessed directly.
 * <p>
 * {@code leafView} is the physical facade this route enumerates and operates on, so the counts,
 * the route permissions and the actual IO target all belong to the same view.
 */
public record SourceRoute(MEStorage leafView, List<MEStorage> path, List<Integer> priorities,
                          int nativeOrder) {

    public SourceRoute {
        path = List.copyOf(path);
        priorities = List.copyOf(priorities);
    }

    public MEStorage target() {
        return path.isEmpty() ? null : path.get(0);
    }

    public int priority() {
        int priority = 0;
        for (int value : priorities) {
            priority = Math.max(priority, value);
        }
        return priority;
    }
}
