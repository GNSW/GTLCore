package org.gtlcore.gtlcore.common.item.manipulator;

import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.manipulator.ManipulatorConnections;
import org.gtlcore.gtlcore.integration.ae2.manipulator.ManipulatorNetwork;
import org.gtlcore.gtlcore.integration.ae2.manipulator.ManipulatorPlanService;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ManipulatorTask {

    public final UUID id;
    public final UUID owner;
    public final UUID toolId;
    public final ManipulatorSettings settings;
    public final CompoundTag binding;
    public final Map<AEKey, Long> escrow = new LinkedHashMap<>();
    public final Map<AEKey, Long> remaining = new LinkedHashMap<>();
    public final List<ManipulatorSnapshot> template = new ArrayList<>();
    public final List<Operation> operations = new ArrayList<>();
    public final Set<BlockPos> targets = new HashSet<>();
    public final ManipulatorUndo undo;
    public boolean paused;
    public boolean autoOrder = true;
    public boolean cancelled;
    public int scan;
    public int cursor;
    public int planMode;
    public String message = "mm.status.scanning";
    public BlockPos failure;
    private final ManipulatorSavedData savedData;
    private final ManipulatorNetwork network;
    public final ManipulatorPlanService plans;
    private long quota;
    private boolean ready;
    public final Map<String, CompoundTag> p2pBindings = new LinkedHashMap<>();
    private int finishCursor;
    private int finishPhase;
    private long snapshotBytes;

    public ManipulatorTask(ManipulatorSavedData savedData, ServerPlayer player, ItemStack tool, ManipulatorSettings settings, int planMode) {
        this.savedData = savedData;
        this.id = UUID.randomUUID();
        this.owner = player.getUUID();
        this.toolId = tool.getOrCreateTag().getUUID("ManipulatorId");
        this.settings = settings;
        this.planMode = planMode;
        undo = new ManipulatorUndo(true);
        binding = tool.getOrCreateTag().getCompound("ManipulatorNetwork").copy();
        network = new ManipulatorNetwork(this, new CompoundTag());
        plans = new ManipulatorPlanService(this, network);
    }

    public ManipulatorTask(ManipulatorSavedData savedData, CompoundTag tag) {
        this.savedData = savedData;
        id = tag.getUUID("Id");
        owner = tag.getUUID("Owner");
        toolId = tag.getUUID("Tool");
        settings = ManipulatorSettings.read(tag.getCompound("Settings"));
        binding = tag.getCompound("Binding");
        escrow.putAll(ManipulatorResources.read(tag.getList("Escrow", Tag.TAG_COMPOUND)));
        remaining.putAll(ManipulatorResources.read(tag.getList("Remaining", Tag.TAG_COMPOUND)));
        List<ManipulatorSnapshot> palette = new ArrayList<>();
        for (Tag t : tag.getList("Palette", Tag.TAG_COMPOUND)) palette.add(ManipulatorSnapshot.read((CompoundTag) t));
        for (int index : tag.getIntArray("Template")) template.add(palette.get(index));
        for (Tag t : tag.getList("Operations", Tag.TAG_COMPOUND)) {
            Operation operation = Operation.read((CompoundTag) t, palette);
            operations.add(operation);
            targets.add(operation.target);
        }
        paused = true; // a restart never resumes world edits without a fresh use gesture.
        autoOrder = tag.getBoolean("AutoOrder");
        cancelled = tag.getBoolean("Cancelled");
        scan = tag.getInt("Scan");
        cursor = tag.getInt("Cursor");
        ready = tag.getBoolean("Ready");
        planMode = tag.getInt("PlanMode");
        finishCursor = tag.getInt("FinishCursor");
        finishPhase = tag.getInt("FinishPhase");
        snapshotBytes = tag.getLong("SnapshotBytes");
        undo = tag.contains("Undo") ? new ManipulatorUndo(tag.getCompound("Undo")) : new ManipulatorUndo(false);
        CompoundTag pairs = tag.getCompound("P2PBindings");
        for (String key : pairs.getAllKeys()) p2pBindings.put(key, pairs.getCompound(key));
        message = tag.getString("Message");
        if (tag.contains("Failure")) failure = BlockPos.of(tag.getLong("Failure"));
        network = new ManipulatorNetwork(this, tag.getCompound("Network"));
        plans = new ManipulatorPlanService(this, network);
        plans.load(tag.getCompound("Plans"));
        // Retire saved cable jobs through the normal cancellation/refund lifecycle.
        if (ManipulatorSettings.isRemovedMode(tag.getCompound("Settings"))) cancel();
    }

    public void changed() {
        savedData.setDirty();
    }

    public void status(String key) {
        if (!message.equals(key)) {
            message = key;
            changed();
        }
    }

    public void tick(ServerPlayer player, ItemStack tool, long deadline) {
        if (cancelled) {
            refund(player, binding.equals(tool.getOrCreateTag().getCompound("ManipulatorNetwork")) ? network.connect(player) : null);
            return;
        }
        if (!binding.equals(tool.getOrCreateTag().getCompound("ManipulatorNetwork"))) {
            paused = true;
            status("mm.error.binding_changed");
            network.close();
            return;
        }
        IGrid grid = network.connect(player);
        plans.tick(player, grid);
        boolean held = player.isUsingItem() && player.getUseItem() == tool && tool.getOrCreateTag().getBoolean("ManipulatorExecuted");
        if (!undo.active) network.tick(player, grid, held && !paused && ready ? remaining : Map.of());
        if (paused) return;
        if (!held && planMode == 0) {
            status("mm.status.paused");
            return;
        }
        if (!settings.dimension.equals(player.level().dimension().location().toString())) {
            status("mm.error.dimension");
            return;
        }
        ServerLevel level = player.serverLevel();
        var config = ConfigHolder.INSTANCE.manipulator;
        quota = Math.min(quota + config.operationsPerSecond, (long) config.operationsPerSecond * SharedConstants.TICKS_PER_SECOND);
        int allowance = (int) (quota / SharedConstants.TICKS_PER_SECOND);
        quota %= SharedConstants.TICKS_PER_SECOND;
        try {
            if (undo.active) {
                while (undo.available() && allowance-- > 0 && System.nanoTime() < deadline) {
                    if (!undo.step(this, player, tool, grid)) return;
                }
                if (!undo.available()) {
                    paused = true;
                    failure = null;
                    status("mm.status.undone");
                    refund(player, grid);
                }
                return;
            }
            int scanAllowance = config.operationsPerSecond;
            while (!ready && scanAllowance-- > 0 && System.nanoTime() < deadline) {
                if (!scan(level, player)) break;
            }
            if (!ready) return;
            if (planMode != 0) {
                if (!plans.publish(player, grid, planMode)) return;
                planMode = 0;
                paused = true;
                changed();
                return;
            }
            while (cursor < operations.size() && allowance-- > 0 && System.nanoTime() < deadline) {
                Operation operation = operations.get(cursor);
                failure = operation.target;
                if (!commit(level, player, tool, grid, operation)) return;
                for (var entry : operation.snapshot.cost().entrySet()) remaining.computeIfPresent(entry.getKey(), (k, n) -> Math.max(0, n - entry.getValue()));
                cursor++;
                changed();
            }
            if (cursor == operations.size()) {
                while (finishPhase < 2 && allowance-- > 0 && System.nanoTime() < deadline) {
                    if (finishCursor == operations.size()) {
                        finishPhase++;
                        finishCursor = 0;
                        continue;
                    }
                    if (!finish(player, operations.get(finishCursor))) return;
                    finishCursor++;
                    changed();
                }
                if (finishPhase < 2) return;
                status("mm.status.done");
                paused = true;
                refund(player, grid);
            }
        } catch (IllegalArgumentException | ArithmeticException exception) {
            paused = true;
            status(exception.getMessage());
            changed();
        } catch (IllegalStateException exception) {
            org.gtlcore.gtlcore.GTLCore.LOGGER.error("Manipulator {} stopped at {}", id, failure, exception);
            paused = true;
            status("mm.error.restore");
            changed();
        }
    }

    private boolean scan(ServerLevel level, ServerPlayer player) {
        long volume = settings.volume();
        if (settings.template() && template.size() < volume) {
            BlockPos pos = settings.min().offset(ManipulatorGeometry.local(template.size(), settings.size()));
            failure = pos;
            if (!loaded(level, pos)) return false;
            if (!allowed(level, player, pos)) {
                paused = true;
                status("mm.error.permission");
                return false;
            }
            var snapshot = ManipulatorSnapshot.capture(level, pos, player, settings);
            if (settings.mode == ManipulatorSettings.Mode.MOVE) {
                for (long proxy : snapshot.data().getLongArray("BufferProxies")) if (!loaded(level, BlockPos.of(proxy))) return false;
            }
            long bytes = Math.addExact(snapshotBytes, snapshot.save().sizeInBytes());
            if (bytes > ConfigHolder.INSTANCE.manipulator.maxSnapshotBytes) throw new IllegalArgumentException("mm.error.snapshot_size");
            template.add(snapshot);
            snapshotBytes = bytes;
            changed();
            return true;
        }
        long arrays = settings.template() ? (long) settings.repeat[0] * settings.repeat[1] * settings.repeat[2] : 1;
        if (scan >= volume * arrays) {
            ready = true;
            failure = null;
            changed();
            return true;
        }
        int array = (int) (scan / volume);
        int index = (int) (scan % volume);
        BlockPos local = ManipulatorGeometry.local(index, settings.size());
        BlockPos source = settings.min().offset(local);
        BlockPos target = settings.template() ? ManipulatorGeometry.target(local, array, settings) : source;
        failure = target;
        if (!loaded(level, target)) return false;
        if (!allowed(level, player, target)) {
            paused = true;
            status("mm.error.permission");
            return false;
        }
        ManipulatorSnapshot snapshot;
        if (settings.template()) snapshot = template.get(index);
        else {
            int material = settings.mode == ManipulatorSettings.Mode.REMOVE ? 3 : ManipulatorGeometry.material(local, settings);
            if (material < 0) {
                scan++;
                return true;
            }
            BlockState current = level.getBlockState(target);
            if (settings.mode == ManipulatorSettings.Mode.REPLACE && !(settings.whitelist.isEmpty() ? current.isAir() : settings.whitelist.contains(current))) {
                scan++;
                return true;
            }
            BlockState state = settings.mode == ManipulatorSettings.Mode.REMOVE ? Blocks.AIR.defaultBlockState() : settings.materials[material];
            snapshot = ManipulatorSnapshot.plain(state, state.isAir() ? ItemStack.EMPTY : new ItemStack(state.getBlock().asItem()));
        }
        BlockState transformed = settings.template() ? ManipulatorGeometry.state(snapshot.state(), settings) : snapshot.state();
        BlockState old = level.getBlockState(target);
        if (targets.contains(target)) throw new IllegalArgumentException("mm.error.overlap");
        // Full plans include already satisfied cells; missing plans and construction omit them.
        boolean satisfied = old == transformed && snapshot.data().isEmpty();
        if (!satisfied || planMode >= 3) {
            if (!old.isAir() && !transformed.isAir() && com.gregtechceu.gtceu.api.machine.MetaMachine.getMachine(level, target) instanceof org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase) throw new IllegalArgumentException("mm.error.buffer_replacement");
            if (!satisfied && !mayRemove(old)) {
                status("mm.error.obstructed");
                paused = true;
                return false;
            }
            if (!transformed.isAir() && snapshot.item().isEmpty() && !snapshot.data().getString("Kind").equals("parts")) throw new IllegalArgumentException("mm.error.item");
            if (settings.mode == ManipulatorSettings.Mode.MOVE && !snapshot.data().isEmpty()) {
                for (int i = 0; i < 3; i++) if (settings.rotation[i] != 0 || settings.mirror[i]) throw new IllegalArgumentException("mm.error.move_rotation");
            }
            Operation operation = new Operation(source, target, transformed, old, snapshot);
            operations.add(operation);
            if (settings.mode != ManipulatorSettings.Mode.MOVE) snapshot.cost().forEach((key, amount) -> ManipulatorResources.add(remaining, key, amount));
        }
        targets.add(target);
        scan++;
        changed();
        return true;
    }

    public long snapshotBytes() {
        return snapshotBytes;
    }

    public int completed() {
        return undo.active ? undo.completed() : cursor;
    }

    public int operationCount() {
        return undo.active ? undo.total : operations.size();
    }

    public boolean finished() {
        return undo.active ? !undo.available() : ready && cursor == operations.size() && finishPhase == 2;
    }

    public void requestUndo(ServerPlayer player) {
        if (!undo.available()) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(undo.enabled ? "mm.error.undo_empty" : "mm.error.undo_legacy"), true);
            return;
        }
        if (!undo.active) undo.total = undo.pendingCount();
        undo.active = true;
        cancelled = false;
        paused = true;
        autoOrder = false;
        planMode = 0;
        network.cancel();
        plans.clear();
        undo.requirements(remaining);
        status("mm.status.undo_ready");
        changed();
    }

    private boolean finish(ServerPlayer player, Operation operation) {
        ServerLevel level = player.serverLevel();
        var before = undo.capture(this, level, operation);
        if (undo.enabled && before == null) return false;
        boolean recorded = false;
        undo.beginCapture(this, level, before);
        try {
            boolean done = ManipulatorConnections.finish(this, operation, player, finishPhase);
            undo.record(this, level, before, Map.of(), Map.of());
            recorded = true;
            return done;
        } finally {
            undo.endCapture();
            if (!recorded && before != null) before.restore(level);
        }
    }

    boolean loaded(ServerLevel level, BlockPos pos) {
        if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)) throw new IllegalArgumentException("mm.error.bounds");
        if (!level.hasChunkAt(pos)) {
            status("mm.status.chunks");
            return false;
        }
        return true;
    }

    boolean allowed(ServerLevel level, ServerPlayer player, BlockPos pos) {
        return !player.isSpectator() && player.mayBuild() && level.mayInteract(player, pos) &&
                level.getBlockState(pos).getDestroySpeed(level, pos) >= 0;
    }

    private boolean mayRemove(BlockState old) {
        return old.isAir() || settings.removal == ManipulatorSettings.Removal.ALL ||
                settings.removal == ManipulatorSettings.Removal.REPLACEABLE && old.canBeReplaced();
    }

    private boolean commit(ServerLevel level, ServerPlayer player, ItemStack tool, IGrid grid, Operation op) {
        if (!loaded(level, op.target)) return false;
        if (!allowed(level, player, op.target)) {
            paused = true;
            status("mm.error.permission");
            return false;
        }
        if (settings.mode != ManipulatorSettings.Mode.MOVE && level.getBlockState(op.target) == op.state && op.snapshot.data().isEmpty()) return true;
        if (level.getBlockState(op.target) != op.expected) {
            paused = true;
            status("mm.error.conflict");
            return false;
        }
        boolean moving = settings.mode == ManipulatorSettings.Mode.MOVE;
        if (undo.enabled && op.expected.hasBlockEntity()) {
            // This adapter already rejects direct replacement: removal can refund into an unrelated network.
            if (com.gregtechceu.gtceu.api.machine.MetaMachine.getMachine(level, op.target) instanceof org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase) throw new IllegalArgumentException("mm.error.undo_adapter");
            var captureSettings = ManipulatorSettings.read(settings.save());
            captureSettings.mode = ManipulatorSettings.Mode.MOVE;
            ManipulatorSnapshot.capture(level, op.target, player, captureSettings);
        }
        if (moving) {
            if (!loaded(level, op.source) || !allowed(level, player, op.source)) return false;
            if (level.getBlockState(op.source) != op.snapshot.state()) {
                paused = true;
                status("mm.error.source_changed");
                return false;
            }
            BlockEntity source = level.getBlockEntity(op.source);
            if (source != null && !source.saveWithFullMetadata().equals(op.snapshot.data().getCompound("Persistent"))) {
                paused = true;
                status("mm.error.source_changed");
                return false;
            }
            if (ForgeHooks.onBlockBreakEvent(level, player.gameMode.getGameModeForPlayer(), player, op.source) < 0) {
                paused = true;
                status("mm.error.permission");
                return false;
            }
        }
        if (!moving) {
            for (var entry : op.snapshot.cost().entrySet()) {
                long needed = entry.getValue() - escrow.getOrDefault(entry.getKey(), 0L);
                if (needed <= 0) continue;
                long taken = ManipulatorResources.extract(player, grid, entry.getKey(), needed, false);
                ManipulatorResources.add(escrow, entry.getKey(), taken);
                changed();
                if (taken < needed) {
                    status(grid == null ? "mm.status.materials_offline" : "mm.status.materials");
                    return false;
                }
            }
        }
        var undoBefore = undo.capture(this, level, op);
        if (undo.enabled && undoBefore == null) return false;
        long eu = energy(level, player, op.target, op.expected, op.state);
        if (!player.isCreative() && !ManipulatorNetwork.charge(tool, grid, eu)) {
            status("mm.status.energy");
            return false;
        }
        CompoundTag previous = level.getBlockEntity(op.target) == null ? null : level.getBlockEntity(op.target).saveWithFullMetadata();
        BlockSnapshot before = BlockSnapshot.create(level.dimension(), level, op.target);
        Map<AEKey, Long> recovered = new LinkedHashMap<>();
        boolean success = false;
        ManipulatorSavedData.captureDrops(level, op.target, recovered);
        undo.beginCapture(this, level, undoBefore);
        try {
            if (!op.expected.isAir() && !player.gameMode.destroyBlock(op.target)) {
                status("mm.error.permission");
                return false;
            }
            if (!place(level, player, op, moving)) {
                status("mm.error.placement");
                return false;
            }
            if (!op.state.isAir() && ForgeEventFactory.onBlockPlace(player, before, net.minecraft.core.Direction.UP)) {
                status("mm.error.permission");
                return false;
            }
            op.snapshot.restore(level, op.target, player, settings);
            if (moving) {
                // Detach the original entity before removing its block: inventories must not drop twice.
                level.removeBlockEntity(op.source);
                if (!level.setBlock(op.source, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL) && !level.getBlockState(op.source).isAir()) throw new IllegalStateException("Move source removal failed");
            }
            ManipulatorConnections.initializeCore(level, op.target, player);
            undo.record(this, level, undoBefore, moving ? Map.of() : op.snapshot.cost(), recovered);
            success = true;
        } finally {
            undo.endCapture();
            ManipulatorSavedData.endCapture();
            if (!success) {
                if (undoBefore != null) undoBefore.restore(level);
                else {
                    restore(level, op.target, op.expected, previous);
                    if (moving) restore(level, op.source, op.snapshot.state(), op.snapshot.data().contains("Persistent") ? op.snapshot.data().getCompound("Persistent") : null);
                }
                if (!player.isCreative()) ManipulatorNetwork.refund(tool, eu);
                paused = true;
                changed();
            }
        }
        if (!moving) op.snapshot.cost().forEach((key, amount) -> escrow.computeIfPresent(key, (k, n) -> n - amount));
        recovered.forEach((key, amount) -> ManipulatorResources.add(escrow, key, amount));
        refundSurplus(player, grid);
        status("mm.status.running");
        return true;
    }

    long energy(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState before, BlockState after) {
        float hardness = Math.max(before.getDestroySpeed(level, pos), after.getDestroySpeed(level, pos));
        double distance = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        var config = ConfigHolder.INSTANCE.manipulator;
        double energy = config.baseEU * Math.max(1, hardness) * (before.hasBlockEntity() || after.hasBlockEntity() ? 16 : 1) *
                (1 + distance / ((double) config.distanceScale * config.distanceScale)) * 0.5;
        if (!Double.isFinite(energy) || energy >= Long.MAX_VALUE) throw new IllegalArgumentException("mm.error.energy_overflow");
        return (long) Math.ceil(energy);
    }

    private boolean place(ServerLevel level, ServerPlayer player, Operation op, boolean moving) {
        if (moving || op.state.isAir()) return level.setBlock(op.target, op.state, Block.UPDATE_ALL) || level.getBlockState(op.target) == op.state;
        if (op.snapshot.data().getString("Kind").equals("parts")) return appeng.api.parts.PartHelper.getOrPlacePartHost(level, op.target, false, player) != null;
        ItemStack placement = op.snapshot.item().copy();
        var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(op.target), net.minecraft.core.Direction.UP, op.target, false);
        var context = new net.minecraft.world.item.context.UseOnContext(level, player, net.minecraft.world.InteractionHand.MAIN_HAND, placement, hit);
        if (!placement.useOn(context).consumesAction() || level.getBlockState(op.target).getBlock() != op.state.getBlock()) return false;
        return level.setBlock(op.target, op.state, Block.UPDATE_ALL) || level.getBlockState(op.target) == op.state;
    }

    private static void restore(ServerLevel level, BlockPos pos, BlockState state, CompoundTag tag) {
        level.removeBlockEntity(pos);
        level.setBlock(pos, state, Block.UPDATE_ALL);
        if (tag != null) {
            level.removeBlockEntity(pos);
            BlockEntity be = BlockEntity.loadStatic(pos, state, tag);
            if (be == null) throw new IllegalStateException("Unable to restore manipulator commit at " + pos);
            level.setBlockEntity(be);
        }
    }

    void refundSurplus(ServerPlayer player, IGrid grid) {
        for (var entry : new ArrayList<>(escrow.entrySet())) {
            long excess = entry.getValue() - remaining.getOrDefault(entry.getKey(), 0L);
            if (excess <= 0) continue;
            long remainder = ManipulatorResources.insert(player, grid, entry.getKey(), excess, player.blockPosition());
            escrow.put(entry.getKey(), entry.getValue() - excess + remainder);
            changed();
        }
    }

    public void refund(ServerPlayer player, IGrid grid) {
        for (var entry : new ArrayList<>(escrow.entrySet())) {
            long remainder = ManipulatorResources.insert(player, grid, entry.getKey(), entry.getValue(), player.blockPosition());
            if (remainder == 0) escrow.remove(entry.getKey());
            else escrow.put(entry.getKey(), remainder);
        }
        changed();
    }

    public void resume() {
        paused = false;
        autoOrder = !undo.active;
        network.retry();
        status(undo.active ? "mm.status.undoing" : "mm.status.scanning");
        changed();
    }

    public void cancel() {
        paused = true;
        cancelled = true;
        network.cancel();
        plans.clear();
        status(undo.active ? "mm.status.undo_cancelled" : "mm.status.cancelled");
        changed();
    }

    public void cancelOrders() {
        autoOrder = false;
        plans.cancelOrders();
        changed();
    }

    public void disconnect() {
        network.close();
        plans.disconnect();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Owner", owner);
        tag.putUUID("Tool", toolId);
        tag.put("Settings", settings.save());
        tag.put("Binding", binding);
        tag.put("Escrow", ManipulatorResources.save(escrow));
        tag.put("Remaining", ManipulatorResources.save(remaining));
        ListTag palette = new ListTag();
        Map<CompoundTag, Integer> indexes = new java.util.HashMap<>();
        java.util.function.ToIntFunction<ManipulatorSnapshot> index = snapshot -> {
            CompoundTag value = snapshot.save();
            Integer existing = indexes.get(value);
            if (existing != null) return existing;
            int next = palette.size();
            indexes.put(value, next);
            palette.add(value);
            return next;
        };
        tag.putIntArray("Template", template.stream().mapToInt(index).toArray());
        ListTag ops = new ListTag();
        operations.forEach(op -> ops.add(op.save(index.applyAsInt(op.snapshot))));
        tag.put("Palette", palette);
        tag.put("Operations", ops);
        tag.putBoolean("AutoOrder", autoOrder);
        tag.putBoolean("Cancelled", cancelled);
        tag.putBoolean("Ready", ready);
        tag.putInt("Scan", scan);
        tag.putInt("Cursor", cursor);
        tag.putInt("PlanMode", planMode);
        tag.putInt("FinishCursor", finishCursor);
        tag.putInt("FinishPhase", finishPhase);
        tag.putLong("SnapshotBytes", snapshotBytes);
        CompoundTag pairs = new CompoundTag();
        p2pBindings.forEach(pairs::put);
        tag.put("P2PBindings", pairs);
        tag.putString("Message", message);
        if (failure != null) tag.putLong("Failure", failure.asLong());
        tag.put("Network", network.save());
        tag.put("Plans", plans.save());
        tag.put("Undo", undo.save());
        return tag;
    }

    public record Operation(BlockPos source, BlockPos target, BlockState state, BlockState expected, ManipulatorSnapshot snapshot) {

        public CompoundTag save(int snapshotIndex) {
            CompoundTag tag = new CompoundTag();
            tag.putLong("Source", source.asLong());
            tag.putLong("Target", target.asLong());
            tag.put("State", NbtUtils.writeBlockState(state));
            tag.put("Expected", NbtUtils.writeBlockState(expected));
            tag.putInt("SnapshotIndex", snapshotIndex);
            return tag;
        }

        public static Operation read(CompoundTag tag, List<ManipulatorSnapshot> palette) {
            return new Operation(BlockPos.of(tag.getLong("Source")), BlockPos.of(tag.getLong("Target")),
                    NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound("State")),
                    NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound("Expected")), palette.get(tag.getInt("SnapshotIndex")));
        }
    }
}
