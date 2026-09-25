package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.config.AECraftingEngine;
import org.gtlcore.gtlcore.config.AEGraphSeedPolicy;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.graph.core.*;

import net.minecraft.world.level.Level;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID)
public final class CraftingEngineRouter {

    private CraftingEngineRouter() {}

    static <K> Map<K, Long> planningAvailability(Map<K, Long> network, Map<K, Long> forecast) {
        Map<K, Long> result = new LinkedHashMap<>(network);
        forecast.forEach((key, count) -> result.merge(key, CheckedAmounts.nonNegative(count), (stored, owned) -> {
            // Availability is a lower bound for a long-sized request, not an
            // ownership ledger. An infinity cell plus real CPU-held material
            // must not overflow before replanning even starts. Physical inputs,
            // expected outputs and settlement continue to use exact arithmetic.
            CheckedAmounts.nonNegative(stored);
            return stored + Math.min(owned, Long.MAX_VALUE - stored);
        }));
        return result;
    }

    private static PlanningScheduler scheduler;

    private record PlanLogKey(AEKey target, long amount, CalculationStrategy strategy, GraphPlan.Result result, long epoch, int missing) {}

    private static final class LogWindow {

        long last, skipped;

        LogWindow(long now) {
            last = now;
        }
    }

    private static final Map<IGrid, Map<PlanLogKey, LogWindow>> PLAN_LOGS = new WeakHashMap<>();

    /** Repeating requesters retain diagnostics without printing four lines every second. */
    private static long logAllowance(IGrid grid, PlanLogKey key) {
        synchronized (PLAN_LOGS) {
            Map<PlanLogKey, LogWindow> windows = PLAN_LOGS.computeIfAbsent(grid, unused -> new LinkedHashMap<>());
            long now = System.nanoTime();
            LogWindow window = windows.get(key);
            if (window == null) {
                if (windows.size() >= 256) windows.remove(windows.keySet().iterator().next());
                windows.put(key, new LogWindow(now));
                return 0;
            }
            if (now - window.last < 30_000_000_000L) {
                window.skipped++;
                return -1;
            }
            long skipped = window.skipped;
            window.last = now;
            window.skipped = 0;
            return skipped;
        }
    }

    private static final class Settings {

        static final AECraftingEngine ENGINE = ConfigHolder.INSTANCE.ae2CraftingEngine;
    }

    public static boolean useGraph() {
        return Settings.ENGINE == AECraftingEngine.GRAPH;
    }

    private static synchronized PlanningScheduler scheduler() {
        if (scheduler == null) scheduler = new PlanningScheduler(ConfigHolder.INSTANCE.ae2GraphPlannerThreads,
                ConfigHolder.INSTANCE.ae2GraphPlannerMaxRequests, 4096, 2_000_000L);
        return scheduler;
    }

    static CompletableFuture<GraphRingView> describe(GraphRingView.Builder work) {
        return scheduler().submit(work, new PlanningBudget(0, 1_000_000,
                ConfigHolder.INSTANCE.ae2GraphPlannerMemoryMiB * (1L << 20), () -> false, System::nanoTime));
    }

    @SubscribeEvent
    public static synchronized void stop(ServerStoppedEvent event) {
        if (scheduler != null) scheduler.close();
        scheduler = null;
        synchronized (PLAN_LOGS) {
            PLAN_LOGS.clear();
        }
    }

    public static Future<ICraftingPlan> begin(GtlPatternCatalog catalog, IGrid grid, CraftingService service,
                                              Level level, ICraftingSimulationRequester requester,
                                              AEKey target, long amount, CalculationStrategy strategy) {
        return begin(catalog, grid, service, level, requester.getActionSource(), target, amount, strategy, null,
                ConfigHolder.INSTANCE.ae2GraphSeedPolicy == AEGraphSeedPolicy.PRESERVE);
    }

    public static GraphPlanningRequest replan(GtlPatternCatalog catalog, GraphCpuHost host, CraftingService service,
                                              GraphJobRuntime.ReplanCheckpoint<AEKey> checkpoint, boolean preserve) {
        return begin(catalog, host.grid(), service, host.level(), host.source(), checkpoint.target(), checkpoint.remaining(),
                CalculationStrategy.REPORT_MISSING_ITEMS, checkpoint, preserve);
    }

    private static GraphPlanningRequest begin(GtlPatternCatalog catalog, IGrid grid, CraftingService service,
                                              Level level, IActionSource source, AEKey target, long amount, CalculationStrategy strategy,
                                              GraphJobRuntime.ReplanCheckpoint<AEKey> checkpoint, boolean preserve) {
        if (amount <= 0) throw new IllegalArgumentException("Non-positive crafting request");
        PlanningBudget budget = new PlanningBudget(ConfigHolder.INSTANCE.ae2GraphPlannerTimeoutMs,
                ConfigHolder.INSTANCE.ae2GraphPlannerMaxSteps, ConfigHolder.INSTANCE.ae2GraphPlannerMemoryMiB * (1L << 20),
                () -> false, System::nanoTime);
        GraphPlanningRequest result = new GraphPlanningRequest(budget);
        if (ConfigHolder.INSTANCE.ae2GraphDiagnosticLogging) budget.enableMetrics();
        CompletableFuture<GtlPatternCatalog.Snapshot> snapshot = new CompletableFuture<>();
        var work = new RequestWork(grid, snapshot, target, amount, strategy, budget, checkpoint, preserve, result);
        // Admit before collecting world data; rejected/cancelled requests never collect a snapshot.
        var worker = scheduler().submit(work, budget);
        result.attach(worker);
        worker.whenComplete((plan, error) -> {
            if (error != null) {
                work.logFailure(error);
                result.completeExceptionally(error);
            } else result.complete(plan);
        });
        result.whenComplete((plan, error) -> { if (error != null) snapshot.cancel(false); });
        Runnable capture = () -> {
            if (result.isDone()) return;
            long captureStarted = System.nanoTime();
            try {
                GtlPatternCatalog.Capture task;
                try (var timing = budget.work(PlanningBudget.Phase.SNAPSHOT)) {
                    budget.phase(PlanningBudget.Phase.SNAPSHOT);
                    int parallelism = 1;
                    for (var cpu : service.getCpus()) if (!cpu.isBusy())
                        parallelism = (int) Math.max(parallelism, Math.min(4096L, (long) cpu.getCoProcessors() + 1));
                    // Published by completion of the snapshot future; workers never inspect CPUs.
                    work.catalysts = new CatalystPolicy(parallelism, ConfigHolder.INSTANCE.ae2GraphMaxExtraCatalystCopies);
                    task = catalog.begin(grid, service, level, source, target,
                            checkpoint == null ? Set.of() : checkpoint.recoverySeeds().keySet(), budget);
                }
                long preparationNanos = System.nanoTime() - captureStarted;
                GraphSnapshots.enqueue(task, budget, snapshot, timing -> {
                    work.snapshotNanos = preparationNanos + timing.activeNanos();
                    work.snapshotTiming = timing;
                    work.snapshotElapsedNanos = System.nanoTime() - captureStarted;
                });
            } catch (Throwable error) {
                snapshot.completeExceptionally(error);
            }
        };
        if (level.getServer() == null) snapshot.completeExceptionally(new IllegalStateException("Graph crafting requires server level"));
        else if (level.getServer().isSameThread()) capture.run();
        else level.getServer().execute(capture);
        return result;
    }

    private static final class RequestWork implements PlanningScheduler.Work<ICraftingPlan> {

        private final IGrid grid;

        private final CompletableFuture<GtlPatternCatalog.Snapshot> capture;
        private final AEKey target;
        private final long amount;
        private final CalculationStrategy strategy;
        private final PlanningBudget budget;
        private final GraphJobRuntime.ReplanCheckpoint<AEKey> checkpoint;
        private final boolean preserve;
        private final GraphPlanningRequest request;
        private GtlPatternCatalog.Snapshot snapshot;
        private CapturedPatternCatalog.Build preparing;
        private CapturedPatternCatalog.Prepared prepared;
        private GraphCompiler<AEKey> compiler;
        private Map<AEKey, Long> available;
        private CatalystPlanningWork<AEKey> current;
        private GraphPlan<AEKey> selected;
        private boolean partialSearch, directEmission, unavailableTarget;
        private long low, high, middle, snapshotNanos, snapshotElapsedNanos, catalogPreparationNanos;
        private long catalogPreparationStarted, catalogPreparationElapsed, catalogParallelNanos;
        private int catalogParallelBatches;
        private GraphSnapshots.Timing snapshotTiming;
        private AeGraphPlan result;
        private CatalystPolicy catalysts = CatalystPolicy.MINIMAL;

        private RequestWork(IGrid grid, CompletableFuture<GtlPatternCatalog.Snapshot> capture, AEKey target, long amount,
                            CalculationStrategy strategy, PlanningBudget budget,
                            GraphJobRuntime.ReplanCheckpoint<AEKey> checkpoint, boolean preserve, GraphPlanningRequest request) {
            this.grid = grid;
            this.capture = capture;
            this.target = target;
            this.amount = amount;
            this.strategy = strategy;
            this.budget = budget;
            this.checkpoint = checkpoint;
            this.preserve = preserve;
            this.request = request;
        }

        @Override
        public boolean advance(PlanningScheduler.Slice slice) {
            if (!capture.isDone()) return false;
            if (snapshot == null) {
                snapshot = capture.join();
                preparing = snapshot.structure().catalog().build(budget);
                catalogPreparationStarted = System.nanoTime();
            }
            if (compiler == null) {
                long started = System.nanoTime();
                boolean ready = preparing.advance(slice);
                catalogPreparationNanos += System.nanoTime() - started;
                if (!ready) return false;
                prepared = preparing.result();
                catalogPreparationElapsed = System.nanoTime() - catalogPreparationStarted;
                catalogParallelNanos = preparing.parallelActiveNanos();
                catalogParallelBatches = preparing.parallelBatches();
                compiler = prepared.compiler();
                preparing = null;
                request.dependencies(snapshot.structure().resources());
                available = planningAvailability(snapshot.stock(), checkpoint == null ? Map.of() : checkpoint.forecast());
                directEmission = snapshot.emitable().contains(target) && compiler.producers(target).isEmpty();
                unavailableTarget = checkpoint == null && !snapshot.emitable().contains(target) && compiler.producers(target).isEmpty();
                budget.note("catalog", "recipes=" + compiler.catalog().size() + "; target_sources=" + compiler.producers(target).size() +
                        "; stock_keys=" + available.size() + "; target_stock=" + available.getOrDefault(target, 0L) +
                        "; external=" + snapshot.emitable().size() + "; cache_hit=" + snapshot.cacheHit() +
                        "; input_alternatives_bounded=" + snapshot.structure().boundedAlternatives());
                if (directEmission && checkpoint == null) available.remove(target);
                current = calculation(amount);
            }
            if (!current.advance(slice)) return false;
            GraphPlan<AEKey> candidate = current.result();
            if (!partialSearch) {
                selected = candidate;
                if (selected.feasible() || unavailableTarget || strategy != CalculationStrategy.CRAFT_LESS || selected.missing().isEmpty()) return finish();
                partialSearch = true;
                low = 1;
                high = amount - 1;
            } else {
                if (candidate.feasible()) {
                    selected = candidate;
                    low = middle + 1;
                } else if (candidate.missing().isEmpty()) throw limitOrUnknown(candidate);
                else high = middle - 1;
            }
            if (low > high) return finish();
            middle = low + (high - low) / 2;
            current.close();
            current = calculation(middle);
            return false;
        }

        private CatalystPlanningWork<AEKey> calculation(long count) {
            budget.note("request", "target=" + target + "; amount=" + count + "; strategy=" + strategy + "; preserve_seeds=" + preserve);
            return new CatalystPlanningWork<>(checkpoint != null ? CatalystPolicy.MINIMAL : catalysts, budget,
                    policy -> new GraphPlanningWork<>(compiler, target, count, available, snapshot.emitable(),
                            checkpoint == null ? Map.of() : checkpoint.recoverySeeds(), preserve, checkpoint == null && !directEmission, budget).catalysts(policy));
        }

        @Override
        public void close() {
            if (current != null) current.close();
        }

        private RuntimeException limitOrUnknown(GraphPlan<AEKey> plan) {
            return switch (plan.result()) {
                case TIMEOUT, SEARCH_LIMIT, MEMORY_LIMIT, GRAPH_LIMIT, QUEUE_LIMIT -> new PlanningBudget.Exhausted(PlanningBudget.Limit.valueOf(plan.result().name()), budget.failureDetail());
                default -> new IllegalStateException("Graph crafting: " + plan.result() +
                        (budget.failureDetail().isEmpty() ? "" : " (" + budget.failureDetail() + ")"));
            };
        }

        private void logFailure(Throwable error) {
            Throwable cause = error;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            if (cause instanceof java.util.concurrent.CancellationException) return;
            if (ConfigHolder.INSTANCE.ae2GraphDiagnosticLogging) GTLCore.LOGGER.warn(
                    "[Graph Crafting] plan failed target={} amount={} phase={} nodes={} reserved_bytes={} elapsed_ms={} detail={} strategies={} error={}",
                    target, amount, budget.phase(), budget.nodes(), budget.peakBytes(), budget.elapsedNanos() / 1_000_000.0,
                    budget.failureDetail(), budget.diagnostics(), cause.toString(), error);
        }

        private boolean finish() {
            if (!selected.feasible() && selected.missing().isEmpty()) throw limitOrUnknown(selected);
            if (!selected.feasible() && snapshot.structure().boundedAlternatives())
                throw budget.exhausted(PlanningBudget.Limit.SEARCH_LIMIT, "input_alternatives_bounded; missing preview is not a proof for omitted alternatives");
            Map<AEKey, Long> extractionStock = new LinkedHashMap<>(available);
            if (directEmission && checkpoint == null) extractionStock.remove(target);
            long assemblyStarted = System.nanoTime();
            result = new AeGraphPlan(selected, prepared.bindings(), snapshot.emitable(), extractionStock);
            long assemblyNanos = System.nanoTime() - assemblyStarted;
            long skipped = ConfigHolder.INSTANCE.ae2GraphDiagnosticLogging ? logAllowance(grid,
                    new PlanLogKey(target, amount, strategy, selected.result(), snapshot.epoch(), selected.missingExact().hashCode())) : -1;
            if (skipped >= 0) GTLCore.LOGGER.info(
                    "[Graph Crafting] plan target={} result={} amount={} snapshot_ms={} planner_ms={} queue_ms={} patterns={} nodes={} cache_hit={} bytes={} plan={} snapshot_elapsed_ms={} snapshot_wait_ms={} plan_assembly_ms={} catalog_prepare_ms={} snapshot_idle_ms={} snapshot_tick_slices={} snapshot_idle_slices={} snapshot_max_slice_ms={} catalog_elapsed_ms={} catalog_parallel_ms={} catalog_parallel_batches={} target_sources={} catalog_recipes={} missing={} suppressed_repeats={}",
                    target, selected.result(), selected.amount(), snapshotNanos / 1_000_000.0, selected.planningNanos() / 1_000_000.0,
                    budget.waitingNanos() / 1_000_000.0, selected.recipes().size(), budget.nodes(), snapshot.cacheHit(), result.bytes(), result.id(),
                    snapshotElapsedNanos / 1_000_000.0, Math.max(0, snapshotElapsedNanos - snapshotNanos) / 1_000_000.0, assemblyNanos / 1_000_000.0,
                    (catalogPreparationNanos + catalogParallelNanos) / 1_000_000.0, snapshotTiming.idleNanos() / 1_000_000.0,
                    snapshotTiming.tickSlices(), snapshotTiming.idleSlices(), snapshotTiming.maxSliceNanos() / 1_000_000.0,
                    catalogPreparationElapsed / 1_000_000.0, catalogParallelNanos / 1_000_000.0, catalogParallelBatches,
                    compiler.producers(target).size(), compiler.catalog().size(), selected.missingExact().entrySet().stream().limit(4).toList(), skipped);
            if (skipped >= 0) GTLCore.LOGGER.info("[Graph Crafting] phases={} wall_ms={} order_amount={}",
                    budget.metrics(), budget.runningWallNanos() / 1_000_000.0, selected.amount());
            return true;
        }

        @Override
        public CompletableFuture<?> waitingFor() {
            if (!capture.isDone()) return capture;
            if (preparing != null) return preparing.waitingFor();
            return current == null ? null : current.waitingFor();
        }

        @Override
        public ICraftingPlan result() {
            return result;
        }

        @Override
        public ICraftingPlan limited(PlanningBudget.Exhausted limit) {
            if (current != null) {
                GraphPlan<AEKey> retained = current.limited(limit);
                if (retained.feasible()) selected = retained;
            }
            if (selected == null || !selected.feasible()) throw limit;
            selected = new GraphPlan<>(selected.target(), selected.amount(), selected.preserveSeeds(), selected.steps(),
                    selected.recipes(), selected.initialExact(), selected.seeds(), Map.of(), GraphPlan.Result.FEASIBLE_NOT_PROVEN_OPTIMAL,
                    budget.nodes(), selected.planningNanos());
            finish();
            return result;
        }
    }
}
