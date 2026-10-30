package org.gtlcore.gtlcore.integration.voxelregion;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import org.voxelregion.forge.ForgeRegionEngine;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/** Explicit operator entry point; ordinary players cannot submit region changes. */
final class GtlRegionCommands {

    static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("gtlregion").requires(source -> source.hasPermission(2));
        var radius = Commands.argument("radius", IntegerArgumentType.integer(1, 10000))
                .executes(context -> submit(context.getSource(), IntegerArgumentType.getInteger(context, "radius"),
                        ForgeRegionEngine.Options.DEFAULT.generateMissingChunks(), true));
        var generate = Commands.argument("generate", BoolArgumentType.bool())
                .executes(context -> submit(context.getSource(), IntegerArgumentType.getInteger(context, "radius"),
                        BoolArgumentType.getBool(context, "generate"), true));
        var clearFluids = Commands.argument("clearFluids", BoolArgumentType.bool())
                .executes(context -> submit(context.getSource(), IntegerArgumentType.getInteger(context, "radius"),
                        BoolArgumentType.getBool(context, "generate"), BoolArgumentType.getBool(context, "clearFluids")));
        clearFluids.then(Commands.argument("destroyBedrock", BoolArgumentType.bool())
                .executes(context -> submit(context.getSource(), IntegerArgumentType.getInteger(context, "radius"),
                        BoolArgumentType.getBool(context, "generate"), BoolArgumentType.getBool(context, "clearFluids"), BoolArgumentType.getBool(context, "destroyBedrock"))));
        generate.then(clearFluids);
        radius.then(generate);
        root.then(Commands.literal("blast").then(radius));
        root.then(Commands.literal("inspect")
                .executes(context -> inspect(context.getSource(), BlockPos.containing(context.getSource().getPosition())))
                .then(Commands.argument("chunkX", IntegerArgumentType.integer(-1875000, 1874999))
                        .then(Commands.argument("chunkZ", IntegerArgumentType.integer(-1875000, 1874999))
                                .executes(context -> inspect(context.getSource(), new BlockPos(
                                        IntegerArgumentType.getInteger(context, "chunkX") * 16 + 8,
                                        BlockPos.containing(context.getSource().getPosition()).getY(),
                                        IntegerArgumentType.getInteger(context, "chunkZ") * 16 + 8))))));
        root.then(Commands.literal("status").executes(context -> {
            int count = 0;
            for (var level : context.getSource().getServer().getAllLevels()) {
                for (var status : GtlRegionExplosions.statuses(level)) {
                    context.getSource().sendSuccess(() -> Component.literal(level.dimension().location() + " ").append(status.asComponent()), false);
                    count++;
                }
            }
            if (count == 0) context.getSource().sendSuccess(() -> Component.literal("No region operations."), false);
            return count;
        }));
        for (String action : new String[] { "pause", "resume", "cancel" }) {
            root.then(Commands.literal(action).then(Commands.argument("id", StringArgumentType.word())
                    .executes(context -> control(context.getSource(), StringArgumentType.getString(context, "id"), action))));
        }
        event.getDispatcher().register(root);
    }

    private static int inspect(CommandSourceStack source, BlockPos position) {
        var lines = ForgeRegionEngine.inspectChunk(source.getLevel(), position);
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal("[VRE inspect] " + line), false);
            GTLCore.LOGGER.info("[VRE inspect] {}", line);
        }
        return 1;
    }

    private static int submit(CommandSourceStack source, int radius, boolean generate, boolean clearFluids) {
        return submit(source, radius, generate, clearFluids, false);
    }

    private static int submit(CommandSourceStack source, int radius, boolean generate, boolean clearFluids, boolean destroyBedrock) {
        UUID actor = source.getEntity() == null ? new UUID(0, 0) : source.getEntity().getUUID();
        long started = System.nanoTime();
        BlockPos center = BlockPos.containing(source.getPosition());
        ForgeRegionEngine.Handle handle;
        try {
            handle = GtlRegionExplosions.submitSphere(source.getLevel(), actor, center, radius,
                    new ForgeRegionEngine.Options(0, generate, clearFluids, destroyBedrock));
        } catch (IOException | RuntimeException failure) {
            source.sendFailure(Component.literal("Region operation not accepted: " + failure.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("区域清理已开始：" + handle.id() + "，半径 " + radius + "。进度按实际完成区块统计；/gtlregion status 查看详情。"), false);
        try {
            GtlRegionProgress.watch(source, handle, center, radius, started);
        } catch (RuntimeException failure) {
            source.sendFailure(Component.literal("操作已接纳，但进度显示不可用；请使用 /gtlregion status 查看。"));
        }
        return 1;
    }

    private static int control(CommandSourceStack source, String text, String action) {
        UUID id;
        try {
            id = UUID.fromString(text);
        } catch (IllegalArgumentException failure) {
            source.sendFailure(Component.literal("Expected operation UUID from /gtlregion status."));
            return 0;
        }
        for (var level : source.getServer().getAllLevels()) {
            var result = GtlRegionExplosions.control(level, id, ForgeRegionEngine.Control.valueOf(action.toUpperCase(Locale.ROOT)));
            if (result.status().isEmpty()) continue;
            if (!result.applied()) {
                source.sendFailure(Component.literal(result.reason()));
                return 0;
            }
            source.sendSuccess(result.status().orElseThrow()::asComponent, true);
            return 1;
        }
        source.sendFailure(Component.literal("Unknown region operation."));
        return 0;
    }

    private GtlRegionCommands() {}
}
