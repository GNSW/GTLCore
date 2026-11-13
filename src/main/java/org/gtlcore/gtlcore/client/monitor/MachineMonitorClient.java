package org.gtlcore.gtlcore.client.monitor;

import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.*;
import net.minecraftforge.client.event.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;

import com.mojang.math.Axis;

import java.util.*;
import java.util.concurrent.TimeUnit;

public final class MachineMonitorClient {

    private static final Map<BlockPos, Display> DISPLAYS = new HashMap<>();
    private static final MachineMonitorIconRenderer BUFFERS = new MachineMonitorIconRenderer();

    private MachineMonitorClient() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(MachineMonitorClient::render);
        MinecraftForge.EVENT_BUS.addListener(MachineMonitorClient::unload);
        MinecraftForge.EVENT_BUS.addListener(MachineMonitorClient::logout);
    }

    public static void receive(ResourceLocation dimension, CompoundTag message, boolean open) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().location().equals(dimension)) return;
        var master = BlockPos.of(message.getLong("Master"));
        if (message.getBoolean("Remove")) {
            DISPLAYS.remove(master);
            return;
        }
        Display display;
        if (message.contains("Origin")) {
            display = new Display(message);
            DISPLAYS.put(master, display);
        } else {
            display = DISPLAYS.get(master);
            if (display == null) return;
            if (message.getCompound("State").getInt("ContentVersion") != display.state.getInt("ContentVersion")) return;
            display.update(message.getCompound("State"));
        }
        if (open) minecraft.setScreen(new MachineMonitorScreen(master, BlockPos.of(message.getLong("Clicked"))));
    }

    static Display display(BlockPos master) {
        return DISPLAYS.get(master);
    }

    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES || DISPLAYS.isEmpty()) return;
        var pose = event.getPoseStack();
        var camera = event.getCamera().getPosition();
        for (Display display : DISPLAYS.values()) {
            if (!event.getFrustum().isVisible(display.bounds)) continue;
            Vec3 normal = Vec3.atLowerCornerOf(display.facing.getNormal());
            if (camera.subtract(display.bounds.getCenter()).dot(normal) <= 0) continue;
            pose.pushPose();
            var start = display.origin.getCenter().add(normal.scale(.5));
            pose.translate(start.x - camera.x, start.y - camera.y, start.z - camera.z);
            pose.mulPose(Axis.YP.rotationDegrees(-display.facing.toYRot()));
            pose.translate(-.5, display.height - .5, 0);
            pose.scale(1, -1, 1);
            // One fixed-size layout irrespective of physical area; no offscreen pixel framebuffer.
            var canvas = new MachineMonitorView.WorldCanvas(pose, BUFFERS);
            canvas.rect(0, 0, display.width, display.height, MachineMonitorView.BORDER, MachineMonitorView.Layer.MARGIN);
            float scale = Math.min(display.width / MachineMonitorView.WIDTH, display.height / MachineMonitorView.HEIGHT);
            pose.translate((display.width - MachineMonitorView.WIDTH * scale) / 2, (display.height - MachineMonitorView.HEIGHT * scale) / 2, 0);
            pose.scale(scale, scale, 1);
            MachineMonitorView.draw(canvas, display);
            pose.popPose();
        }
        BUFFERS.endBatch();
    }

    private static void unload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) DISPLAYS.clear();
    }

    private static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        DISPLAYS.clear();
    }

    static final class Display {

        final BlockPos origin;
        final Direction facing;
        final int width;
        final int height;
        final AABB bounds;
        final List<MachineMonitorView.Row> inputs;
        final List<MachineMonitorView.Row> outputs;
        CompoundTag state;
        private double previousProgress;
        private long receivedAt;
        private long interpolationMillis;

        private Display(CompoundTag message) {
            origin = BlockPos.of(message.getLong("Origin"));
            facing = Direction.byName(message.getString("Facing"));
            width = message.getInt("Width");
            height = message.getInt("Height");
            var end = origin.relative(facing.getCounterClockWise(), width - 1).above(height - 1);
            bounds = new AABB(origin).minmax(new AABB(end)).inflate(MachineMonitorView.Layer.CONTENT.depth() + MachineMonitorView.LAYER_DEPTH);
            state = message.getCompound("State");
            var contents = message.getCompound("Contents");
            inputs = MachineMonitorView.rows(contents.getList("Inputs", Tag.TAG_COMPOUND));
            outputs = MachineMonitorView.rows(contents.getList("Outputs", Tag.TAG_COMPOUND));
        }

        private void update(CompoundTag next) {
            double current = progress(state), target = progress(next);
            boolean sameRecipe = state.getBoolean("HasRecipe") && next.getBoolean("HasRecipe") &&
                    state.getBoolean("Computation") == next.getBoolean("Computation") && total(state) == total(next);
            boolean working = "working".equals(next.getString("Status"));
            if (sameRecipe && working && "working".equals(state.getString("Status")) && target > current &&
                    next.getLong("SampleTick") > state.getLong("SampleTick")) {
                previousProgress = progress();
                receivedAt = Util.getMillis();
                interpolationMillis = TimeUnit.SECONDS.toMillis(next.getInt("SampleInterval")) / SharedConstants.TICKS_PER_SECOND;
            } else if (!sameRecipe || !working || target != current) {
                interpolationMillis = 0;
            }
            state = next;
        }

        double progress() {
            double target = progress(state);
            if (interpolationMillis == 0) return target;
            // Interpolate received samples only; never predict beyond the last server progress.
            double fraction = Math.min(1, (double) (Util.getMillis() - receivedAt) / interpolationMillis);
            return Mth.lerp(fraction, previousProgress, target);
        }

        double total() {
            return total(state);
        }

        private static double progress(CompoundTag state) {
            return state.getBoolean("Computation") ? state.getLong("CompletedCWU") : state.getInt("Progress");
        }

        private static double total(CompoundTag state) {
            return state.getBoolean("Computation") ? state.getLong("TotalCWU") : state.getInt("MaxProgress");
        }
    }
}
