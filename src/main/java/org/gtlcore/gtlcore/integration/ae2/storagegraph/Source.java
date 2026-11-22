package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import appeng.api.storage.MEStorage;

import java.util.List;

public record Source(SourceId id, SourceKind kind, MEStorage physical, List<SourceRoute> routes,
                     long identityHash, long sourceEpoch, int priority) {

    public Source {
        routes = List.copyOf(routes);
    }

    public MEStorage routeTarget() {
        return routes.isEmpty() ? physical : routes.get(0).target();
    }
}
