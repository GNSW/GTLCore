package org.gtlcore.gtlcore.forge;

import org.gtlcore.gtlcore.GTLCore;
import org.gtlcore.gtlcore.common.data.GTLBlocks;
import org.gtlcore.gtlcore.common.data.GTLItems;
import org.gtlcore.gtlcore.common.item.PatternBoxBehavior;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.utils.Registries;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.MissingMappingsEvent;

import dev.latvian.mods.kubejs.KubeJS;

import java.util.Objects;

@Mod.EventBusSubscriber(modid = GTLCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ForgeCommonEventListener {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBindingToolLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getEntity().isShiftKeyDown() ||
                !(event.getEntity().getMainHandItem().getItem() instanceof org.gtlcore.gtlcore.integration.ae2.wireless.WirelessNetworkBindingToolItem) ||
                !org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAeNetworkRuntime.canOpenWirelessTargetMenu(event.getLevel(), event.getPos()))
            return;
        // Cancel on both sides, including creative mode, so unlinking cannot break the hatch.
        event.setCanceled(true);
        if (event.getLevel().isClientSide && event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START) {
            org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAePackets.CHANNEL.sendToServer(
                    new org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAePackets.UnbindWithToolPacket(event.getPos()));
        }
    }

    @SubscribeEvent
    public static void onPortalSpawnEvent(BlockEvent.PortalSpawnEvent event) {
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getItemStack().is(GTLItems.PATTERN_BOX.asItem())) {
            InteractionResult result = PatternBoxBehavior.handleBlockUse(event.getItemStack(),
                    new UseOnContext(event.getLevel(), event.getEntity(), event.getHand(), event.getItemStack(), event.getHitVec()));
            if (result != InteractionResult.PASS) {
                event.setCanceled(true);
                event.setCancellationResult(result);
                return;
            }
        }
        if (event.getEntity().level().getBlockState(event.getPos()).getBlock() == Blocks.END_PORTAL_FRAME &&
                event.getEntity().getItemInHand(event.getHand()).getItem() == Items.ENDER_EYE) {
            if (event.getEntity() instanceof ServerPlayer player &&
                    Objects.equals(Registries.getItemId(player.getOffhandItem()), "kubejs:end_data")) {
                player.getOffhandItem().setCount(player.getOffhandItem().getCount() - 1);
                return;
            }
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onFarmlandTrampleEvent(BlockEvent.FarmlandTrampleEvent event) {
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onEnderTeleportEvent(EntityTeleportEvent.EnderEntity event) {
        if (event.getEntity() instanceof EnderMan || event.getEntity() instanceof Shulker) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlayerTickEvent(TickEvent.PlayerTickEvent event) {
        if (ConfigHolder.INSTANCE.gameplay.disableDrift && event.phase == TickEvent.Phase.END &&
                event.side == LogicalSide.CLIENT && event.player.xxa == 0 && event.player.zza == 0) {
            event.player.setDeltaMovement(event.player.getDeltaMovement().multiply(0.5, 1, 0.5));
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (ConfigHolder.INSTANCE.gameplay.sendUpdateMessages && event.getEntity() instanceof ServerPlayer player) {
            player.displayClientMessage(Component.translatable("gtlcore.message.structure_update_notice"), false);
            player.displayClientMessage(Component.translatable("block.gtceu.huge_incubator"), false);
            player.displayClientMessage(Component.translatable("gtlcore.message.close_update_notice"), false);
        }
    }

    @SubscribeEvent
    public static void remapIds(MissingMappingsEvent event) {
        event.getMappings(net.minecraft.core.registries.Registries.BLOCK, KubeJS.MOD_ID).forEach(mapping -> {
            if (mapping.getKey().equals(KubeJS.id("multi_functional_casing"))) {
                mapping.remap(GTLBlocks.MULTI_FUNCTIONAL_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("create_casing"))) {
                mapping.remap(GTLBlocks.CREATE_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("space_elevator_mechanical_casing"))) {
                mapping.remap(GTLBlocks.SPACE_ELEVATOR_MECHANICAL_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("manipulator"))) {
                mapping.remap(GTLBlocks.MANIPULATOR.get());
            }
            if (mapping.getKey().equals(KubeJS.id("blaze_blast_furnace_casing"))) {
                mapping.remap(GTLBlocks.BLAZE_BLAST_FURNACE_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("cold_ice_casing"))) {
                mapping.remap(GTLBlocks.COLD_ICE_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("dimension_connection_casing"))) {
                mapping.remap(GTLBlocks.DIMENSION_CONNECTION_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("molecular_casing"))) {
                mapping.remap(GTLBlocks.MOLECULAR_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("dimension_injection_casing"))) {
                mapping.remap(GTLBlocks.DIMENSION_INJECTION_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("dimensionally_transcendent_casing"))) {
                mapping.remap(GTLBlocks.DIMENSIONALLY_TRANSCENDENT_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("echo_casing"))) {
                mapping.remap(GTLBlocks.ECHO_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("dragon_strength_tritanium_casing"))) {
                mapping.remap(GTLBlocks.DRAGON_STRENGTH_TRITANIUM_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("aluminium_bronze_casing"))) {
                mapping.remap(GTLBlocks.ALUMINIUM_BRONZE_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("antifreeze_heatproof_machine_casing"))) {
                mapping.remap(GTLBlocks.ANTIFREEZE_HEATPROOF_MACHINE_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("enhance_hyper_mechanical_casing"))) {
                mapping.remap(GTLBlocks.ENHANCE_HYPER_MECHANICAL_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("extreme_strength_tritanium_casing"))) {
                mapping.remap(GTLBlocks.EXTREME_STRENGTH_TRITANIUM_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("graviton_field_constraint_casing"))) {
                mapping.remap(GTLBlocks.GRAVITON_FIELD_CONSTRAINT_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("hyper_mechanical_casing"))) {
                mapping.remap(GTLBlocks.HYPER_MECHANICAL_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("iridium_casing"))) {
                mapping.remap(GTLBlocks.IRIDIUM_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("lafium_mechanical_casing"))) {
                mapping.remap(GTLBlocks.LAFIUM_MECHANICAL_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("oxidation_resistant_hastelloy_n_mechanical_casing"))) {
                mapping.remap(GTLBlocks.OXIDATION_RESISTANT_HASTELLOY_N_MECHANICAL_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("pikyonium_machine_casing"))) {
                mapping.remap(GTLBlocks.PIKYONIUM_MACHINE_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("sps_casing"))) {
                mapping.remap(GTLBlocks.SPS_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("naquadah_alloy_casing"))) {
                mapping.remap(GTLBlocks.NAQUADAH_ALLOY_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("process_machine_casing"))) {
                mapping.remap(GTLBlocks.PROCESS_MACHINE_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("fission_reactor_casing"))) {
                mapping.remap(GTLBlocks.FISSION_REACTOR_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("degenerate_rhenium_constrained_casing"))) {
                mapping.remap(GTLBlocks.DEGENERATE_RHENIUM_CONSTRAINED_CASING.get());
            }
            if (mapping.getKey().equals(KubeJS.id("infinity_glass"))) {
                mapping.remap(GTLBlocks.INFINITY_GLASS.get());
            }
            if (mapping.getKey().equals(KubeJS.id("rhenium_reinforced_energy_glass"))) {
                mapping.remap(GTLBlocks.RHENIUM_REINFORCED_ENERGY_GLASS.get());
            }
            if (mapping.getKey().equals(KubeJS.id("hsss_reinforced_borosilicate_glass"))) {
                mapping.remap(GTLBlocks.HSSS_REINFORCED_BOROSILICATE_GLASS.get());
            }
        });
        event.getMappings(net.minecraft.core.registries.Registries.ITEM, "infinitycells").forEach(mapping -> {
            if (mapping.getKey().equals(new ResourceLocation("infinitycells:infinity_cell"))) {
                mapping.remap(GTLItems.ITEM_INFINITY_CELL.get());
            }
            if (mapping.getKey().equals(new ResourceLocation("infinitycells:infinity_fluid_cell"))) {
                mapping.remap(GTLItems.FLUID_INFINITY_CELL.get());
            }
        });
    }
}
