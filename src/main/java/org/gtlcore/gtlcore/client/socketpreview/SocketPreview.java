package org.gtlcore.gtlcore.client.socketpreview;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.config.ConfigHolder;

import com.gregtechceu.gtceu.api.block.IMachineBlock;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.settings.KeyConflictContext;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Client-side socket overlay.
 *
 * <p>
 * Only the single multiblock controller under the player's crosshair is rendered. A small area
 * is drawn: a configurable render radius (24 blocks by default) and a roughly 90 degree view cone,
 * capped at {@link #MAX_BOXES} nearest boxes. Controller discovery still uses a wider 13x13 chunk
 * area so a large multiblock whose controller is farther away can still be resolved from a casing
 * block; the discovery itself is invisible and only runs every 20 ticks. A box rebuild runs every
 * 4 ticks. Only block positions that are loaded, non-air and have at least one air neighbour are
 * considered, so a formed shell produces a readable set of sockets instead of the whole structure
 * volume.
 *
 * <p>
 * Everything on the scanning path is designed to be cheap for very large structures:
 * <ul>
 * <li>the pattern cells come from {@link PatternSockets}, a flat section-indexed layout, so a
 * rebuild only walks the sections around the player instead of every cell of the multiblock;</li>
 * <li>sections are visited from the player outwards (Chebyshev rings). Once the nearest
 * {@link #MAX_BOXES} boxes are found, every farther ring is skipped;</li>
 * <li>the six neighbour block states of a cell are answered by a per-rebuild open-addressed
 * cache, which turns the ~13 {@code getBlockState} lookups per cell into one lookup per unique
 * position;</li>
 * <li>boxes are kept in pre-allocated primitive arrays with a bounded max-heap, so a rebuild of a
 * huge structure allocates nothing;</li>
 * <li>a rebuild is capped by {@link #MAX_CELL_VISITS}, and the refresh cadence backs off when a
 * rebuild is expensive, so an enormous structure can never stall a frame.</li>
 * </ul>
 *
 * <p>
 * The overlay can also be switched off for the session with the
 * {@code key.gtlcore.toggle_socket_preview} key binding (unbound by default, configured in the
 * vanilla controls screen). The config option stays the master switch; the key binding can only
 * turn the overlay off when the config allows it.
 */
public final class SocketPreview {

    private static final String TOGGLE_KEY = "key.gtlcore.toggle_socket_preview";
    private static final String KEY_CATEGORY = "key.categories.gtlcore";
    private static final String MESSAGE_ENABLED = "message.gtlcore.socket_preview.enabled";
    private static final String MESSAGE_DISABLED = "message.gtlcore.socket_preview.disabled";
    private static final String MESSAGE_CONFIG_DISABLED = "message.gtlcore.socket_preview.config_disabled";

    private static final int SCAN_RADIUS_CHUNKS = 6;
    private static final double CONTROLLER_RANGE_SQ = 96.0D * 96.0D;
    private static final int CONTROLLER_REFRESH_TICKS = 20;
    private static final int BOX_REFRESH_TICKS = 4;
    private static final int BOX_REFRESH_TICKS_SLOW = 8;
    private static final long SLOW_BUILD_NANOS = 1_500_000L;
    private static final int MAX_BOXES = 512;
    private static final int MAX_CELL_VISITS = 49_152;
    private static final double RAY_RANGE = 64.0D;
    private static final double MIN_FACING_DOT = 0.7D;
    private static final float ALPHA = 0.9F;

    private static final float AVAILABLE_R = 0.20F;
    private static final float AVAILABLE_G = 0.90F;
    private static final float AVAILABLE_B = 1.00F;
    private static final float OCCUPIED_R = 1.00F;
    private static final float OCCUPIED_G = 0.65F;
    private static final float OCCUPIED_B = 0.15F;

    private static final Boxes BOXES = new Boxes();
    private static final BlockStates STATES = new BlockStates();
    private static final Scan SCAN = new Scan();
    private static final AABB FRUSTUM_BOX = new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);

    private static final List<MultiblockControllerMachine> CONTROLLERS = new ArrayList<>();

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
    private static int lastBuildRange;
    private static long lastBuildNanos;

    private static KeyMapping toggleKey;
    /** Session-local switch flipped by the key binding; the config option stays the master switch. */
    private static boolean enabledByKey = true;

    private SocketPreview() {}

    /**
     * Registers the overlay toggle. It is intentionally unbound by default, so nothing happens
     * until a player assigns a key to it in the controls screen.
     */
    public static void registerKeyMapping(RegisterKeyMappingsEvent event) {
        toggleKey = new KeyMapping(TOGGLE_KEY, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, KEY_CATEGORY);
        event.register(toggleKey);
    }

    public static void tick() {
        try {
            pollToggleKey();
            tickUnsafe();
        } catch (Throwable failure) {
            if (!errorLogged) {
                errorLogged = true;
                GTLCore.LOGGER.error("Socket preview scan failed; the overlay is now idle", failure);
            }
            clear();
        }
    }

    private static void pollToggleKey() {
        KeyMapping key = toggleKey;
        if (key == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        while (key.consumeClick()) {
            if (minecraft == null || minecraft.screen != null) {
                continue;
            }
            boolean configEnabled = isConfigEnabled();
            enabledByKey = !enabledByKey;
            if (!isEnabled()) {
                clear();
            }
            LocalPlayer player = minecraft.player;
            if (player == null) {
                continue;
            }
            player.displayClientMessage(Component.translatable(
                    !configEnabled ? MESSAGE_CONFIG_DISABLED : enabledByKey ? MESSAGE_ENABLED : MESSAGE_DISABLED),
                    true);
        }
    }

    private static void tickUnsafe() {
        if (!isEnabled()) {
            clear();
            return;
        }
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

        int renderRange = renderRangeBlocks();
        boolean rangeChanged = renderRange != lastBuildRange;
        boolean viewChanged = !sameView(lastBuildEye, lastBuildLook, eye, look);
        int interval = viewChanged ? buildInterval() : 10;
        if (itemChanged || targetChanged || rangeChanged || tick - lastBuildTick >= interval) {
            rebuildBoxes(level, player, part, selected, eye, look, renderRange);
            lastBuildEye = eye;
            lastBuildLook = look;
            lastBuildTick = tick;
            lastBuildRange = renderRange;
        }
    }

    /**
     * Backs the rebuild cadence off while a single rebuild is expensive, so a multiblock that is
     * too large for one frame's budget keeps a stable overlay instead of stuttering.
     */
    private static int buildInterval() {
        return lastBuildNanos > SLOW_BUILD_NANOS ? BOX_REFRESH_TICKS_SLOW : BOX_REFRESH_TICKS;
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
        lastBuildRange = 0;
        lastBuildNanos = 0L;
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
        if (!isEnabled() || BOXES.isEmpty()) {
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
        Frustum frustum = event.getFrustum();

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
            int count = BOXES.size();
            for (int index = 0; index < count; index++) {
                long position = BOXES.position(index);
                double minX = BlockPos.getX(position) + 0.002D;
                double minY = BlockPos.getY(position) + 0.002D;
                double minZ = BlockPos.getZ(position) + 0.002D;
                double maxX = minX + 0.996D;
                double maxY = minY + 0.996D;
                double maxZ = minZ + 0.996D;
                // The box set is refreshed a few times per second, so drop the boxes that a fast
                // camera turn or a pitch change moved out of the frustum.
                if (frustum != null && !isVisible(frustum, minX, minY, minZ, maxX, maxY, maxZ)) {
                    continue;
                }
                boolean available = BOXES.available(index);
                LevelRenderer.renderLineBox(poseStack, lines, minX, minY, minZ, maxX, maxY, maxZ,
                        available ? AVAILABLE_R : OCCUPIED_R,
                        available ? AVAILABLE_G : OCCUPIED_G,
                        available ? AVAILABLE_B : OCCUPIED_B,
                        ALPHA);
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

    private static boolean isVisible(Frustum frustum, double minX, double minY, double minZ,
                                     double maxX, double maxY, double maxZ) {
        return frustum.isVisible(FRUSTUM_BOX.setMinX(minX).setMinY(minY).setMinZ(minZ)
                .setMaxX(maxX).setMaxY(maxY).setMaxZ(maxZ));
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

    public static boolean isEnabled() {
        return isConfigEnabled() && enabledByKey;
    }

    private static boolean isConfigEnabled() {
        return ConfigHolder.INSTANCE == null ||
                ConfigHolder.INSTANCE.graphics.socketPreview.socketPreviewEnabled;
    }

    private static int renderRangeBlocks() {
        if (ConfigHolder.INSTANCE == null) {
            return ConfigHolder.DEFAULT_SOCKET_PREVIEW_RENDER_RANGE;
        }
        return Math.max(1, Math.min(ConfigHolder.MAX_SOCKET_PREVIEW_RENDER_RANGE,
                ConfigHolder.INSTANCE.graphics.socketPreview.socketPreviewRenderRange));
    }

    private static void refreshControllers(ClientLevel level, LocalPlayer player) {
        CONTROLLERS.clear();
        Set<Long> seen = new HashSet<>();
        BlockPos playerPos = player.blockPosition();
        int chunkX = playerPos.getX() >> 4;
        int chunkZ = playerPos.getZ() >> 4;

        for (int dx = -SCAN_RADIUS_CHUNKS; dx <= SCAN_RADIUS_CHUNKS; dx++) {
            for (int dz = -SCAN_RADIUS_CHUNKS; dz <= SCAN_RADIUS_CHUNKS; dz++) {
                ChunkAccess chunkAccess = level.getChunk(chunkX + dx, chunkZ + dz);
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
                PatternSockets.Cells cells = PatternSockets.cellsFor(pattern, front, up,
                        isMirroredPlacement(controller, pattern, front, up));
                BlockPos controllerPos = controller.getPos();
                int dx = lookPos.getX() - controllerPos.getX();
                int dy = lookPos.getY() - controllerPos.getY();
                int dz = lookPos.getZ() - controllerPos.getZ();
                if (!PatternSockets.isPackable(dx) || !PatternSockets.isPackable(dy) ||
                        !PatternSockets.isPackable(dz)) {
                    continue;
                }
                if (cells.contains(PatternSockets.pack(dx, dy, dz))) {
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
                                     MultiblockControllerMachine controller, Vec3 eye, Vec3 look,
                                     int renderRange) {
        BOXES.clear();
        if (controller == null || controller.isInValid()) {
            return;
        }
        long started = System.nanoTime();
        try {
            rebuildBoxesUnsafe(level, player, part, controller, eye, look, renderRange);
        } catch (Throwable ignored) {
            // A broken addon pattern must not take the whole overlay down.
            BOXES.clear();
        } finally {
            lastBuildNanos = System.nanoTime() - started;
        }
    }

    private static void rebuildBoxesUnsafe(ClientLevel level, LocalPlayer player, MetaMachineItem part,
                                           MultiblockControllerMachine controller, Vec3 eye, Vec3 look,
                                           int renderRange) {
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
        PatternSockets.Cells cells = PatternSockets.cellsFor(pattern, front, up,
                isMirroredPlacement(controller, pattern, front, up));
        if (cells.count() == 0) {
            return;
        }

        BlockPos controllerPos = controller.getPos();
        BlockPos playerPos = player.blockPosition();
        Scan scan = SCAN;
        scan.cells = cells;
        scan.heldItem = part;
        scan.heldBlock = PartItems.blockOf(part);
        scan.eye = eye;
        scan.look = look;
        scan.rangeSq = (double) renderRange * renderRange;
        scan.originX = controllerPos.getX();
        scan.originY = controllerPos.getY();
        scan.originZ = controllerPos.getZ();
        scan.budget = MAX_CELL_VISITS;
        STATES.begin(level);

        int baseX = (playerPos.getX() >> 4) - (controllerPos.getX() >> 4);
        int baseY = (playerPos.getY() >> 4) - (controllerPos.getY() >> 4);
        int baseZ = (playerPos.getZ() >> 4) - (controllerPos.getZ() >> 4);
        int sectionRadius = Math.max(1, (renderRange + 15) >> 4);

        // Walk the sections around the player from the inside out. A ring is 16 blocks farther
        // out than the previous one, so once the kept boxes are all closer than the next ring
        // every remaining section can be skipped.
        for (int ring = 0; ring <= sectionRadius; ring++) {
            if (scan.budget <= 0) {
                break;
            }
            if (BOXES.isFull() && ringDistanceSq(ring) > BOXES.maxDistance()) {
                break;
            }
            scanRing(scan, ring, baseX, baseY, baseZ);
        }
    }

    private static void scanRing(Scan scan, int ring, int baseX, int baseY, int baseZ) {
        if (ring == 0) {
            scanSection(scan, baseX, baseY, baseZ);
            return;
        }
        // The three face pairs partition the shell: the first pair keeps the full cube square,
        // the second one is narrowed on x, the third one on x and y.
        for (int a = -ring; a <= ring; a++) {
            for (int b = -ring; b <= ring; b++) {
                scanSection(scan, baseX + ring, baseY + a, baseZ + b);
                scanSection(scan, baseX - ring, baseY + a, baseZ + b);
            }
        }
        for (int a = -ring + 1; a <= ring - 1; a++) {
            for (int b = -ring; b <= ring; b++) {
                scanSection(scan, baseX + a, baseY + ring, baseZ + b);
                scanSection(scan, baseX + a, baseY - ring, baseZ + b);
            }
        }
        for (int a = -ring + 1; a <= ring - 1; a++) {
            for (int b = -ring + 1; b <= ring - 1; b++) {
                scanSection(scan, baseX + a, baseY + b, baseZ + ring);
                scanSection(scan, baseX + a, baseY + b, baseZ - ring);
            }
        }
    }

    private static void scanSection(Scan scan, int sectionX, int sectionY, int sectionZ) {
        if (scan.budget <= 0) {
            return;
        }
        long key = PatternSockets.sectionKey(sectionX, sectionY, sectionZ);
        int start = scan.cells.sectionStart(key);
        if (start < 0) {
            return;
        }
        double cornerX = scan.originX + (sectionX << 4);
        double cornerY = scan.originY + (sectionY << 4);
        double cornerZ = scan.originZ + (sectionZ << 4);
        if (sectionDistanceSq(scan.eye, cornerX, cornerY, cornerZ) > scan.rangeSq) {
            return;
        }
        int end = scan.cells.sectionEnd(key);
        for (int index = start; index < end; index++) {
            if (--scan.budget < 0) {
                return;
            }
            long offset = scan.cells.offset(index);
            int x = scan.originX + PatternSockets.unpackX(offset);
            int y = scan.originY + PatternSockets.unpackY(offset);
            int z = scan.originZ + PatternSockets.unpackZ(offset);
            if (x == scan.originX && y == scan.originY && z == scan.originZ) {
                continue;
            }
            if (!isInFront(scan.eye, scan.look, x + 0.5D, y + 0.5D, z + 0.5D, scan.rangeSq)) {
                continue;
            }
            TraceabilityPredicate predicate = scan.cells.predicate(index);
            if (!PatternSockets.accepts(predicate, scan.heldItem)) {
                continue;
            }
            BlockState state = STATES.state(x, y, z);
            if (state.isAir()) {
                // An empty socket is shown only when it is a hole in an otherwise built
                // structure (at least two solid neighbours).
                if (STATES.solidNeighbours(x, y, z) < 2) {
                    continue;
                }
                BOXES.offer(BlockPos.asLong(x, y, z), true, eyeDistanceSq(scan, x, y, z));
                continue;
            }
            if (!STATES.hasAirNeighbour(x, y, z)) {
                continue;
            }
            Block current = state.getBlock();
            boolean samePart = current == scan.heldBlock;
            boolean otherMachine = current instanceof IMachineBlock;
            boolean accepted = PatternSockets.acceptsBlock(predicate, current);
            // The held part itself or another machine in the socket means "occupied". A plain
            // casing accepted by the cell is free to be replaced.
            boolean available = accepted && !samePart && !otherMachine;
            BOXES.offer(BlockPos.asLong(x, y, z), available, eyeDistanceSq(scan, x, y, z));
        }
    }

    /**
     * Approximate (squared) distance between the player and a section ring. Ring {@code r} is at
     * least {@code (r - 1) * 16} blocks away, which is a safe lower bound for the boxes inside it.
     */
    private static double ringDistanceSq(int ring) {
        double distance = (ring - 1) * 16.0D;
        return distance > 0.0D ? distance * distance : 0.0D;
    }

    private static double sectionDistanceSq(Vec3 eye, double minX, double minY, double minZ) {
        double dx = axisDistance(eye.x, minX, minX + 16.0D);
        double dy = axisDistance(eye.y, minY, minY + 16.0D);
        double dz = axisDistance(eye.z, minZ, minZ + 16.0D);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        return value > max ? value - max : 0.0D;
    }

    private static double eyeDistanceSq(Scan scan, int x, int y, int z) {
        double dx = x + 0.5D - scan.eye.x;
        double dy = y + 0.5D - scan.eye.y;
        double dz = z + 0.5D - scan.eye.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Uses the controller's synced state first and falls back to the actual part positions when
     * that state is unavailable or stale on the client. Membership is a binary search inside the
     * pre-built {@link PatternSockets.Cells} layout, so a multiblock with hundreds of parts no
     * longer rescans its cell lists for every part.
     *
     * <p>
     * The mirrored layout is only built when the normal one does not already explain a strict
     * majority of the parts, so the common (not flipped) case walks a huge pattern once instead
     * of twice.
     */
    private static boolean isMirroredPlacement(MultiblockControllerMachine controller,
                                               BlockPattern pattern, Direction front, Direction up) {
        if (controller.isFlipped()) {
            return true;
        }
        BlockPos[] partPositions;
        try {
            partPositions = controller.getPartPositions();
        } catch (Throwable ignored) {
            return false;
        }
        if (partPositions == null || partPositions.length == 0) {
            return false;
        }
        PatternSockets.Cells normal = PatternSockets.cellsFor(pattern, front, up, false);
        if (normal.count() == 0) {
            return false;
        }

        BlockPos controllerPos = controller.getPos();
        int considered = 0;
        int normalMatches = 0;
        for (BlockPos partPos : partPositions) {
            if (partPos == null || partPos.equals(controllerPos)) {
                continue;
            }
            considered++;
            if (normal.contains(packOffset(partPos, controllerPos))) {
                normalMatches++;
            }
            // A strict majority of the parts already decides the answer.
            if (normalMatches * 2 > considered) {
                return false;
            }
        }
        if (considered == 0) {
            return false;
        }

        PatternSockets.Cells mirrored = PatternSockets.cellsFor(pattern, front, up, true);
        int mirroredMatches = 0;
        for (BlockPos partPos : partPositions) {
            if (partPos == null || partPos.equals(controllerPos)) {
                continue;
            }
            if (mirrored.contains(packOffset(partPos, controllerPos))) {
                mirroredMatches++;
            }
            if (mirroredMatches * 2 > considered) {
                return true;
            }
        }
        return mirroredMatches > normalMatches;
    }

    /**
     * Packed controller-relative offset of a block position, or {@link Long#MIN_VALUE} when it is
     * out of packable range. A packed offset never sets its sign bit, so that sentinel can never
     * be found by {@link PatternSockets.Cells#contains(long)}.
     */
    private static long packOffset(BlockPos pos, BlockPos origin) {
        int dx = pos.getX() - origin.getX();
        int dy = pos.getY() - origin.getY();
        int dz = pos.getZ() - origin.getZ();
        if (!PatternSockets.isPackable(dx) || !PatternSockets.isPackable(dy) ||
                !PatternSockets.isPackable(dz)) {
            return Long.MIN_VALUE;
        }
        return PatternSockets.pack(dx, dy, dz);
    }

    /**
     * True when the point is within the configured render range of the player's eyes and inside
     * a roughly 90 degree cone around the look direction. The check uses the squared projection
     * against the (unit) look vector, so no normalisation is needed.
     */
    private static boolean isInFront(Vec3 eye, Vec3 look, double x, double y, double z,
                                     double renderRangeSq) {
        double dx = x - eye.x;
        double dy = y - eye.y;
        double dz = z - eye.z;
        double distanceSq = dx * dx + dy * dy + dz * dz;
        if (distanceSq > renderRangeSq || distanceSq < 1.0E-6D) {
            return false;
        }
        double projection = dx * look.x + dy * look.y + dz * look.z;
        return projection > 0.0D &&
                projection * projection >= distanceSq * MIN_FACING_DOT * MIN_FACING_DOT;
    }

    /** Mutable per-rebuild scan state, reused so a rebuild allocates nothing. */
    private static final class Scan {

        private PatternSockets.Cells cells;
        private Item heldItem;
        private Block heldBlock;
        private Vec3 eye;
        private Vec3 look;
        private double rangeSq;
        private int originX;
        private int originY;
        private int originZ;
        private int budget;
    }

    /**
     * The nearest {@link #MAX_BOXES} boxes, kept in a bounded max-heap over pre-allocated
     * primitive arrays. Adding a box never allocates, and the heap root is the current farthest
     * box, which the section walk uses to stop early.
     */
    private static final class Boxes {

        private final double[] distance = new double[MAX_BOXES];
        private final long[] position = new long[MAX_BOXES];
        private final boolean[] available = new boolean[MAX_BOXES];
        private int size;

        void clear() {
            this.size = 0;
        }

        boolean isEmpty() {
            return this.size == 0;
        }

        boolean isFull() {
            return this.size == MAX_BOXES;
        }

        int size() {
            return this.size;
        }

        long position(int index) {
            return this.position[index];
        }

        boolean available(int index) {
            return this.available[index];
        }

        double maxDistance() {
            return this.size < MAX_BOXES ? Double.POSITIVE_INFINITY : this.distance[0];
        }

        void offer(long blockPosition, boolean isAvailable, double squaredDistance) {
            if (this.size < MAX_BOXES) {
                write(this.size, blockPosition, isAvailable, squaredDistance);
                siftUp(this.size);
                this.size++;
                return;
            }
            if (squaredDistance >= this.distance[0]) {
                return;
            }
            write(0, blockPosition, isAvailable, squaredDistance);
            siftDown(0);
        }

        private void write(int slot, long blockPosition, boolean isAvailable, double squaredDistance) {
            this.distance[slot] = squaredDistance;
            this.position[slot] = blockPosition;
            this.available[slot] = isAvailable;
        }

        private void swap(int first, int second) {
            if (first == second) {
                return;
            }
            double distance = this.distance[first];
            this.distance[first] = this.distance[second];
            this.distance[second] = distance;
            long position = this.position[first];
            this.position[first] = this.position[second];
            this.position[second] = position;
            boolean available = this.available[first];
            this.available[first] = this.available[second];
            this.available[second] = available;
        }

        private void siftUp(int index) {
            while (index > 0) {
                int parent = (index - 1) >> 1;
                if (this.distance[parent] >= this.distance[index]) {
                    return;
                }
                swap(parent, index);
                index = parent;
            }
        }

        private void siftDown(int index) {
            int half = this.size >> 1;
            while (index < half) {
                int child = (index << 1) + 1;
                int right = child + 1;
                if (right < this.size && this.distance[right] > this.distance[child]) {
                    child = right;
                }
                if (this.distance[index] >= this.distance[child]) {
                    return;
                }
                swap(index, child);
                index = child;
            }
        }
    }

    /**
     * Per-rebuild block state cache. A socket test reads the cell plus its six neighbours, so a
     * section is probed about thirteen times by the naive path; this turns that into one chunk
     * lookup per unique position. Entries carry a generation stamp that is bumped for every
     * rebuild, so the cache never has to be cleared and never returns a stale state.
     */
    private static final class BlockStates {

        private static final int SIZE = 1 << 13;
        private static final int SHIFT = 64 - 13;

        private final long[] keys = new long[SIZE];
        private final BlockState[] states = new BlockState[SIZE];
        private final int[] stamps = new int[SIZE];
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private ClientLevel level;
        private int generation = 1;

        void begin(ClientLevel clientLevel) {
            this.level = clientLevel;
            if (++this.generation == Integer.MAX_VALUE) {
                Arrays.fill(this.stamps, 0);
                this.generation = 1;
            }
        }

        BlockState state(int x, int y, int z) {
            long key = BlockPos.asLong(x, y, z);
            int slot = slot(key);
            if (this.stamps[slot] == this.generation && this.keys[slot] == key) {
                return this.states[slot];
            }
            BlockState state = this.level.getBlockState(this.cursor.set(x, y, z));
            this.stamps[slot] = this.generation;
            this.keys[slot] = key;
            this.states[slot] = state;
            return state;
        }

        boolean isAir(int x, int y, int z) {
            return state(x, y, z).isAir();
        }

        boolean hasAirNeighbour(int x, int y, int z) {
            return isAir(x + 1, y, z) || isAir(x - 1, y, z) ||
                    isAir(x, y + 1, z) || isAir(x, y - 1, z) ||
                    isAir(x, y, z + 1) || isAir(x, y, z - 1);
        }

        int solidNeighbours(int x, int y, int z) {
            int count = 0;
            if (!isAir(x + 1, y, z)) {
                count++;
            }
            if (!isAir(x - 1, y, z)) {
                count++;
            }
            if (!isAir(x, y + 1, z)) {
                count++;
            }
            if (!isAir(x, y - 1, z)) {
                count++;
            }
            if (!isAir(x, y, z + 1)) {
                count++;
            }
            if (!isAir(x, y, z - 1)) {
                count++;
            }
            return count;
        }

        private static int slot(long key) {
            return (int) ((key * 0x9E3779B97F4A7C15L) >>> SHIFT);
        }
    }
}
