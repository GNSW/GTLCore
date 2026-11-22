package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import appeng.api.storage.MEStorage;

/**
 * One legal access path to a physical source. {@code target} is the closest wrapper on the path
 * (a StorageBus inventory) so its filter / direction / access checks still apply; when no wrapper
 * exists the target is the physical leaf itself.
 */
public record SourceRoute(MEStorage target, int priority, int nativeOrder) {}
