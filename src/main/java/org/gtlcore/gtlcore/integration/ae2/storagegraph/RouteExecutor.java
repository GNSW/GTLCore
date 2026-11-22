package org.gtlcore.gtlcore.integration.ae2.storagegraph;

import org.gtlcore.gtlcore.config.StorageGraphMode;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Executes a storage operation along the already collected graph sources instead of walking the
 * mounted storage list. Returns {@code -1} when the graph cannot answer exactly (incomplete graph,
 * OPAQUE storage, or compatibility fallback enabled).
 */
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
            } else {
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
                    long got = route.target().extract(what, remaining, mode, source);
                    if (got > 0L) {
                        extracted = StorageGraphManager.saturatingAdd(extracted, got);
                        remaining -= got;
                        context.addConsumed(graphSource.id(), got);
                        break;
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
            } else {
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
                    long got = route.target().insert(what, remaining, mode, source);
                    if (got > 0L) {
                        inserted = StorageGraphManager.saturatingAdd(inserted, got);
                        remaining -= got;
                        context.addInserted(graphSource.id(), got);
                        break;
                    }
                }
            }
            return inserted;
        } finally {
            StorageOperationContext.end(context);
        }
    }

    private static List<SourceRoute> orderedRoutes(Source source, boolean ascending) {
        List<SourceRoute> routes = new ArrayList<>(source.routes());
        if (ascending) {
            routes.sort(Comparator.comparingInt(SourceRoute::priority));
        } else {
            routes.sort(Comparator.comparingInt(SourceRoute::priority).reversed());
        }
        return routes;
    }
}
