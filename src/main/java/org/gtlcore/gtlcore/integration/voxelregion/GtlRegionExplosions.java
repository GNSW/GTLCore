package org.gtlcore.gtlcore.integration.voxelregion;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.api.pattern.INewMultiblockWorldSavedData;
import org.gtlcore.gtlcore.common.util.BlockStateWatcher;
import org.gtlcore.gtlcore.common.worldgen.RegionGeneration;

import com.gregtechceu.gtceu.api.block.OreBlock;
import com.gregtechceu.gtceu.api.data.tag.TagPrefix;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import org.voxelregion.forge.ForgeRegionEngine;
import org.voxelregion.forge.HostIntegration;
import org.voxelregion.forge.PaletteSectionClearBackend;
import org.voxelregion.forge.RegionGeometry;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Server-thread host API. Region radius describes exact geometry, not vanilla explosion strength. */
public final class GtlRegionExplosions {

    private static final UUID MACHINE_ACTOR = new UUID(0, 0);
    private static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue ENABLE_LARGE_FISSION;
    private static final ForgeConfigSpec.IntValue LARGE_FISSION_THRESHOLD;
    private static boolean installed;

    static {
        var config = new ForgeConfigSpec.Builder();
        config.push("fission");
        ENABLE_LARGE_FISSION = config.comment("Use the bounded region engine for large fission reactor failures. Small explosions retain vanilla behavior.")
                .define("use_region_engine", true);
        LARGE_FISSION_THRESHOLD = config.comment("Minimum existing explosion power routed to a region. ceil(power) becomes the exact region radius; this is not vanilla-equivalent terrain damage.")
                .defineInRange("region_threshold", 32, 1, 10000);
        config.pop();
        SPEC = config.build();
    }

    public static void install(IEventBus modBus) {
        if (installed) return;
        ForgeRegionEngine.install(modBus, "gtlcore-voxelregion-server.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC, "gtlcore-region-explosions-server.toml");
        HostIntegration.registerAuditedMod("gtlcore", new HostIntegration.Observer() {

            @Override
            public void afterSectionCleared(ServerLevel level, LevelChunk chunk, PaletteSectionClearBackend.Prepared previous) {
                BlockStateWatcher.notifySectionChanged(level, chunk.getPos().x, previous.sectionY(), chunk.getPos().z,
                        position -> previous.changed(position.getX() & 15, position.getY() & 15, position.getZ() & 15),
                        position -> previous.replacementState(position.getX() & 15, position.getY() & 15, position.getZ() & 15));
            }
        });
        HostIntegration.registerAuditedMod("gtceu", new HostIntegration.Observer() {

            @Override
            public void afterSectionCleared(ServerLevel level, LevelChunk chunk, PaletteSectionClearBackend.Prepared previous) {
                notifyMultiblocks(level, chunk, previous);
            }
        });
        // Audited against GTCEu 1.4.4: exact OreBlock inherits the ordinary removal
        // callback. Sand ores have scheduled falling behavior and remain native.
        HostIntegration.registerStaticRemovalProof("gtlcore:gtceu_1_4_4_solid_ore", state -> {
            if (state.getBlock().getClass() != OreBlock.class) return false;
            var type = TagPrefix.ORES.get(((OreBlock) state.getBlock()).tagPrefix);
            return type != null && !type.isSand();
        });
        MinecraftForge.EVENT_BUS.addListener(GtlRegionCommands::register);
        GtlRegionProgress.install();
        RegionGeneration.install();
        installed = true;
        GTLCore.LOGGER.info("VoxelRegionEngine Forge adapter installed with GTLCore block watchers and GTCEu multiblock notifications");
    }

    private static void notifyMultiblocks(ServerLevel level, LevelChunk chunk, PaletteSectionClearBackend.Prepared previous) {
        var data = MultiblockWorldSavedData.getOrCreate(level);
        var structures = ((INewMultiblockWorldSavedData) data).getControllersInChunk(chunk.getPos());
        // Structure checks can update the chunk mapping; do not iterate its live set through callbacks.
        for (var structure : List.copyOf(structures)) {
            var controller = structure.getController();
            if (controller == null || !controller.isFormed()) continue;
            BlockPos changed = null;
            if (structure.cache.size() <= 4096) {
                for (long packed : structure.cache) {
                    BlockPos position = BlockPos.of(packed);
                    if ((position.getX() >> 4) == chunk.getPos().x && (position.getZ() >> 4) == chunk.getPos().z && (position.getY() >> 4) == previous.sectionY() && previous.changed(position.getX() & 15, position.getY() & 15, position.getZ() & 15)) {
                        changed = position;
                        break;
                    }
                }
            } else {
                // A huge structure cache must not be rescanned in full for every small section.
                for (int voxel = 0; voxel < 4096; voxel++) {
                    int x = voxel & 15, z = (voxel >>> 4) & 15, y = voxel >>> 8;
                    if (!previous.changed(x, y, z)) continue;
                    long packed = BlockPos.asLong(chunk.getPos().getMinBlockX() + x, (previous.sectionY() << 4) + y,
                            chunk.getPos().getMinBlockZ() + z);
                    if (structure.cache.contains(packed)) {
                        changed = BlockPos.of(packed);
                        break;
                    }
                }
            }
            // The callback validates the complete current structure. One changed cache
            // position is sufficient for a section already committed as a batch.
            if (changed != null) structure.onBlockStateChanged(changed,
                    previous.replacementState(changed.getX() & 15, changed.getY() & 15, changed.getZ() & 15));
        }
    }

    public static boolean handlesFissionPower(float power) {
        return ENABLE_LARGE_FISSION.get() && Float.isFinite(power) && power >= LARGE_FISSION_THRESHOLD.get();
    }

    public static ForgeRegionEngine.Handle submitFission(ServerLevel level, BlockPos center, float power) throws IOException {
        if (!Float.isFinite(power) || power < 1 || power > ForgeRegionEngine.maximumRadius())
            throw new IllegalArgumentException("Fission region radius exceeds configured limits");
        return submitSphere(level, MACHINE_ACTOR, center, (int) Math.ceil(power), ForgeRegionEngine.Options.DEFAULT);
    }

    public static ForgeRegionEngine.Handle submitSphere(ServerLevel level, UUID actor, BlockPos center, int radius,
                                                        ForgeRegionEngine.Options options) throws IOException {
        return ForgeRegionEngine.submitSphere(level, actor, center, radius, options);
    }

    public static ForgeRegionEngine.Handle submit(ServerLevel level, UUID actor, RegionGeometry geometry,
                                                  ForgeRegionEngine.Options options) throws IOException {
        return ForgeRegionEngine.submit(level, actor, geometry, options);
    }

    public static Optional<ForgeRegionEngine.Status> status(ServerLevel level, UUID id) {
        return ForgeRegionEngine.status(level, id);
    }

    public static List<ForgeRegionEngine.Status> statuses(ServerLevel level) {
        return ForgeRegionEngine.statuses(level);
    }

    public static ForgeRegionEngine.ControlResult control(ServerLevel level, UUID id, ForgeRegionEngine.Control control) {
        return ForgeRegionEngine.control(level, id, control);
    }

    private GtlRegionExplosions() {}
}
