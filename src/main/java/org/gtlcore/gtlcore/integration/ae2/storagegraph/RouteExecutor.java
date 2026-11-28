package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import org.gtlcore.gtlcore.config.StorageGraphMode;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RouteExecutor {

    private RouteExecutor() {}

    public static long extract(MEStorage root, AEKey what, long amount, Actionable mode, IActionSource source) {
        if (root == null || what == null || amount <= 0L || StorageGraphManager.mode() != StorageGraphMode.ON) {
            return -1L;
        }
        StorageGraphSnapshot snapshot = StorageGraphManager.snapshot(root);
        if (snapshot == null || !snapshot.complete()) {
            return -1L;
        }
        if (!hasCandidate(snapshot, false)) {
            return -1L;
        }
        List<RouteEntry> routes = collectRoutes(snapshot, what, false, source);
        if (routes.isEmpty()) {
            return 0L;
        }
        StorageOperationContext context = StorageOperationContext.begin(mode, source, snapshot.topologyEpoch());
        try {
            long remaining = amount;
            long extracted = 0L;
            for (RouteEntry entry : routes) {
                if (remaining <= 0L) {
                    break;
                }
                long got = entry.target().extract(what, remaining, mode, source);
                if (got > 0L) {
                    extracted = StorageGraphManager.saturatingAdd(extracted, got);
                    remaining -= got;
                    context.addConsumed(entry.source().id(), got);
                }
            }
            return extracted;
        } finally {
            StorageOperationContext.end(context);
        }
    }

    public static long insert(MEStorage root, AEKey what, long amount, Actionable mode, IActionSource source) {
        if (root == null || what == null || amount <= 0L || StorageGraphManager.mode() != StorageGraphMode.ON) {
            return -1L;
        }
        StorageGraphSnapshot snapshot = StorageGraphManager.snapshot(root);
        if (snapshot == null || !snapshot.complete()) {
            return -1L;
        }
        if (!hasCandidate(snapshot, true)) {
            return -1L;
        }
        List<RouteEntry> routes = collectRoutes(snapshot, what, true, source);
        if (routes.isEmpty()) {
            return 0L;
        }
        StorageOperationContext context = StorageOperationContext.begin(mode, source, snapshot.topologyEpoch());
        try {
            long remaining = amount;
            long inserted = 0L;
            for (RouteEntry entry : routes) {
                if (remaining <= 0L) {
                    break;
                }
                long got = entry.target().insert(what, remaining, mode, source);
                if (got > 0L) {
                    inserted = StorageGraphManager.saturatingAdd(inserted, got);
                    remaining -= got;
                    context.addInserted(entry.source().id(), got);
                }
            }
            return inserted;
        } finally {
            StorageOperationContext.end(context);
        }
    }

    private static boolean hasCandidate(StorageGraphSnapshot snapshot, boolean insert) {
        for (Source graphSource : snapshot.sources()) {
            if (graphSource.kind() == SourceKind.FINITE || graphSource.kind() == SourceKind.INFINITE ||
                    (insert && graphSource.kind() == SourceKind.VIRTUAL)) {
                return true;
            }
            if (graphSource.kind() != SourceKind.VIRTUAL) {
                StorageGraphDiagnostics.logCompatFallback(snapshot.uniqueSourceCount(), snapshot.opaqueSourceCount());
                return false;
            }
        }
        return false;
    }

    private static List<RouteEntry> collectRoutes(StorageGraphSnapshot snapshot, AEKey what, boolean insert,
                                                  IActionSource source) {
        List<RouteEntry> result = new ArrayList<>();
        for (Source graphSource : snapshot.sources()) {
            if (graphSource.physical() == null) {
                continue;
            }
            if (insert) {
                if (graphSource.kind() != SourceKind.FINITE && graphSource.kind() != SourceKind.INFINITE &&
                        graphSource.kind() != SourceKind.VIRTUAL) {
                    continue;
                }
            } else if (graphSource.kind() != SourceKind.FINITE && graphSource.kind() != SourceKind.INFINITE) {
                continue;
            }
            for (SourceRoute route : orderedRoutes(graphSource, !insert)) {
                MEStorage target = routeTarget(graphSource, route);
                if (target == null) {
                    continue;
                }
                if (insert ? !allowsInsert(target, what) : !allowsExtract(target, what)) {
                    continue;
                }
                result.add(new RouteEntry(graphSource, route, target));
            }
        }
        Comparator<RouteEntry> comparator = (first, second) -> {
            boolean firstPreferred = isPreferred(first.target(), what, source);
            boolean secondPreferred = isPreferred(second.target(), what, source);
            if (firstPreferred != secondPreferred) {
                return firstPreferred ? -1 : 1;
            }
            int priorities = comparePriorities(first.route().priorities(), second.route().priorities());
            return insert ? -priorities : priorities;
        };
        result.sort(comparator);
        return result;
    }

    private static boolean isPreferred(MEStorage target, AEKey what, IActionSource source) {
        try {
            return target.isPreferredStorageFor(what, source);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static int comparePriorities(List<Integer> first, List<Integer> second) {
        int shared = Math.min(first.size(), second.size());
        for (int i = 0; i < shared; i++) {
            int cmp = Integer.compare(first.get(i), second.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(first.size(), second.size());
    }

    private static MEStorage routeTarget(Source source, SourceRoute route) {
        MEStorage target = route.target();
        if (target != null) {
            return target;
        }
        return route.leafView() != null ? route.leafView() : source.physical();
    }

    private static boolean allowsExtract(MEStorage target, AEKey what) {
        return !(target instanceof MEInventoryPolicyView view) || view.gtlcore$allowsExtract(what);
    }

    private static boolean allowsInsert(MEStorage target, AEKey what) {
        return !(target instanceof MEInventoryPolicyView view) || view.gtlcore$allowsInsert(what);
    }

    private static List<SourceRoute> orderedRoutes(Source source, boolean ascending) {
        List<SourceRoute> routes = new ArrayList<>(source.routes());
        Comparator<SourceRoute> byWrapper = Comparator.comparingInt(route -> route.path().isEmpty() ? 1 : 0);
        Comparator<SourceRoute> byPriorities = (first, second) -> comparePriorities(first.priorities(),
                second.priorities());
        if (ascending) {
            routes.sort(byWrapper.thenComparing(byPriorities));
        } else {
            routes.sort(byWrapper.thenComparing(byPriorities.reversed()));
        }
        return routes;
    }

    private record RouteEntry(Source source, SourceRoute route, MEStorage target) {}
}
