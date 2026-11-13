package org.gtlcore.gtlcore.integration.monitor;

import org.gtlcore.gtlcore.config.ConfigHolder;

import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.*;

import java.util.*;

/** Server-thread-only screen topology and subscriptions. Screen blocks never tick individually. */
public final class MachineMonitorRuntime {

    public static final int SCREEN_WIDTH = 4;
    public static final int SCREEN_HEIGHT = 3;
    private static final Map<ServerLevel, MachineMonitorRuntime> LEVELS = new IdentityHashMap<>();
    private final ServerLevel level;
    private final Set<BlockPos> pending = new LinkedHashSet<>();
    private final Map<BlockPos, Display> displays = new HashMap<>();
    private final Map<Long, Set<BlockPos>> chunkScreens = new HashMap<>();
    private final Map<Long, Set<BlockPos>> waitingChunks = new HashMap<>();
    private final Map<UUID, Set<Long>> watched = new HashMap<>();
    private Build build;

    private MachineMonitorRuntime(ServerLevel level) {
        this.level = level;
    }

    public static MachineMonitorRuntime get(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, MachineMonitorRuntime::new);
    }

    public static void register() {
        var bus = MinecraftForge.EVENT_BUS;
        bus.addListener(MachineMonitorRuntime::tick);
        bus.addListener(MachineMonitorRuntime::watch);
        bus.addListener(MachineMonitorRuntime::unwatch);
        bus.addListener(MachineMonitorRuntime::chunkLoad);
        bus.addListener(MachineMonitorRuntime::levelUnload);
        bus.addListener(MachineMonitorRuntime::logout);
    }

    public void changed(BlockPos pos) {
        var entity = panel(pos);
        if (entity != null && entity.master() != null) remove(entity.master());
        if (build != null) {
            pending.add(build.seed);
            build = null;
        }
        pending.add(pos.immutable());
        for (Direction side : Direction.values()) pending.add(pos.relative(side));
    }

    public void unloaded(BlockPos pos, BlockPos master) {
        if (master != null) {
            remove(master);
            waitingChunks.computeIfAbsent(ChunkPos.asLong(pos), key -> new HashSet<>()).add(master);
            pending.add(master);
        }
        if (build != null && build.members.contains(pos)) {
            pending.add(build.seed);
            build = null;
        }
    }

    private MachineMonitorBlockEntity panel(BlockPos pos) {
        return level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof MachineMonitorBlockEntity panel ? panel : null;
    }

    public MachineMonitorBlockEntity controller(MachineMonitorBlockEntity panel) {
        BlockPos master = panel.master();
        return master != null && displays.containsKey(master) ? panel(master) : null;
    }

    public boolean mayEdit(ServerPlayer player, BlockPos pos) {
        return player.mayBuild() && level.mayInteract(player, pos);
    }

    public void refresh(BlockPos master) {
        var display = displays.get(master);
        if (display != null) {
            display.sampler.invalidate();
            display.forceFull = true;
            display.update();
        }
    }

    public void open(ServerPlayer player, MachineMonitorBlockEntity panel) {
        var master = controller(panel);
        if (master == null) {
            player.displayClientMessage(panel.structureMessage(), true);
            return;
        }
        var display = displays.get(master.getBlockPos());
        display.update();
        var message = display.full();
        message.putLong("Clicked", panel.getBlockPos().asLong());
        MachineMonitorPackets.send(player, level.dimension().location(), message, true);
    }

    public void action(ServerPlayer player, BlockPos clicked, int page, boolean unlink) {
        // Reach is checked against the block the player opened, rather than the elected master.
        double reach = player.getBlockReach() + 1;
        if (player.distanceToSqr(clicked.getCenter()) > reach * reach || !level.mayInteract(player, clicked)) return;
        var panel = panel(clicked);
        if (panel == null) return;
        var master = controller(panel);
        if (master == null) return;
        var display = displays.get(master.getBlockPos());
        if (unlink) {
            if (mayEdit(player, master.getBlockPos()) && player.mayBuild()) master.bind(new CompoundTag());
        } else {
            int rows = Math.max(display.sampler.contents().getList("Inputs", net.minecraft.nbt.Tag.TAG_COMPOUND).size(),
                    display.sampler.contents().getList("Outputs", net.minecraft.nbt.Tag.TAG_COMPOUND).size());
            display.page = Math.max(0, Math.min(page, Math.max(0, (rows - 1) / MachineMonitorPackets.ROWS_PER_PAGE)));
            display.state.putInt("Page", display.page);
            display.broadcast(false);
        }
    }

    private void remove(BlockPos master) {
        Display previous = displays.remove(master);
        if (previous == null) return;
        for (long chunk : previous.chunks) {
            var entries = chunkScreens.get(chunk);
            entries.remove(master);
            if (entries.isEmpty()) chunkScreens.remove(chunk);
        }
        var message = new CompoundTag();
        message.putLong("Master", master.asLong());
        message.putBoolean("Remove", true);
        for (var player : previous.receivers()) MachineMonitorPackets.send(player, level.dimension().location(), message, false);
    }

    private void process() {
        int budget = ConfigHolder.INSTANCE.performance.machineMonitorStructureBlocksPerTick;
        while (budget-- > 0) {
            if (build == null) {
                if (pending.isEmpty()) break;
                var iterator = pending.iterator();
                var seed = iterator.next();
                iterator.remove();
                var panel = panel(seed);
                if (panel == null) continue;
                if (panel.master() != null) remove(panel.master());
                build = new Build(seed, panel.getBlockState().getValue(MachineMonitorBlock.FACING));
            }
            if (build.step()) build = null;
        }
        if (level.getGameTime() % updateInterval() == 0) {
            for (Display display : displays.values()) if (!display.receivers().isEmpty()) display.update();
        }
    }

    private static int updateInterval() {
        return Math.max(1, net.minecraft.SharedConstants.TICKS_PER_SECOND / ConfigHolder.INSTANCE.performance.machineMonitorUpdatesPerSecond);
    }

    private static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel level) {
            var runtime = LEVELS.get(level);
            if (runtime != null) runtime.process();
        }
    }

    private static void watch(ChunkWatchEvent.Watch event) {
        var runtime = get(event.getLevel());
        runtime.watched.computeIfAbsent(event.getPlayer().getUUID(), key -> new HashSet<>()).add(event.getPos().toLong());
        for (BlockPos pos : runtime.chunkScreens.getOrDefault(event.getPos().toLong(), Set.of())) {
            var display = runtime.displays.get(pos);
            display.update();
            MachineMonitorPackets.send(event.getPlayer(), event.getLevel().dimension().location(), display.full(), false);
        }
    }

    private static void unwatch(ChunkWatchEvent.UnWatch event) {
        var runtime = LEVELS.get(event.getLevel());
        if (runtime == null) return;
        var chunks = runtime.watched.get(event.getPlayer().getUUID());
        if (chunks == null) return;
        chunks.remove(event.getPos().toLong());
        for (BlockPos pos : runtime.chunkScreens.getOrDefault(event.getPos().toLong(), Set.of())) {
            var display = runtime.displays.get(pos);
            if (Collections.disjoint(chunks, display.chunks)) {
                var message = new CompoundTag();
                message.putLong("Master", pos.asLong());
                message.putBoolean("Remove", true);
                MachineMonitorPackets.send(event.getPlayer(), event.getLevel().dimension().location(), message, false);
            }
        }
        if (chunks.isEmpty()) runtime.watched.remove(event.getPlayer().getUUID());
    }

    private static void chunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // Enqueue only; chunks may not yet be FULL during this event.
        level.getServer().execute(() -> {
            var runtime = LEVELS.get(level);
            if (runtime == null) return;
            var seeds = runtime.waitingChunks.remove(event.getChunk().getPos().toLong());
            if (seeds != null) runtime.pending.addAll(seeds);
        });
    }

    private static void levelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) LEVELS.remove(level);
    }

    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        for (var runtime : LEVELS.values()) runtime.watched.remove(event.getEntity().getUUID());
    }

    private final class Build {

        private final BlockPos seed;
        private final Direction facing;
        private final Direction right;
        private final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        private final Set<BlockPos> seen = new HashSet<>();
        private final Set<BlockPos> members = new LinkedHashSet<>();
        private final Set<Long> missing = new HashSet<>();
        private BlockPos boundMaster;
        private boolean mixedFacing;
        private int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        private Iterator<BlockPos> assigning;
        private String status;
        private BlockPos master;

        private Build(BlockPos seed, Direction facing) {
            this.seed = seed;
            this.facing = facing;
            right = facing.getCounterClockWise();
            queue.add(seed);
            seen.add(seed);
        }

        private int x(BlockPos pos) {
            return pos.getX() * right.getStepX() + pos.getZ() * right.getStepZ();
        }

        private boolean step() {
            if (assigning != null) {
                if (assigning.hasNext()) {
                    var panel = panel(assigning.next());
                    if (panel != null) panel.assign(master, status);
                    return false;
                }
                if (status.equals("formed")) {
                    int width = maxX - minX + 1, height = maxY - minY + 1;
                    BlockPos origin = seed.relative(right, minX - x(seed)).atY(minY);
                    var display = new Display(master, origin, facing, width, height);
                    displays.put(master, display);
                    for (long chunk : display.chunks) chunkScreens.computeIfAbsent(chunk, key -> new HashSet<>()).add(master);
                    display.update();
                }
                return true;
            }
            if (queue.isEmpty()) {
                long width = (long) maxX - minX + 1, height = (long) maxY - minY + 1;
                status = !missing.isEmpty() ? "unloaded" : mixedFacing ? "mixed_facing" :
                        width != SCREEN_WIDTH || height != SCREEN_HEIGHT ? "wrong_size" :
                                width * height != members.size() ? "incomplete" : "formed";
                if (status.equals("formed")) {
                    // Prefer the existing binding owner, including a remapped legacy controller.
                    master = boundMaster != null ? boundMaster : seed.relative(right, minX - x(seed)).atY(minY);
                }
                for (long chunk : missing) waitingChunks.computeIfAbsent(chunk, key -> new HashSet<>()).add(seed);
                assigning = members.iterator();
                return false;
            }
            BlockPos pos = queue.removeFirst();
            if (!level.hasChunkAt(pos)) {
                missing.add(ChunkPos.asLong(pos));
                return false;
            }
            var panel = panel(pos);
            if (panel == null) return false;
            pending.remove(pos);
            members.add(pos);
            if (panel.master() != null) remove(panel.master());
            if (panel.getBlockState().getValue(MachineMonitorBlock.FACING) != facing) mixedFacing = true;
            if (!panel.target().isEmpty() && (boundMaster == null || pos.compareTo(boundMaster) < 0)) boundMaster = pos;
            minX = Math.min(minX, x(pos));
            maxX = Math.max(maxX, x(pos));
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
            for (Direction direction : new Direction[] { right, right.getOpposite(), Direction.UP, Direction.DOWN }) {
                var next = pos.relative(direction);
                if (!level.isOutsideBuildHeight(next) && seen.add(next)) queue.addLast(next);
            }
            return false;
        }
    }

    private final class Display {

        private final BlockPos master;
        private final CompoundTag geometry = new CompoundTag();
        private final Set<Long> chunks = new HashSet<>();
        private final MachineMonitorData sampler = new MachineMonitorData();
        private CompoundTag state = new CompoundTag();
        private int page;
        private boolean forceFull = true;
        private long sampledAt;

        private Display(BlockPos master, BlockPos origin, Direction facing, int width, int height) {
            this.master = master;
            geometry.putLong("Master", master.asLong());
            geometry.putLong("Origin", origin.asLong());
            geometry.putInt("Width", width);
            geometry.putInt("Height", height);
            geometry.putString("Facing", facing.getName());
            var right = facing.getCounterClockWise();
            for (int x = 0; x < width; x++) chunks.add(ChunkPos.asLong(origin.relative(right, x)));
        }

        private List<ServerPlayer> receivers() {
            List<ServerPlayer> players = new ArrayList<>();
            for (var player : level.players()) {
                var tracking = watched.get(player.getUUID());
                if (tracking != null && !Collections.disjoint(tracking, chunks)) players.add(player);
            }
            return players;
        }

        private void update() {
            var controller = panel(master);
            if (controller == null) return;
            int oldVersion = state.getInt("ContentVersion");
            CompoundTag next = sampler.sample(level, controller.target());
            sampledAt = level.getGameTime();
            if (oldVersion != next.getInt("ContentVersion")) page = 0;
            next.putInt("Page", page);
            boolean changed = !next.equals(state);
            state = next;
            if (forceFull || changed) broadcast(forceFull || oldVersion != state.getInt("ContentVersion"));
            forceFull = false;
        }

        private CompoundTag full() {
            var result = geometry.copy();
            result.put("State", snapshotState());
            result.put("Contents", sampler.contents().copy());
            return result;
        }

        private CompoundTag snapshotState() {
            var result = state.copy();
            result.putLong("SampleTick", sampledAt);
            result.putInt("SampleInterval", updateInterval());
            return result;
        }

        private void broadcast(boolean full) {
            var message = full ? full() : new CompoundTag();
            if (!full) {
                message.putLong("Master", master.asLong());
                message.put("State", snapshotState());
            }
            for (var receiver : receivers()) MachineMonitorPackets.send(receiver, level.dimension().location(), message, false);
        }
    }
}
