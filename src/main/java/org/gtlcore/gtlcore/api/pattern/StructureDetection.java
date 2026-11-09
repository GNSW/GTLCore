package org.gtlcore.gtlcore.api.pattern;

import org.gtlcore.gtlcore.network.GTLNetworkHandler;
import org.gtlcore.gtlcore.network.packet.SStructureDetectHighlight;

import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.pattern.*;
import com.gregtechceu.gtceu.api.pattern.error.*;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;

import com.lowdragmc.lowdraglib.utils.BlockInfo;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Arrays;
import java.util.List;

/**
 * Structure detection shared by the ultimate terminal: reports the first mismatching position of an unformed
 * multiblock, honouring the terminal's mirror switch and repetition count.
 */
public final class StructureDetection {

    /**
     * Detection runs on the server thread, so a plain flag is enough to reject a second request that was queued
     * before the first one finished.
     */
    private static boolean running;

    private StructureDetection() {}

    public static void detect(Player player, IMultiController controller, boolean isFlipped, int repeatCount) {
        if (controller.isFormed()) {
            player.sendSystemMessage(Component.translatable("message.gtlcore.structure_formed").withStyle(ChatFormatting.GREEN));
            return;
        }
        Level level = controller.self().getLevel();
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) return;
        if (running) {
            player.sendSystemMessage(Component.translatable("message.gtlcore.structure_detection_running"));
            return;
        }
        running = true;
        serverLevel.getServer().execute(() -> {
            try {
                for (var detectionError : check(controller, controller.getPattern(), isFlipped, repeatCount)) {
                    showError(player, detectionError, isFlipped);
                }
            } finally {
                running = false;
            }
        });
    }

    private static List<DetectionError> check(IMultiController controller, BlockPattern pattern, boolean isFlipped,
                                              int repeatCount) {
        var errors = new ObjectArrayList<DetectionError>();
        if (controller == null || pattern == null) {
            errors.add(new DetectionError(new PatternStringError("no controller found"), null));
            return errors;
        }
        var centerPos = controller.self().getPos();
        var frontFacing = controller.self().getFrontFacing();
        var facings = controller.hasFrontFacing() ? new Direction[] { frontFacing } :
                new Direction[] { Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST };
        if (controller.self().getBlockState().getBlock() instanceof MetaMachineBlock machineBlock) {
            if (machineBlock.rotationState == RotationState.NONE) facings = new Direction[] { frontFacing };
        }
        var upwardsFacing = controller.self().getUpwardsFacing();
        for (var direction : facings) {
            var worldState = new MultiblockState(controller.self().getLevel(), controller.self().getPos());
            try (var scope = StructureDetectionScope.begin(worldState, repeatCount)) {
                pattern.checkPatternAt(worldState, centerPos, direction, upwardsFacing, isFlipped, false);
            }
            if (worldState.hasError()) errors.add(new DetectionError(worldState.error, worldState.predicate));
        }
        return errors;
    }

    private static void showError(Player player, DetectionError detectionError, boolean flip) {
        var error = detectionError.error();
        var show = new ObjectArrayList<Component>();
        if (error instanceof PatternStringError pe) {
            player.sendSystemMessage(pe.getErrorInfo());
            return;
        }
        var pos = error.getPos();
        var posComponent = Component.translatable("message.gtlcore.structure_detect.error.2", pos.getX(), pos.getY(), pos.getZ(), flip ?
                Component.translatable("message.gtlcore.structure_detect.error.3").withStyle(ChatFormatting.GREEN) :
                Component.translatable("message.gtlcore.structure_detect.error.4").withStyle(ChatFormatting.YELLOW));
        var candidates = getCandidateNames(detectionError.predicate());
        if (error instanceof SinglePredicateError singlePredicateError) {
            var roots = getCandidateNames(singlePredicateError.predicate);
            show.add(Component.translatable("message.gtlcore.structure_detect.error.1", posComponent));
            var detail = Component.literal(" - ");
            if (!roots.isEmpty()) detail.append(roots.get(0));
            show.add(detail.append(error.getErrorInfo()));
        } else {
            show.add(Component.translatable("message.gtlcore.structure_detect.error.0", posComponent));
            for (var candidate : candidates) {
                if (!candidate.isEmpty()) show.add(Component.literal(" - ").append(candidate.get(0)));
            }
        }
        show.forEach(player::sendSystemMessage);
        if (player instanceof ServerPlayer serverPlayer) {
            GTLNetworkHandler.INSTANCE.sendTo(new SStructureDetectHighlight(error.getPos(), error.getWorld().dimension(),
                    System.currentTimeMillis() + 15000), serverPlayer);
        }
    }

    private static List<List<Component>> getCandidateNames(TraceabilityPredicate predicate) {
        if (predicate == null) return List.of();
        return java.util.stream.Stream.concat(predicate.common.stream(), predicate.limited.stream())
                .map(StructureDetection::getCandidateNames)
                .filter(candidates -> !candidates.isEmpty())
                .toList();
    }

    private static List<Component> getCandidateNames(SimplePredicate predicate) {
        if (predicate.candidates == null) return List.of();
        return Arrays.stream(predicate.candidates.get())
                .map(StructureDetection::getCandidateName)
                .filter(component -> component != null)
                .toList();
    }

    private static Component getCandidateName(BlockInfo candidate) {
        var fluidState = candidate.getBlockState().getFluidState();
        if (!fluidState.isEmpty()) return fluidState.getType().getFluidType().getDescription();
        var itemStack = candidate.getItemStackForm();
        return itemStack.isEmpty() ? null : itemStack.getHoverName();
    }

    private record DetectionError(PatternError error, TraceabilityPredicate predicate) {}
}
