package org.gtlcore.gtlcore.common.item.manipulator;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.data.GTLItems;
import org.gtlcore.gtlcore.config.ConfigHolder;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import appeng.api.stacks.AEKey;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID)
public final class ManipulatorSavedData extends SavedData {

    private final Map<UUID, ManipulatorTask> tasks = new LinkedHashMap<>();
    private static ServerLevel captureLevel;
    private static BlockPos capturePos;
    private static boolean discardOre;
    private static Map<AEKey, Long> captured;

    public static ManipulatorSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(ManipulatorSavedData::load, ManipulatorSavedData::new, "gtlcore_manipulator");
    }

    private static ManipulatorSavedData load(CompoundTag tag) {
        var data = new ManipulatorSavedData();
        for (Tag t : tag.getList("Tasks", Tag.TAG_COMPOUND)) {
            var task = new ManipulatorTask(data, (CompoundTag) t);
            data.tasks.put(task.id, task);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        tasks.values().forEach(task -> list.add(task.save()));
        tag.put("Tasks", list);
        return tag;
    }

    public ManipulatorTask task(ServerPlayer player, ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        ManipulatorTask task = tag.hasUUID("ManipulatorTask") ? tasks.get(tag.getUUID("ManipulatorTask")) : null;
        return task != null && task.owner.equals(player.getUUID()) && task.toolId.equals(tag.getUUID("ManipulatorId")) ? task : null;
    }

    public void start(ServerPlayer player, ItemStack stack, int planMode) {
        CompoundTag tag = stack.getOrCreateTag();
        if (!tag.hasUUID("ManipulatorId")) tag.putUUID("ManipulatorId", UUID.randomUUID());
        ManipulatorTask previous = task(player, stack);
        if (previous != null && !previous.cancelled && !previous.finished() && planMode == 0) {
            previous.resume();
            return;
        }
        ManipulatorSettings settings = ManipulatorSettings.read(stack);
        try {
            validate(player, settings);
            if (previous != null) {
                previous.cancel();
                previous.refund(player, org.gtlcore.gtlcore.integration.ae2.manipulator.ManipulatorNetwork.grid(player, stack));
                if (!previous.escrow.isEmpty()) {
                    player.displayClientMessage(Component.translatable("mm.error.refund"), true);
                    return;
                }
                previous.disconnect();
                tasks.remove(previous.id);
            }
            var task = new ManipulatorTask(this, player, stack, settings, planMode);
            tasks.put(task.id, task);
            tag.putUUID("ManipulatorTask", task.id);
            setDirty();
        } catch (IllegalArgumentException | ArithmeticException exception) {
            player.displayClientMessage(Component.translatable(exception.getMessage()), true);
        }
    }

    private static void validate(ServerPlayer player, ManipulatorSettings s) {
        if (s.a == null || s.b == null || s.template() && s.anchor == null) throw new IllegalArgumentException("mm.error.selection");
        if (!s.dimension.equals(player.level().dimension().location().toString())) throw new IllegalArgumentException("mm.error.dimension");
        long count = s.volume();
        if (s.template()) for (int repeat : s.repeat) count = Math.multiplyExact(count, repeat);
        if (count > ConfigHolder.INSTANCE.manipulator.maxBlocks || count <= 0) throw new IllegalArgumentException("mm.error.size");
        if (s.template()) {
            for (int axis = 0; axis < 3; axis++) if (s.repeat[axis] > 1 && s.spacing[axis] < 0) throw new IllegalArgumentException("mm.error.overlap");
        }
        if (s.mode == ManipulatorSettings.Mode.MOVE) {
            for (int repeat : s.repeat) if (repeat != 1) throw new IllegalArgumentException("mm.error.move_array");
            BlockPos first = ManipulatorGeometry.target(BlockPos.ZERO, 0, s);
            BlockPos last = first.offset(ManipulatorGeometry.extent(s)).offset(-1, -1, -1);
            BlockPos sourceLast = s.min().offset(s.size()).offset(-1, -1, -1);
            if (first.getX() <= sourceLast.getX() && last.getX() >= s.min().getX() && first.getY() <= sourceLast.getY() && last.getY() >= s.min().getY() && first.getZ() <= sourceLast.getZ() && last.getZ() >= s.min().getZ()) throw new IllegalArgumentException("mm.error.overlap");
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        var data = get(server);
        long deadline = System.nanoTime() + ConfigHolder.INSTANCE.manipulator.tickBudgetMillis * 1_000_000L;
        for (var task : data.tasks.values()) {
            ServerPlayer player = server.getPlayerList().getPlayer(task.owner);
            if (player == null) {
                task.disconnect();
                continue;
            }
            ItemStack tool = findTool(player, task.toolId);
            if (tool.isEmpty()) {
                task.paused = true;
                task.disconnect();
                continue;
            }
            task.tick(player, tool, deadline);
            String status = task.message + " " + task.completed() + "/" + task.operationCount();
            if (task.failure != null) status += " @ " + task.failure.toShortString();
            if (!tool.getOrCreateTag().getString("ManipulatorStatus").equals(status)) {
                tool.getOrCreateTag().putString("ManipulatorStatus", status);
                player.getInventory().setChanged();
            }
        }
    }

    private static ItemStack findTool(ServerPlayer player, UUID id) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(GTLItems.MATTER_MANIPULATOR.get()) && stack.hasTag() && stack.getTag().hasUUID("ManipulatorId") && stack.getTag().getUUID("ManipulatorId").equals(id)) return stack;
        }
        return ItemStack.EMPTY;
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        var data = get(event.getServer());
        data.setDirty();
        data.tasks.values().forEach(ManipulatorTask::disconnect);
        endCapture();
    }

    public static void captureDrops(ServerLevel level, BlockPos pos, Map<AEKey, Long> output) {
        captureLevel = level;
        capturePos = pos;
        captured = output;
        discardOre = level.getBlockState(pos).is(Tags.Blocks.ORES);
    }

    public static void endCapture() {
        captureLevel = null;
        capturePos = null;
        captured = null;
    }

    @SubscribeEvent
    public static void drops(EntityJoinLevelEvent event) {
        if (captured == null || event.getLevel() != captureLevel ||
                !event.getEntity().blockPosition().equals(capturePos) && !ManipulatorUndo.capturing(captureLevel))
            return;
        if (event.getEntity() instanceof ItemEntity item) {
            boolean discard = ManipulatorUndo.capturing(captureLevel) ? ManipulatorUndo.discardDrops(captureLevel, item.blockPosition()) : discardOre;
            if (!discard) ManipulatorResources.add(captured, item.getItem());
            event.setCanceled(true);
        } else if (event.getEntity() instanceof ExperienceOrb) event.setCanceled(true);
    }
}
