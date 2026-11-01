package org.gtlcore.gtlcore.client.socketpreview;

import org.gtlcore.gtlcore.GTLCore;

import com.gregtechceu.gtceu.api.block.IMachineBlock;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-side socket overlay.
 *
 * <p>
 * Only the single multiblock controller under the player's crosshair is rendered. A small area
 * is drawn: a 24 block render radius and a roughly 90 degree view cone, capped at
 * {@link #MAX_BOXES} nearest boxes. Controller discovery still uses a wider 13x13 chunk area so a
 * large multiblock whose controller is farther away can still be resolved from a casing block;
 * the discovery itself is invisible and only runs every 20 ticks. A box rebuild runs every 4
 * ticks. Only block positions that are loaded, non-air and have at least one air neighbour are
 * considered, so a formed shell produces a readable set of sockets instead of the whole structure
 * volume.
 */
public final class SocketPreview {

    private static final int SCAN_RADIUS_CHUNKS = 6;
    private static final double CONTROLLER_RANGE_SQ = 96.0D * 96.0D;
    private static final double RENDER_RANGE_SQ = 24.0D * 24.0D;
    private static final int CONTROLLER_REFRESH_TICKS = 20;
    private static final int BOX_REFRESH_TICKS = 4;
    private static final int MAX_BOXES = 512;
    private static final int SECTION_RADIUS = 2;
    private static final double RAY_RANGE = 64.0D;
    private static final double MIN_FACING_DOT = 0.7D;
    private static final float ALPHA = 0.9F;

    private static final float AVAILABLE_R = 0.20F;
    private static final float AVAILABLE_G = 0.90F;
    private static final float AVAILABLE_B = 1.00F;
    private static final float OCCUPIED_R = 1.00F;
    private static final float OCCUPIED_G = 0.65F;
    private static final float OCCUPIED_B = 0.15F;

    private static final int[][] NEIGHBOURS = {
            { 1, 0, 0 }, { -1, 0, 0 },
            { 0, 1, 0 }, { 0, -1, 0 },
            { 0, 0, 1 }, { 0, 0, -1 }
    };

    private static final List<MultiblockControllerMachine> CONTROLLERS = new ArrayList<>();
    private static final List<Box> BOXES = new ArrayList<>();

    private static ClientLevel lastLevel;
    private static Item lastItem;
    private static MultiblockControllerMachine target;
    private static int tick;
    private static boolean errorLogged;

    private static BlockPos lastScanPos;
    private static int lastScanTick;

    private static Vec3 lastSelectEye;
    private static Vec3 lastSelectLook;
    private static MultiblockControllerMachine lastSelectTarget;
    private static boolean lastSelectResolved;
    private static int lastSelectTick;

    private static BlockPos lastResolvedLookPos;
    private static MultiblockControllerMachine lastResolvedController;
    private static boolean lastResolvedValid;

    private static Vec3 lastBuildEye;
    private static Vec3 lastBuildLook;
    private static int lastBuildTick;

    private SocketPreview() {}

    public static void tick() {
        try {
            tickUnsafe();
        } catch (Throwable failure) {
            if (!errorLogged) {
                errorLogged = true;
                GTLCore.LOGGER.error("Socket preview scan failed; the overlay is now idle", failure);
            }
            clear();
        }
    }

    private static void tickUnsafe() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft == null ? null : minecraft.player;
        ClientLevel level = minecraft == null ? null : minecraft.level;
        if (player == null || level == null) {
            clear();
            return;
        }
        if (level != lastLevel) {
            CONTROLLERS.clear();
            BOXES.clear();
            lastItem = null;
            target = null;
            lastLevel = level;
            resetCaches();
        }

        ItemStack held = player.getMainHandItem();
        MetaMachineItem part = PartItems.partMachine(held);
        if (part == null) {
            held = player.getOffhandItem();
            part = PartItems.partMachine(held);
        }
        if (part == null) {
            BOXES.clear();
            lastItem = null;
            target = null;
            return;
        }

        tick++;
        boolean itemChanged = part != lastItem;
        lastItem = part;
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);

        boolean needScan = itemChanged;
        if (!needScan) {
            boolean moved = lastScanPos == null ||
                    player.distanceToSqr(lastScanPos.getX() + 0.5D,
                            lastScanPos.getY() + 0.5D,
                            lastScanPos.getZ() + 0.5D) > 64.0D;
            boolean invalidTarget = target != null && target.isInValid();
            int interval = target == null ? CONTROLLER_REFRESH_TICKS : 60;
            needScan = moved || invalidTarget || tick - lastScanTick >= interval;
        }
        if (needScan) {
            refreshControllers(level, player);
            lastScanPos = player.blockPosition();
            lastScanTick = tick;
            lastResolvedValid = false;
            lastSelectResolved = false;
        }

        MultiblockControllerMachine selected = selectController(level, player, eye, look);
        if (selected == null) {
            BOXES.clear();
            target = null;
            return;
        }
        boolean targetChanged = selected != target;
        target = selected;

        boolean viewChanged = !sameView(lastBuildEye, lastBuildLook, eye, look);
        int sinceBuild = tick - lastBuildTick;
        if (itemChanged || targetChanged || sinceBuild >= (viewChanged ? BOX_REFRESH_TICKS : 10)) {
            rebuildBoxes(level, player, part, selected, eye, look);
            lastBuildEye = eye;
            lastBuildLook = look;
            lastBuildTick = tick;
        }
    }

    private static void resetCaches() {
        lastScanPos = null;
        lastScanTick = 0;
        lastSelectEye = null;
        lastSelectLook = null;
        lastSelectTarget = null;
        lastSelectResolved = false;
        lastSelectTick = 0;
        lastResolvedLookPos = null;
        lastResolvedController = null;
        lastResolvedValid = false;
        lastBuildEye = null;
        lastBuildLook = null;
        lastBuildTick = 0;
    }

    private static boolean sameView(Vec3 aEye, Vec3 aLook, Vec3 bEye, Vec3 bLook) {
        if (aEye == null || aLook == null || bEye == null || bLook == null) {
            return false;
        }
        double dx = aEye.x - bEye.x;
        double dy = aEye.y - bEye.y;
        double dz = aEye.z - bEye.z;
        if (dx * dx + dy * dy + dz * dz > 0.25D) {
            return false;
        }
        double dot = aLook.x * bLook.x + aLook.y * bLook.y + aLook.z * bLook.z;
        return dot > 0.9995D;
    }

    public static void render(RenderLevelStageEvent event) {
        try {
            renderUnsafe(event);
        } catch (Throwable failure) {
            if (!errorLogged) {
                errorLogged = true;
                GTLCore.LOGGER.error("Socket preview rendering failed; the overlay is now idle", failure);
            }
            BOXES.clear();
        }
    }

    private static void renderUnsafe(RenderLevelStageEvent event) {
        if (BOXES.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        RenderType type = RenderType.lines();
        VertexConsumer lines = buffers.getBuffer(type);

        // RenderLevelStageEvent hands out the level renderer's pose stack, which is still at
        // world origin (block entities translate by pos - camera themselves). Move it to the
        // camera before drawing absolute world-space AABBs.
        poseStack.pushPose();
        boolean lineWidthSet = false;
        try {
            Vec3 camera = event.getCamera().getPosition();
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            try {
                RenderSystem.lineWidth(2.0F);
                lineWidthSet = true;
            } catch (Throwable ignored) {
                // Some drivers only support 1px lines.
            }
            for (Box box : BOXES) {
                LevelRenderer.renderLineBox(poseStack, lines, box.aabb(),
                        box.r(), box.g(), box.b(), ALPHA);
            }
        } finally {
            poseStack.popPose();
            buffers.endBatch(type);
            if (lineWidthSet) {
                try {
                    RenderSystem.lineWidth(1.0F);
                } catch (Throwable ignored) {
                    // Ignore driver limitations.
                }
            }
        }
    }

    public static void clear() {
        CONTROLLERS.clear();
        BOXES.clear();
        lastLevel = null;
        lastItem = null;
        target = null;
        tick = 0;
        resetCaches();
    }

    private static void refreshControllers(ClientLevel level, LocalPlayer player) {
        CONTROLLERS.clear();
        Set<Long> seen = new HashSet<>();
        BlockPos playerPos = player.blockPosition();
        int chunkX = playerPos.getX() >> 4;
        int chunkZ = playerPos.getZ() >> 4;

        for (int dx = -SCAN_RADIUS_CHUNKS; dx <= SCAN_RADIUS_CHUNKS; dx++) {
            for (int dz = -SCAN_RADIUS_CHUNKS; dz <= SCAN_RADIUS_CHUNKS; dz++) {
                ChunkAccess chunkAccess = level.getChunk(new BlockPos(
                        (chunkX + dx) << 4, playerPos.getY(), (chunkZ + dz) << 4));
                if (!(chunkAccess instanceof LevelChunk chunk)) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof IMachineBlockEntity machineBlockEntity)) {
                        continue;
                    }
                    try {
                        MetaMachine machine = machineBlockEntity.getMetaMachine();
                        if (!(machine instanceof MultiblockControllerMachine controller)) {
                            continue;
                        }
                        if (controller.isInValid() || controller.getLevel() != level) {
                            continue;
                        }
                        BlockPos pos = controller.getPos();
                        if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D,
                                pos.getZ() + 0.5D) > CONTROLLER_RANGE_SQ) {
                            continue;
                        }
                        if (seen.add(pos.asLong())) {
                            CONTROLLERS.add(controller);
                        }
                    } catch (Throwable ignored) {
                        // A single broken/partially loaded block entity must not hide the overlay.
                    }
                }
            }
        }

        CONTROLLERS.sort(Comparator.comparingDouble(controller -> {
            BlockPos pos = controller.getPos();
            return player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D,
                    pos.getZ() + 0.5D);
        }));
    }

    /**
     * Picks exactly one controller: the one under the crosshair. Looking at a controller or a
     * hatch/bus resolves through its block entity; looking at a casing resolves by checking which
     * nearby structure pattern owns that cell.
     */
    private static MultiblockControllerMachine selectController(ClientLevel level, LocalPlayer player,
                                                                Vec3 eye, Vec3 look) {
        if (level == null || player == null || eye == null || look == null) {
            return null;
        }

        // Standing still: reuse the previous result for up to half a second.
        boolean same = lastSelectResolved && sameView(lastSelectEye, lastSelectLook, eye, look) &&
                tick - lastSelectTick < 10;
        if (same) {
            if (lastSelectTarget == null) {
                return null;
            }
            if (!lastSelectTarget.isInValid()) {
                return lastSelectTarget;
            }
        }

        // While moving/turning, cap the raycast to roughly 10 times per second.
        if (lastSelectResolved && tick - lastSelectTick < 2) {
            return lastSelectTarget != null && !lastSelectTarget.isInValid() ? lastSelectTarget : null;
        }

        Vec3 end = new Vec3(
                eye.x + look.x * RAY_RANGE,
                eye.y + look.y * RAY_RANGE,
                eye.z + look.z * RAY_RANGE);
        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        MultiblockControllerMachine result = null;
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos lookPos = hit.getBlockPos();
            result = controllerAt(level, lookPos);
            if (result == null) {
                result = controllerForCell(lookPos);
            }
        }
        lastSelectEye = eye;
        lastSelectLook = look;
        lastSelectTarget = result;
        lastSelectResolved = true;
        lastSelectTick = tick;
        return result;
    }

    private static MultiblockControllerMachine controllerAt(ClientLevel level, BlockPos lookPos) {
        if (level == null || lookPos == null) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(lookPos);
        if (!(blockEntity instanceof IMachineBlockEntity machineBlockEntity)) {
            return null;
        }
        try {
            MetaMachine machine = machineBlockEntity.getMetaMachine();
            if (machine instanceof MultiblockControllerMachine controller && !controller.isInValid()) {
                return controller;
            }
            if (machine instanceof IMultiPart part) {
                for (IMultiController controller : part.getControllers()) {
                    MultiblockControllerMachine self = controller.self();
                    if (self != null && !self.isInValid()) {
                        return self;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the pattern-cell lookup.
        }
        return null;
    }

    private static MultiblockControllerMachine controllerForCell(BlockPos lookPos) {
        if (lastResolvedValid && lookPos.equals(lastResolvedLookPos)) {
            if (lastResolvedController == null) {
                return null;
            }
            if (!lastResolvedController.isInValid()) {
                return lastResolvedController;
            }
            lastResolvedValid = false;
        }

        for (MultiblockControllerMachine controller : CONTROLLERS) {
            if (controller.isInValid()) {
                continue;
            }
            BlockPattern pattern = PatternSockets.patternFor(controller);
            if (pattern == null) {
                continue;
            }
            Direction front = controller.getFrontFacing();
            if (front == null) {
                front = Direction.NORTH;
            }
            Direction up = controller.getUpwardsFacing();
            if (up == null) {
                up = Direction.NORTH;
            }
            try {
                if (PatternSockets.contains(pattern, front, up, controller.isFlipped(),
                        controller.getPos(), lookPos)) {
                    // CONTROLLERS is sorted by distance, so this is the nearest owner.
                    lastResolvedLookPos = lookPos;
                    lastResolvedController = controller;
                    lastResolvedValid = true;
                    return controller;
                }
            } catch (Throwable ignored) {
                // Try the next candidate.
            }
        }

        lastResolvedLookPos = lookPos;
        lastResolvedController = null;
        lastResolvedValid = true;
        return null;
    }

    private static void rebuildBoxes(ClientLevel level, LocalPlayer player, MetaMachineItem part,
                                     MultiblockControllerMachine controller, Vec3 eye, Vec3 look) {
        BOXES.clear();
        if (controller == null || controller.isInValid()) {
            return;
        }
        try {
            Item heldItem = part;
            Block heldBlock = PartItems.blockOf(part);
            Set<Long> added = new HashSet<>();

            BlockPattern pattern = PatternSockets.patternFor(controller);
            if (pattern == null) {
                return;
            }
            Direction front = controller.getFrontFacing();
            if (front == null) {
                front = Direction.NORTH;
            }
            Direction up = controller.getUpwardsFacing();
            if (up == null) {
                up = Direction.NORTH;
            }
            BlockPos controllerPos = controller.getPos();
            BlockPos playerPos = player.blockPosition();
            int baseX = (playerPos.getX() >> 4) - (controllerPos.getX() >> 4);
            int baseY = (playerPos.getY() >> 4) - (controllerPos.getY() >> 4);
            int baseZ = (playerPos.getZ() >> 4) - (controllerPos.getZ() >> 4);
            Map<Long, List<PatternSockets.Cell>> sections = PatternSockets.sectionCellsFor(
                    pattern, front, up, controller.isFlipped());

            for (int sx = -SECTION_RADIUS; sx <= SECTION_RADIUS; sx++) {
                for (int sy = -SECTION_RADIUS; sy <= SECTION_RADIUS; sy++) {
                    for (int sz = -SECTION_RADIUS; sz <= SECTION_RADIUS; sz++) {
                        List<PatternSockets.Cell> cells = sections.get(
                                PatternSockets.sectionKey(baseX + sx, baseY + sy, baseZ + sz));
                        if (cells == null) {
                            continue;
                        }
                        for (PatternSockets.Cell cell : cells) {
                            BlockPos offset = cell.offset();
                            BlockPos pos = controllerPos.offset(
                                    offset.getX(), offset.getY(), offset.getZ());
                            if (pos.equals(controllerPos)) {
                                continue;
                            }
                            if (!isInFront(eye, look, pos.getX() + 0.5D, pos.getY() + 0.5D,
                                    pos.getZ() + 0.5D)) {
                                continue;
                            }
                            if (!PatternSockets.accepts(cell.predicate(), heldItem) ||
                                    !added.add(pos.asLong())) {
                                continue;
                            }
                            double distance = player.distanceToSqr(pos.getX() + 0.5D,
                                    pos.getY() + 0.5D, pos.getZ() + 0.5D);

                            BlockState state = level.getBlockState(pos);
                            boolean airSocket = state.isAir();
                            if (airSocket) {
                                // An empty socket is shown only when it is a hole in an
                                // otherwise built structure (at least two solid neighbours).
                                // This keeps a completely unformed multiblock from turning
                                // into a cloud of ghost boxes.
                                if (countSolidNeighbours(level, pos) < 2) {
                                    continue;
                                }
                            } else if (!hasAirNeighbour(level, pos)) {
                                continue;
                            }

                            AABB aabb = new AABB(
                                    pos.getX() + 0.002D, pos.getY() + 0.002D,
                                    pos.getZ() + 0.002D,
                                    pos.getX() + 0.998D, pos.getY() + 0.998D,
                                    pos.getZ() + 0.998D);
                            if (airSocket) {
                                BOXES.add(new Box(aabb, AVAILABLE_R, AVAILABLE_G, AVAILABLE_B,
                                        distance));
                                continue;
                            }

                            Block current = state.getBlock();
                            boolean samePart = current == heldBlock;
                            boolean otherMachine = current instanceof IMachineBlock;
                            boolean accepted = PatternSockets.acceptsBlock(cell.predicate(), current);
                            // The held part itself or another machine in the socket means
                            // "occupied". A plain casing accepted by the cell is free to be
                            // replaced.
                            boolean available = accepted && !samePart && !otherMachine;
                            BOXES.add(new Box(aabb,
                                    available ? AVAILABLE_R : OCCUPIED_R,
                                    available ? AVAILABLE_G : OCCUPIED_G,
                                    available ? AVAILABLE_B : OCCUPIED_B,
                                    distance));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // A broken addon pattern must not take the whole overlay down.
            BOXES.clear();
            return;
        }

        BOXES.sort(Comparator.comparingDouble(Box::distance));
        if (BOXES.size() > MAX_BOXES) {
            BOXES.subList(MAX_BOXES, BOXES.size()).clear();
        }
    }

    /**
     * True when the point is within {@link #RENDER_RANGE_SQ} of the player's eyes and inside a
     * roughly 90 degree cone around the look direction. The check uses the squared projection
     * against the (unit) look vector, so no normalisation is needed.
     */
    private static boolean isInFront(Vec3 eye, Vec3 look, double x, double y, double z) {
        double dx = x - eye.x;
        double dy = y - eye.y;
        double dz = z - eye.z;
        double distanceSq = dx * dx + dy * dy + dz * dz;
        if (distanceSq > RENDER_RANGE_SQ || distanceSq < 1.0E-6D) {
            return false;
        }
        double projection = dx * look.x + dy * look.y + dz * look.z;
        return projection > 0.0D &&
                projection * projection >= distanceSq * MIN_FACING_DOT * MIN_FACING_DOT;
    }

    private static boolean hasAirNeighbour(ClientLevel level, BlockPos pos) {
        for (int[] offset : NEIGHBOURS) {
            if (level.getBlockState(pos.offset(offset[0], offset[1], offset[2])).isAir()) {
                return true;
            }
        }
        return false;
    }

    private static int countSolidNeighbours(ClientLevel level, BlockPos pos) {
        int count = 0;
        for (int[] offset : NEIGHBOURS) {
            if (!level.getBlockState(pos.offset(offset[0], offset[1], offset[2])).isAir()) {
                count++;
            }
        }
        return count;
    }

    private record Box(AABB aabb, float r, float g, float b, double distance) {}
}
