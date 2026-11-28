package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import appeng.api.storage.MEStorage;

import java.util.List;

public record Source(SourceId id, SourceKind kind, MEStorage physical, List<MEStorage> views,
                     List<SourceRoute> routes, long identityHash, long sourceEpoch, int priority) {

    public Source {
        views = List.copyOf(views);
        routes = List.copyOf(routes);
    }

    public MEStorage routeTarget() {
        for (SourceRoute route : routes) {
            MEStorage target = route.target();
            if (target != null) {
                return target;
            }
        }
        return physical;
    }
}
