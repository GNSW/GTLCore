package org.gtlcore.gtlcore.api.pattern;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.config.ConfigHolder;

import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongConsumer;

/** Owned by one world's saved data. All index operations run on its server thread. */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PatternRetryIndex {

    private final Map<IMultiController, Entry> entries = new IdentityHashMap<>();
    private final Long2ObjectOpenHashMap<Set<Entry>> chunks = new Long2ObjectOpenHashMap<>();
    private final Counters counters = new Counters();
    private long reportStart;
    private boolean reportedActive;

    public static PatternRetryIndex get(ServerLevel level) {
        return ((INewMultiblockWorldSavedData) MultiblockWorldSavedData.getOrCreate(level)).getPatternRetries();
    }

    public void watch(IMultiController controller) {
        Entry entry = entries.get(controller);
        BlockPattern pattern = controller.getPattern();
        Direction facing = controller.self().getFrontFacing();
        Direction upwards = controller.self().getUpwardsFacing();
        if (entry != null && entry.pattern == pattern && entry.facing == facing && entry.upwards == upwards) return;
        if (entry != null) unindex(entry);
        var retry = ((PatternRetryController) controller).gtlcore$getPatternRetryState();
        Entry replacement = new Entry(pattern, facing, upwards, PatternRetryBounds.create(controller, pattern),
                PatternRetryBounds.fixedPositions(controller, pattern), retry);
        entries.put(controller, replacement);
        forEachChunk(replacement.bounds, key -> chunks.computeIfAbsent(key, ignored -> new ReferenceOpenHashSet<>()).add(replacement));
        if (entry == null) retry.activate(counters);
        else retry.resetForLayout();
    }

    public void refresh(IMultiController controller) {
        if (!entries.containsKey(controller)) return;
        watch(controller);
    }

    public void remove(IMultiController controller) {
        Entry entry = entries.remove(controller);
        if (entry == null) return;
        unindex(entry);
        entry.retry.deactivate();
    }

    private void unindex(Entry entry) {
        forEachChunk(entry.bounds, key -> {
            Set<Entry> listeners = chunks.get(key);
            listeners.remove(entry);
            if (listeners.isEmpty()) chunks.remove(key);
        });
    }

    public void blockChanged(BlockPos pos) {
        Set<Entry> listeners = chunks.get(ChunkPos.asLong(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ())));
        if (listeners == null) return;
        boolean logging = ConfigHolder.INSTANCE.diagnostics.multiblockRetryLogging;
        long position = pos.asLong();
        for (Entry entry : listeners) {
            if (!entry.bounds.isInside(pos)) continue;
            if (logging) counters.boundsHits.increment();
            if (entry.positions != null && !entry.positions.contains(position)) {
                if (logging) counters.positionFiltered.increment();
                continue;
            }
            entry.retry.wake(false);
        }
    }

    public void formationException() {
        if (ConfigHolder.INSTANCE.diagnostics.multiblockRetryLogging) counters.formationExceptions.increment();
    }

    public void deferredRecheck() {
        if (ConfigHolder.INSTANCE.diagnostics.multiblockRetryLogging) counters.deferredRechecks.increment();
    }

    private void chunkLoaded(ChunkPos pos) {
        Set<Entry> listeners = chunks.get(pos.toLong());
        if (listeners != null) {
            for (Entry entry : listeners) entry.retry.wake(true);
        }
    }

    private void clear() {
        for (Entry entry : entries.values()) entry.retry.deactivate();
        entries.clear();
        chunks.clear();
    }

    private static void forEachChunk(BoundingBox bounds, LongConsumer consumer) {
        int maxX = SectionPos.blockToSectionCoord(bounds.maxX());
        int maxZ = SectionPos.blockToSectionCoord(bounds.maxZ());
        for (int x = SectionPos.blockToSectionCoord(bounds.minX()); x <= maxX; x++) {
            for (int z = SectionPos.blockToSectionCoord(bounds.minZ()); z <= maxZ; z++) {
                consumer.accept(ChunkPos.asLong(x, z));
            }
        }
    }

    private record Entry(BlockPattern pattern, Direction facing, Direction upwards, BoundingBox bounds,
                         LongSet positions, PatternRetryState retry) {}

    static final class Counters {

        final LongAdder checks = new LongAdder();
        final LongAdder eagerChecks = new LongAdder();
        final LongAdder failures = new LongAdder();
        final LongAdder skipped = new LongAdder();
        final LongAdder wakeups = new LongAdder();
        final LongAdder formed = new LongAdder();
        final LongAdder blockWakeups = new LongAdder();
        final LongAdder chunkWakeups = new LongAdder();
        final LongAdder layoutResets = new LongAdder();
        final LongAdder initialChecks = new LongAdder();
        final LongAdder wakeChecks = new LongAdder();
        final LongAdder periodicChecks = new LongAdder();
        final LongAdder boundsHits = new LongAdder();
        final LongAdder positionFiltered = new LongAdder();
        final LongAdder formationExceptions = new LongAdder();
        final LongAdder deferredRechecks = new LongAdder();

        boolean hasActivity() {
            return checks.sum() != 0 || skipped.sum() != 0 || wakeups.sum() != 0 || layoutResets.sum() != 0 ||
                    boundsHits.sum() != 0 || formationExceptions.sum() != 0 || deferredRechecks.sum() != 0;
        }

        void reset() {
            checks.reset();
            eagerChecks.reset();
            failures.reset();
            skipped.reset();
            wakeups.reset();
            formed.reset();
            blockWakeups.reset();
            chunkWakeups.reset();
            layoutResets.reset();
            initialChecks.reset();
            wakeChecks.reset();
            periodicChecks.reset();
            boundsHits.reset();
            positionFiltered.reset();
            formationExceptions.reset();
            deferredRechecks.reset();
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ChunkPos pos = event.getChunk().getPos();
            // Forge can emit chunk events while completing asynchronous generation.
            level.getServer().execute(() -> get(level).chunkLoaded(pos));
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) get(level).clear();
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel level) get(level).report(level);
    }

    private void report(ServerLevel level) {
        if (!ConfigHolder.INSTANCE.diagnostics.multiblockRetryLogging) {
            if (reportStart != 0) {
                reportStart = 0;
                reportedActive = false;
                counters.reset();
            }
            return;
        }
        long now = System.nanoTime();
        if (reportStart == 0) {
            reportStart = now;
            counters.reset();
            return;
        }
        if (now - reportStart < TimeUnit.MINUTES.toNanos(1)) return;
        boolean active = !entries.isEmpty() || counters.hasActivity();
        if (!active && !reportedActive) {
            reportStart = now;
            return;
        }
        GTLCore.LOGGER.info("[Multiblock Retry] schema=3 dim={} window_ms={} enabled={} max_seconds={} listeners={} chunks={} " +
                "checks={} eager_checks={} failures={} skipped={} wakeups={} formed={} " +
                "block_wakeups={} chunk_wakeups={} layout_resets={} initial_checks={} wake_checks={} periodic_checks={} " +
                "bounds_hits={} position_filtered={} formation_exceptions={} deferred_rechecks={}",
                level.dimension().location(), TimeUnit.NANOSECONDS.toMillis(now - reportStart),
                ConfigHolder.INSTANCE.performance.multiblockRetryBackoff,
                ConfigHolder.INSTANCE.performance.multiblockRetryMaxSeconds, entries.size(), chunks.size(),
                counters.checks.sumThenReset(), counters.eagerChecks.sumThenReset(), counters.failures.sumThenReset(),
                counters.skipped.sumThenReset(), counters.wakeups.sumThenReset(), counters.formed.sumThenReset(),
                counters.blockWakeups.sumThenReset(), counters.chunkWakeups.sumThenReset(), counters.layoutResets.sumThenReset(),
                counters.initialChecks.sumThenReset(), counters.wakeChecks.sumThenReset(), counters.periodicChecks.sumThenReset(),
                counters.boundsHits.sumThenReset(), counters.positionFiltered.sumThenReset(),
                counters.formationExceptions.sumThenReset(), counters.deferredRechecks.sumThenReset());
        reportedActive = active;
        reportStart = now;
    }
}
