package org.gtlcore.gtlcore.common.diagnostics;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.mixin.mc.BlockEntityTypeValidityAccessor;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.google.common.collect.ImmutableSet;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Explicit, bounded observation of the original validity checks; never substitutes a cached result. */
@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID)
public final class BlockEntityValidityDiagnostics {

    private static volatile Session active;

    private BlockEntityValidityDiagnostics() {}

    public static Session activeSession() {
        return active;
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("gtlbediag")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("start")
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1))
                                .executes(context -> start(context.getSource(), IntegerArgumentType.getInteger(context, "ticks")))))
                .then(Commands.literal("stop").executes(context -> stop(context.getSource()))));
    }

    private static int start(CommandSourceStack source, int ticks) {
        if (active != null) {
            source.sendFailure(Component.literal("方块实体合法性诊断已在运行；请先使用 /gtlbediag stop。"));
            return 0;
        }
        active = new Session(source.getServer(), ticks);
        GTLCore.LOGGER.info("[BE Validity] start requested_ticks={} mode=observation no_per_check_timing=true", ticks);
        source.sendSuccess(() -> Component.literal("方块实体合法性诊断已开启，采集 " + ticks +
                " tick 后自动结束；汇总写入 logs/latest.log 的 [BE Validity] 行。性能对比请在诊断关闭后进行。"), false);
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        Session session = active;
        if (session == null || session.server != source.getServer()) {
            source.sendFailure(Component.literal("当前服务器没有正在运行的方块实体合法性诊断。"));
            return 0;
        }
        finish(session, "command");
        source.sendSuccess(() -> Component.literal("方块实体合法性诊断已停止，汇总已写入 logs/latest.log。"), false);
        return 1;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        Session session = active;
        if (session != null && event.phase == TickEvent.Phase.END && session.server == event.getServer() &&
                ++session.completedTickEnds >= session.requestedTicks) {
            finish(session, "duration");
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        Session session = active;
        if (session != null && session.server == event.getServer()) {
            finish(session, "server_stopped");
        }
    }

    private static void finish(Session session, String reason) {
        active = null;
        session.report(reason);
    }

    public static final class Session {

        private final MinecraftServer server;
        private final int requestedTicks;
        private final long startedNanos = System.nanoTime();
        private final Object token = new Object();
        private final Map<BlockEntityType<?>, TypeStats> types = new IdentityHashMap<>();
        private int completedTickEnds;

        private Session(MinecraftServer server, int requestedTicks) {
            this.server = server;
            this.requestedTicks = requestedTicks;
        }

        public boolean accepts(ServerLevel level) {
            return level.getServer() == server && server.isSameThread();
        }

        public Tracker tracker(BlockEntityType<?> type, ResourceKey<Level> dimension) {
            TypeStats stats = types.computeIfAbsent(type, TypeStats::new);
            Counts counts = stats.dimensions.computeIfAbsent(dimension, ignored -> new Counts());
            counts.bindings++;
            return new Tracker(token, type, dimension, counts);
        }

        private void report(String reason) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            long checks = types.values().stream().flatMap(type -> type.dimensions.values().stream()).mapToLong(counts -> counts.checks).sum();
            GTLCore.LOGGER.info("[BE Validity] summary reason={} requested_ticks={} completed_tick_ends={} elapsed_ms={} types={} checks={}",
                    reason, requestedTicks, completedTickEnds, elapsedMs, types.size(), checks);
            for (var entry : types.entrySet()) {
                BlockEntityType<?> type = entry.getKey();
                TypeStats stats = entry.getValue();
                var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
                Set<Block> current = validBlocks(type);
                GTLCore.LOGGER.info("[BE Validity] type={} implementation={} set_class_first={} set_class_end={} size_first={} size_end={} " +
                        "guava_immutable_first={} guava_immutable_end={} set_reference_changed={} set_contents_changed={}",
                        id, type.getClass().getName(), stats.initialSet.getClass().getName(), current.getClass().getName(),
                        stats.initialMembers.size(), current.size(), stats.initialSet instanceof ImmutableSet<?>, current instanceof ImmutableSet<?>,
                        stats.initialSet != current, !stats.initialMembers.equals(current));
                for (var dimension : stats.dimensions.entrySet()) {
                    Counts counts = dimension.getValue();
                    GTLCore.LOGGER.info("[BE Validity] type={} dimension={} bindings_observed={} checks={} invalid={} same_state={} " +
                            "state_changes={} block_changes={} same_state_result_changes={}",
                            id, dimension.getKey().location(), counts.bindings, counts.checks, counts.invalid, counts.sameState,
                            counts.stateChanges, counts.blockChanges, counts.sameStateResultChanges);
                }
            }
            GTLCore.LOGGER.info("[BE Validity] end: set observations compare first encounter with end only; bindings are not unique machines; " +
                    "counts do not measure execution time or prove cache safety; the first tick may be partial.");
        }
    }

    /** Lives with a ticker binding, without retaining the diagnostic session, world, or block entity. */
    public static final class Tracker {

        private final Object token;
        private final BlockEntityType<?> type;
        private final ResourceKey<Level> dimension;
        private final Counts counts;
        private BlockState previousState;
        private boolean previousResult;

        private Tracker(Object token, BlockEntityType<?> type, ResourceKey<Level> dimension, Counts counts) {
            this.token = token;
            this.type = type;
            this.dimension = dimension;
            this.counts = counts;
        }

        public boolean matches(Session session, BlockEntityType<?> type, ResourceKey<Level> dimension) {
            return token == session.token && this.type == type && this.dimension == dimension;
        }

        public void record(BlockState state, boolean result) {
            counts.checks++;
            if (!result) counts.invalid++;
            if (previousState == state) {
                counts.sameState++;
                if (previousResult != result) counts.sameStateResultChanges++;
            } else if (previousState != null) {
                counts.stateChanges++;
                if (previousState.getBlock() != state.getBlock()) counts.blockChanges++;
            }
            previousState = state;
            previousResult = result;
        }
    }

    private static Set<Block> validBlocks(BlockEntityType<?> type) {
        return ((BlockEntityTypeValidityAccessor) type).gtlcore$getValidBlocks();
    }

    private static final class TypeStats {

        private final Set<Block> initialSet;
        private final Set<Block> initialMembers;
        private final Map<ResourceKey<Level>, Counts> dimensions = new HashMap<>();

        private TypeStats(BlockEntityType<?> type) {
            initialSet = validBlocks(type);
            initialMembers = new HashSet<>(initialSet);
        }
    }

    private static final class Counts {

        private long bindings;
        private long checks;
        private long invalid;
        private long sameState;
        private long stateChanges;
        private long blockChanges;
        private long sameStateResultChanges;
    }
}
