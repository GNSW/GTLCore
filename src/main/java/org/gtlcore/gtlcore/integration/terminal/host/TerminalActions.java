package org.gtlcore.gtlcore.integration.terminal.host;

import org.gtlcore.gtlcore.common.block.BlockMap;
import org.gtlcore.gtlcore.common.item.UltimateTerminalBehavior;
import org.gtlcore.gtlcore.network.packet.CTerminalAction;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

public final class TerminalActions {

    private static final double MAX_DISTANCE_SQUARED = 32.0D * 32.0D;
    private static final Map<UUID, Long> LAST_ACTION = new WeakHashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger("gtlcore");

    private TerminalActions() {}

    public static void perform(ServerPlayer player, BlockPos controllerPos,
                               CTerminalAction.Action action, boolean noHatch,
                               int repeatCount, boolean preferAe, boolean mirrorMode,
                               String blockType, int blockTier) {
        if (player.distanceToSqr(
                controllerPos.getX() + 0.5D,
                controllerPos.getY() + 0.5D,
                controllerPos.getZ() + 0.5D) > MAX_DISTANCE_SQUARED) {
            player.displayClientMessage(Component.translatable("gtlcore.terminal_host.message.too_far"), true);
            return;
        }

        TerminalAccess.EquippedTerminal equipped = TerminalAccess.find(player);
        if (!equipped.usable()) {
            player.displayClientMessage(Component.translatable("gtlcore.terminal_host.message.no_terminal"), true);
            return;
        }

        long now = player.level().getGameTime();
        Long previous = LAST_ACTION.put(player.getUUID(), now);
        if (previous != null && now - previous < 10L) {
            return;
        }

        if (!(MetaMachine.getMachine(player.level(), controllerPos) instanceof IMultiController controller)) {
            return;
        }

        LOGGER.info("Terminal action player={} creative={} action={} permission={} noHatch={} preferAe={} mirror={} repeat={} blockType={} blockTier={}",
                player.getGameProfile().getName(), player.isCreative(), action, equipped.kind(),
                noHatch, preferAe, mirrorMode, repeatCount, blockType, blockTier);

        BlockPattern pattern = controller.getPattern();
        if (pattern == null) {
            player.displayClientMessage(Component.translatable("gtlcore.terminal_host.message.no_pattern"), true);
            return;
        }

        if (action == CTerminalAction.Action.UPGRADE) {
            upgrade(player, controller, pattern, equipped, blockType, blockTier);
        } else if (action == CTerminalAction.Action.DISMANTLE_AE || action == CTerminalAction.Action.DISMANTLE_INVENTORY) {
            dismantle(player, controller, pattern, equipped,
                    action == CTerminalAction.Action.DISMANTLE_AE, mirrorMode);
        } else {
            build(player, controller, pattern, equipped, noHatch, preferAe, mirrorMode,
                    repeatCount, blockType, blockTier);
        }
    }

    private static void build(ServerPlayer player, IMultiController controller, BlockPattern pattern,
                              TerminalAccess.EquippedTerminal equipped, boolean noHatch,
                              boolean preferAe, boolean mirrorMode, int repeatCount,
                              String blockType, int blockTier) {
        if (controller.isFormed()) {
            player.displayClientMessage(Component.translatable("gtlcore.terminal_host.message.already_formed"), true);
            return;
        }

        if (equipped.kind() == TerminalKind.BASIC) {
            MirrorIntent.clear(player.level(), controller.self().getPos());
            pattern.autoBuild(player, controller.getMultiblockState());
            player.displayClientMessage(Component.translatable("gtlcore.terminal_host.message.build_started"), true);
            return;
        }

        int safeRepeat = Math.max(0, Math.min(repeatCount, 648));
        String safeType = blockType != null && BlockMap.tierBlockMap.containsKey(blockType) ? blockType : "coil";
        int safeTier = Math.max(0, blockTier);

        HostBuildSettings.Ultimate setting = new HostBuildSettings.Ultimate();
        setting.setHostNoHatch(noHatch);
        setting.setNoHatchMode(noHatch);
        setting.setRepeatCount(safeRepeat);
        setting.setAeMode(preferAe);
        setting.setFlipped(mirrorMode);
        setting.setBoundAE(preferAe ? readBoundAe(equipped.stack()) : null);
        applyTierSelection(setting, safeType, safeTier);

        org.gtlcore.gtlcore.api.pattern.AdvancedBlockPattern advanced = org.gtlcore.gtlcore.api.pattern.AdvancedBlockPattern.getAdvancedBlockPattern(pattern);
        if (advanced != null) {
            advanced.autoBuild(player, controller.getMultiblockState(), setting);
            player.displayClientMessage(Component.translatable("gtlcore.terminal_host.message.build_started"), true);
        } else {
            player.displayClientMessage(Component.translatable("gtlcore.terminal_host.message.no_pattern"), true);
        }
    }

    private static void dismantle(ServerPlayer player, IMultiController controller, BlockPattern pattern,
                                  TerminalAccess.EquippedTerminal equipped, boolean returnToAe,
                                  boolean mirrorMode) {
        if (equipped.kind() != TerminalKind.ULTIMATE) {
            player.displayClientMessage(Component.translatable("gtlcore.terminal_host.message.need_ultimate"), true);
            return;
        }

        org.gtlcore.gtlcore.api.pattern.AdvancedBlockPattern advanced = org.gtlcore.gtlcore.api.pattern.AdvancedBlockPattern.getAdvancedBlockPattern(pattern);
        if (advanced == null) {
            player.displayClientMessage(Component.translatable("gtlcore.terminal_host.message.no_pattern"), true);
            return;
        }

        PatternPreservingDismantleSetting setting = new PatternPreservingDismantleSetting();
        setting.setFlipped(mirrorMode);
        GlobalPos boundAe = returnToAe ? readBoundAe(equipped.stack()) : null;
        boolean boundToCurrentDimension = boundAe != null && boundAe.dimension().equals(player.level().dimension());
        boolean unboundAeFallback = returnToAe && !boundToCurrentDimension;
        setting.setStrictAeOutput(returnToAe && boundToCurrentDimension);
        setting.setAeMode(returnToAe && boundToCurrentDimension);
        setting.setBoundAE(boundToCurrentDimension ? boundAe : null);

        advanced.dismantleMultiblock(controller, player, setting);
        player.displayClientMessage(Component.translatable("gtlcore.terminal_host.message.dismantle_started"), true);
        if (unboundAeFallback) {
            player.displayClientMessage(Component.translatable(
                    "gtlcore.terminal_host.message.unbound_ae_inventory"), true);
        }
    }

    private static GlobalPos readBoundAe(net.minecraft.world.item.ItemStack terminal) {
        CompoundTag tag = terminal.getTag();
        if (tag == null || !tag.contains("BoundAE", CompoundTag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag bound = tag.getCompound("BoundAE");
        ResourceLocation dimensionId = ResourceLocation.tryParse(bound.getString("dim"));
        if (dimensionId == null) {
            return null;
        }
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        return GlobalPos.of(dimension,
                new BlockPos(bound.getInt("X"), bound.getInt("Y"), bound.getInt("Z")));
    }

    private static void upgrade(ServerPlayer player, IMultiController controller, BlockPattern pattern,
                                TerminalAccess.EquippedTerminal equipped, String blockType, int blockTier) {
        if (equipped.kind() != TerminalKind.ULTIMATE) {
            player.displayClientMessage(Component.translatable("gtlcore.terminal_host.message.need_ultimate"), true);
            return;
        }
        if (!controller.isFormed()) {
            player.displayClientMessage(Component.translatable("gtlcore.terminal_host.message.not_formed"), true);
            return;
        }

        String safeType = blockType != null && BlockMap.tierBlockMap.containsKey(blockType) ? blockType : "coil";
        Block[] blocks = BlockMap.tierBlockMap.get(safeType).get();
        if (blocks.length == 0) {
            return;
        }
        int safeTier = Math.max(0, Math.min(blockTier, blocks.length - 1));
        boolean reverse = hasHigherTierNearby(player, controller, blocks, safeTier);

        org.gtlcore.gtlcore.api.pattern.AdvancedBlockPattern advanced = org.gtlcore.gtlcore.api.pattern.AdvancedBlockPattern.getAdvancedBlockPattern(pattern);
        if (advanced == null) {
            player.displayClientMessage(Component.translatable("gtlcore.terminal_host.message.no_pattern"), true);
            return;
        }

        HostBuildSettings.Ultimate setting = new HostBuildSettings.Ultimate();
        setting.setHostNoHatch(false);
        setting.setNoHatchMode(false);
        setting.setRepeatCount(0);
        setting.setAeMode(false);
        setting.setFlipped(controller.getMultiblockState().isNeededFlip());
        setting.setBoundAE(null);
        setting.setReplaceMode(true);
        applyTierSelection(setting, safeType, safeTier);
        advanced.autoBuild(player, controller.getMultiblockState(), setting);
        if (controller instanceof WorkableMultiblockMachine machine) {
            machine.onPartUnload();
        }

        player.displayClientMessage(Component.translatable(reverse ? "gtlcore.terminal_host.message.reverse_upgrade" : "gtlcore.terminal_host.message.upgrade_started"), true);
    }

    private static boolean hasHigherTierNearby(ServerPlayer player, IMultiController controller,
                                               Block[] tierBlocks, int targetTier) {
        BlockPos center = controller.self().getPos();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-32, -16, -32),
                center.offset(32, 16, 32))) {
            Block current = player.level().getBlockState(pos).getBlock();
            for (int tier = targetTier + 1; tier < tierBlocks.length; tier++) {
                if (current == tierBlocks[tier]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void applyTierSelection(UltimateTerminalBehavior.AutoBuildSetting setting,
                                           String blockType, int blockTier) {
        var lazyBlocks = BlockMap.tierBlockMap.get(blockType);
        if (lazyBlocks == null) {
            return;
        }
        Block[] blocks = lazyBlocks.get();
        if (blocks.length == 0) {
            return;
        }
        int tier = Math.max(0, Math.min(blockTier, blocks.length - 1));
        setting.addTierSelection(blocks, blocks[tier]);
    }
}
