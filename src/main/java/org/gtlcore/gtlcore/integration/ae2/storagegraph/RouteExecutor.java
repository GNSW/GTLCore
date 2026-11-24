package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import org.gtlcore.gtlcore.config.StorageGraphMode;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
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
        int candidates = 0;
        for (Source graphSource : snapshot.sources()) {
            if (graphSource.kind() == SourceKind.FINITE || graphSource.kind() == SourceKind.INFINITE) {
                candidates++;
            } else if (graphSource.kind() != SourceKind.VIRTUAL) {
                StorageGraphDiagnostics.logCompatFallback(snapshot.uniqueSourceCount(), snapshot.opaqueSourceCount());
                return -1L;
            }
        }
        if (candidates == 0) {
            return -1L;
        }
        StorageOperationContext context = StorageOperationContext.begin(mode, source, snapshot.topologyEpoch());
        try {
            List<Source> ordered = new ArrayList<>(snapshot.sources());
            ordered.sort(Comparator.comparingInt(Source::priority));
            long remaining = amount;
            long extracted = 0L;
            if (mode == Actionable.MODULATE) {
                for (MEStorage target : orderedExtractPaths(ordered, what)) {
                    if (remaining <= 0L) {
                        break;
                    }
                    long got = target.extract(what, remaining, mode, source);
                    if (got > 0L) {
                        extracted = StorageGraphManager.saturatingAdd(extracted, got);
                        remaining -= got;
                    }
                }
            } else {
                for (Source graphSource : ordered) {
                    if (remaining <= 0L) {
                        break;
                    }
                    if (graphSource.kind() != SourceKind.FINITE && graphSource.kind() != SourceKind.INFINITE) {
                        continue;
                    }
                    if (graphSource.physical() == null || !context.touch(graphSource.id())) {
                        continue;
                    }
                    for (SourceRoute route : orderedRoutes(graphSource, true)) {
                        MEStorage target = routeTarget(graphSource, route);
                        if (target == null || !allowsExtract(target, what)) {
                            continue;
                        }
                        long got = target.extract(what, remaining, mode, source);
                        if (got > 0L) {
                            extracted = StorageGraphManager.saturatingAdd(extracted, got);
                            remaining -= got;
                            context.addConsumed(graphSource.id(), got);
                            break;
                        }
                    }
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
        int candidates = 0;
        for (Source graphSource : snapshot.sources()) {
            if (graphSource.kind() == SourceKind.FINITE || graphSource.kind() == SourceKind.INFINITE) {
                candidates++;
            } else if (graphSource.kind() != SourceKind.VIRTUAL) {
                StorageGraphDiagnostics.logCompatFallback(snapshot.uniqueSourceCount(), snapshot.opaqueSourceCount());
                return -1L;
            }
        }
        if (candidates == 0) {
            return -1L;
        }
        StorageOperationContext context = StorageOperationContext.begin(mode, source, snapshot.topologyEpoch());
        try {
            List<Source> ordered = new ArrayList<>(snapshot.sources());
            ordered.sort(Comparator.comparingInt(Source::priority).reversed());
            long remaining = amount;
            long inserted = 0L;
            if (mode == Actionable.MODULATE) {
                for (MEStorage target : orderedInsertPaths(ordered, what)) {
                    if (remaining <= 0L) {
                        break;
                    }
                    long got = target.insert(what, remaining, mode, source);
                    if (got > 0L) {
                        inserted = StorageGraphManager.saturatingAdd(inserted, got);
                        remaining -= got;
                    }
                }
            } else {
                for (Source graphSource : ordered) {
                    if (remaining <= 0L) {
                        break;
                    }
                    if (graphSource.kind() != SourceKind.FINITE && graphSource.kind() != SourceKind.INFINITE) {
                        continue;
                    }
                    if (graphSource.physical() == null || !context.touch(graphSource.id())) {
                        continue;
                    }
                    for (SourceRoute route : orderedRoutes(graphSource, false)) {
                        MEStorage target = routeTarget(graphSource, route);
                        if (target == null || !allowsInsert(target, what)) {
                            continue;
                        }
                        long got = target.insert(what, remaining, mode, source);
                        if (got > 0L) {
                            inserted = StorageGraphManager.saturatingAdd(inserted, got);
                            remaining -= got;
                            context.addInserted(graphSource.id(), got);
                            break;
                        }
                    }
                }
            }
            return inserted;
        } finally {
            StorageOperationContext.end(context);
        }
    }

    private static List<MEStorage> orderedExtractPaths(List<Source> ordered, AEKey what) {
        IdentityHashMap<MEStorage, Integer> priorities = new IdentityHashMap<>();
        for (Source source : ordered) {
            if (source.kind() != SourceKind.FINITE && source.kind() != SourceKind.INFINITE) {
                continue;
            }
            if (source.physical() == null) {
                continue;
            }
            for (SourceRoute route : orderedRoutes(source, true)) {
                MEStorage target = routeTarget(source, route);
                if (target == null || !allowsExtract(target, what)) {
                    continue;
                }
                priorities.merge(target, route.priority(), Math::min);
            }
        }
        return sortedPaths(priorities);
    }

    private static List<MEStorage> orderedInsertPaths(List<Source> ordered, AEKey what) {
        IdentityHashMap<MEStorage, Integer> priorities = new IdentityHashMap<>();
        for (Source source : ordered) {
            if (source.kind() != SourceKind.FINITE && source.kind() != SourceKind.INFINITE) {
                continue;
            }
            if (source.physical() == null) {
                continue;
            }
            for (SourceRoute route : orderedRoutes(source, false)) {
                MEStorage target = routeTarget(source, route);
                if (target == null || !allowsInsert(target, what)) {
                    continue;
                }
                priorities.merge(target, route.priority(), Math::max);
            }
        }
        return sortedPaths(priorities, true);
    }

    private static List<MEStorage> sortedPaths(IdentityHashMap<MEStorage, Integer> priorities) {
        return sortedPaths(priorities, false);
    }

    private static List<MEStorage> sortedPaths(IdentityHashMap<MEStorage, Integer> priorities, boolean reversed) {
        List<MEStorage> paths = new ArrayList<>(priorities.keySet());
        if (reversed) {
            paths.sort(Comparator.comparingInt(priorities::get).reversed());
        } else {
            paths.sort(Comparator.comparingInt(priorities::get));
        }
        return paths;
    }

    private static MEStorage routeTarget(Source source, SourceRoute route) {
        return route.target() == null ? source.physical() : route.target();
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
        if (ascending) {
            routes.sort(byWrapper.thenComparingInt(SourceRoute::priority));
        } else {
            routes.sort(byWrapper.thenComparing(Comparator.comparingInt(SourceRoute::priority).reversed()));
        }
        return routes;
    }
}
